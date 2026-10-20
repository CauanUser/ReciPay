"""Backup criptografado dos dados do computador (D-05).

O arquivo .recipay-backup contém o banco de dados e todos os comprovantes, cifrados
com AES-256-GCM. A chave vem da senha de backup (scrypt). Sem a senha, não há como abrir.
"""

import io
import os
import secrets
import shutil
import sqlite3
import tempfile
import zipfile
from datetime import datetime
from pathlib import Path

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.scrypt import Scrypt

from recipay import config
from recipay.db import agora

MAGICA = b"RECIPAYBK1"
SENHA_MINIMA = 8
DIAS_LEMBRETE = 7
EXTENSAO = ".recipay-backup"


def _chave(senha: str, sal: bytes) -> bytes:
    return Scrypt(salt=sal, length=32, n=2**15, r=8, p=1).derive(senha.encode("utf-8"))


def ultimo_backup(con: sqlite3.Connection) -> datetime | None:
    l = con.execute("SELECT valor FROM configuracao WHERE chave = 'ultimo_backup'").fetchone()
    return datetime.fromisoformat(l[0]) if l and l[0] else None


def precisa_lembrar(con: sqlite3.Connection) -> bool:
    """Lembrar se há dados e o último backup foi há mais de 7 dias (ou nunca)."""
    tem_dados = con.execute("SELECT EXISTS(SELECT 1 FROM recibo)").fetchone()[0]
    if not tem_dados:
        return False
    ultimo = ultimo_backup(con)
    return ultimo is None or (datetime.now() - ultimo).days >= DIAS_LEMBRETE


def exportar(con: sqlite3.Connection, caminho: Path | str, senha: str) -> int:
    """Gera o backup. Devolve o tamanho em bytes."""
    if len(senha) < SENHA_MINIMA:
        raise ValueError(f"A senha do backup precisa ter pelo menos {SENHA_MINIMA} caracteres.")
    caminho = Path(caminho)
    pasta = config.pasta_dados()

    with tempfile.TemporaryDirectory() as tmp:
        copia_banco = Path(tmp) / "recipay.db"
        destino = sqlite3.connect(copia_banco)
        try:
            con.backup(destino)  # cópia consistente, mesmo com o programa aberto
        finally:
            destino.close()
        zip_mem = io.BytesIO()
        with zipfile.ZipFile(zip_mem, "w", zipfile.ZIP_DEFLATED) as z:
            z.write(copia_banco, "recipay.db")
            comp = pasta / "comprovantes"
            if comp.is_dir():
                for arquivo in sorted(comp.iterdir()):
                    if arquivo.is_file():
                        z.write(arquivo, f"comprovantes/{arquivo.name}")
            z.writestr("info.txt", f"Backup do Recipay gerado em {agora()}\n")

    sal, nonce = secrets.token_bytes(16), secrets.token_bytes(12)
    cifrado = AESGCM(_chave(senha, sal)).encrypt(nonce, zip_mem.getvalue(), MAGICA)
    temporario = caminho.with_suffix(caminho.suffix + ".tmp")
    try:
        temporario.write_bytes(MAGICA + sal + nonce + cifrado)
        os.replace(temporario, caminho)
    except OSError as e:
        temporario.unlink(missing_ok=True)
        raise OSError(f"Não foi possível salvar o backup: {e}") from e

    with con:
        con.execute("INSERT OR REPLACE INTO configuracao (chave, valor) VALUES ('ultimo_backup', ?)",
                    (agora(),))
    return caminho.stat().st_size


def _abrir(caminho: Path, senha: str) -> zipfile.ZipFile:
    bruto = Path(caminho).read_bytes()
    if not bruto.startswith(MAGICA):
        raise ValueError("Este arquivo não é um backup do Recipay.")
    n = len(MAGICA)
    sal, nonce, cifrado = bruto[n:n + 16], bruto[n + 16:n + 28], bruto[n + 28:]
    try:
        conteudo = AESGCM(_chave(senha, sal)).decrypt(nonce, cifrado, MAGICA)
    except InvalidTag as e:
        raise ValueError("Senha incorreta ou arquivo de backup danificado.") from e
    return zipfile.ZipFile(io.BytesIO(conteudo))


def conferir(caminho: Path | str, senha: str) -> dict:
    """Abre o backup sem restaurar e devolve um resumo (quem, perfil, quantidades)."""
    with _abrir(Path(caminho), senha) as z, tempfile.TemporaryDirectory() as tmp:
        if "recipay.db" not in z.namelist():
            raise ValueError("Backup incompleto: banco de dados não encontrado.")
        z.extract("recipay.db", tmp)
        c = sqlite3.connect(Path(tmp) / "recipay.db")
        try:
            u = c.execute("SELECT nome, perfil, id FROM usuario WHERE local = 1").fetchone()
            recibos = c.execute("SELECT COUNT(*) FROM recibo").fetchone()[0]
            colabs = c.execute("SELECT COUNT(*) FROM colaborador").fetchone()[0]
        except sqlite3.DatabaseError as e:
            raise ValueError("Backup danificado: banco de dados inválido.") from e
        finally:
            c.close()
        if not u:
            raise ValueError("Backup sem usuário configurado.")
        return {"nome": u[0], "perfil": u[1], "id": u[2], "recibos": recibos, "colaboradores": colabs,
                "comprovantes": sum(1 for n in z.namelist() if n.startswith("comprovantes/"))}


def restaurar(caminho: Path | str, senha: str) -> dict:
    """Substitui TODOS os dados deste computador pelos do backup.

    A conexão com o banco precisa estar FECHADA antes de chamar esta função.
    Os dados atuais são guardados numa pasta "antes_da_restauracao_<data>" por segurança.
    """
    resumo = conferir(caminho, senha)
    pasta = config.pasta_dados()
    guarda = pasta / f"antes_da_restauracao_{datetime.now():%Y%m%d_%H%M%S}"
    guarda.mkdir()
    for nome in ("recipay.db", "recipay.db-wal", "recipay.db-shm", "comprovantes"):
        alvo = pasta / nome
        if alvo.exists():
            shutil.move(str(alvo), str(guarda / nome))
    with _abrir(Path(caminho), senha) as z:
        for nome in z.namelist():
            if nome == "recipay.db" or (nome.startswith("comprovantes/") and "/" not in nome[13:]
                                        and ".." not in nome):
                z.extract(nome, pasta)
    return resumo
