"""Usuários (contas) cadastrados neste computador.

Cada conta tem a própria pasta de dados. A tela de login lista as contas, permite
cadastrar uma nova e restaurar uma conta a partir de um backup.
"""

import shutil
import sqlite3
import uuid
from dataclasses import dataclass
from pathlib import Path

from recipay import config

PASTA_CONTAS = "contas"
ARQUIVO_ULTIMA = "ultima_conta.txt"


@dataclass
class Conta:
    pasta: Path
    usuario_id: str
    nome: str
    perfil: str
    matricula: str | None = None

    @property
    def perfil_nome(self) -> str:
        return {"SUPERVISOR": "Supervisor", "GESTOR": "Gestor"}.get(self.perfil, self.perfil)


def _pasta_contas() -> Path:
    pasta = config.pasta_base() / PASTA_CONTAS
    pasta.mkdir(parents=True, exist_ok=True)
    return pasta


def migrar_instalacao_antiga() -> None:
    """Versões anteriores guardavam um único usuário direto na pasta base. Move para contas\\."""
    base = config.pasta_base()
    antigo = base / "recipay.db"
    if not antigo.is_file():
        return
    destino = _pasta_contas() / uuid.uuid4().hex
    destino.mkdir()
    for nome in ("recipay.db", "recipay.db-wal", "recipay.db-shm", "comprovantes", "temp"):
        if (base / nome).exists():
            shutil.move(str(base / nome), str(destino / nome))


def _ler_conta(pasta: Path) -> Conta | None:
    banco = pasta / "recipay.db"
    if not banco.is_file():
        return None
    try:
        con = sqlite3.connect(banco)
        try:
            colunas = {c[1] for c in con.execute("PRAGMA table_info(usuario)")}
            campo = "matricula" if "matricula" in colunas else "NULL"
            l = con.execute(f"SELECT id, nome, perfil, {campo} FROM usuario WHERE local = 1").fetchone()
        finally:
            con.close()
    except sqlite3.Error:
        return None
    return Conta(pasta, l[0], l[1], l[2], l[3]) if l else None


def listar() -> list[Conta]:
    contas = [c for p in sorted(_pasta_contas().iterdir()) if p.is_dir() and (c := _ler_conta(p))]
    return sorted(contas, key=lambda c: c.nome.lower())


def nova_pasta() -> Path:
    pasta = _pasta_contas() / uuid.uuid4().hex
    pasta.mkdir()
    return pasta


def descartar_pasta_vazia(pasta: Path) -> None:
    """Remove a pasta de um cadastro que foi cancelado (sem usuário criado)."""
    if pasta.is_dir() and _ler_conta(pasta) is None and pasta.parent == _pasta_contas():
        shutil.rmtree(pasta, ignore_errors=True)


def ultima() -> str | None:
    arquivo = config.pasta_base() / ARQUIVO_ULTIMA
    try:
        return arquivo.read_text(encoding="utf-8").strip() or None
    except OSError:
        return None


def lembrar(conta: Conta) -> None:
    try:
        (config.pasta_base() / ARQUIVO_ULTIMA).write_text(conta.usuario_id, encoding="utf-8")
    except OSError:
        pass
