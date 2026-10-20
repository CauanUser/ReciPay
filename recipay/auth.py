"""Usuário local e senha (login sem servidor, D-01)."""

import hashlib
import hmac
import re
import secrets
import sqlite3
from dataclasses import dataclass

from recipay.db import agora, novo_id, registrar_historico

PERFIS = {"SUPERVISOR": "Supervisor", "GESTOR": "Gestor"}
SENHA_MINIMA = 6
PREFIXO_RECUPERACAO = "Cosampa@"

# Parâmetros do scrypt (biblioteca padrão do Python, sem custo).
_N, _R, _P = 2**14, 8, 1


@dataclass
class Usuario:
    id: str
    nome: str
    perfil: str

    @property
    def perfil_nome(self) -> str:
        return PERFIS[self.perfil]


def gerar_hash(senha: str) -> str:
    sal = secrets.token_bytes(16)
    chave = hashlib.scrypt(senha.encode("utf-8"), salt=sal, n=_N, r=_R, p=_P)
    return f"scrypt${_N}${_R}${_P}${sal.hex()}${chave.hex()}"


def conferir_senha(senha: str, armazenado: str) -> bool:
    try:
        _, n, r, p, sal_hex, chave_hex = armazenado.split("$")
        chave = hashlib.scrypt(
            senha.encode("utf-8"), salt=bytes.fromhex(sal_hex), n=int(n), r=int(r), p=int(p)
        )
    except (ValueError, TypeError):
        return False
    return hmac.compare_digest(chave.hex(), chave_hex)


def normalizar_matricula(matricula: str) -> str:
    m = (matricula or "").strip()
    if not re.fullmatch(r"[A-Za-z0-9.\-]{2,20}", m):
        raise ValueError("Informe a matrícula (somente letras e números, sem espaços).")
    return m.upper()


def codigo_recuperacao(matricula: str) -> str:
    return f"{PREFIXO_RECUPERACAO}{normalizar_matricula(matricula)}"


def _normalizar_codigo(codigo: str) -> str:
    """Aceita 'cosampa@123' ou 'Cosampa@ 123' como 'Cosampa@123'."""
    c = "".join((codigo or "").split())
    if c.lower().startswith(PREFIXO_RECUPERACAO.lower()):
        c = PREFIXO_RECUPERACAO + c[len(PREFIXO_RECUPERACAO):].upper()
    return c


def matricula_local(con: sqlite3.Connection) -> str | None:
    l = con.execute("SELECT matricula FROM usuario WHERE local = 1").fetchone()
    return l["matricula"] if l else None


def definir_matricula(con: sqlite3.Connection, matricula: str) -> None:
    """Grava a matrícula (e o código de recuperação) do usuário deste computador."""
    m = normalizar_matricula(matricula)
    with con:
        con.execute("UPDATE usuario SET matricula = ?, recuperacao_hash = ? WHERE local = 1",
                    (m, gerar_hash(codigo_recuperacao(m))))


def redefinir_senha(con: sqlite3.Connection, codigo: str, nova_senha: str) -> Usuario:
    """Troca a senha usando o código de recuperação (Cosampa@<matrícula>)."""
    l = con.execute("SELECT id, nome, perfil, recuperacao_hash FROM usuario WHERE local = 1").fetchone()
    if not l:
        raise ValueError("Usuário não encontrado.")
    if not l["recuperacao_hash"]:
        raise ValueError("Este usuário foi cadastrado sem matrícula e não tem código de recuperação.")
    if not conferir_senha(_normalizar_codigo(codigo), l["recuperacao_hash"]):
        raise ValueError("Código de recuperação incorreto.")
    if len(nova_senha) < SENHA_MINIMA:
        raise ValueError(f"A nova senha precisa ter pelo menos {SENHA_MINIMA} caracteres.")
    with con:
        con.execute("UPDATE usuario SET senha_hash = ? WHERE id = ?", (gerar_hash(nova_senha), l["id"]))
        registrar_historico(con, "usuario", l["id"], "SENHA_REDEFINIDA", None, l["id"], l["perfil"])
    return Usuario(l["id"], l["nome"], l["perfil"])


def usuario_local(con: sqlite3.Connection) -> Usuario | None:
    linha = con.execute("SELECT id, nome, perfil FROM usuario WHERE local = 1").fetchone()
    return Usuario(linha["id"], linha["nome"], linha["perfil"]) if linha else None


def criar_usuario_local(
    con: sqlite3.Connection, nome: str, perfil: str, senha: str, matricula: str | None = None
) -> Usuario:
    nome = nome.strip()
    if not nome:
        raise ValueError("Informe o nome.")
    if perfil not in PERFIS:
        raise ValueError("Perfil inválido.")
    if len(senha) < SENHA_MINIMA:
        raise ValueError(f"A senha precisa ter pelo menos {SENHA_MINIMA} caracteres.")
    if usuario_local(con):
        raise ValueError("Este computador já está configurado.")
    m = normalizar_matricula(matricula) if matricula is not None else None

    usuario = Usuario(novo_id(), nome, perfil)
    with con:
        con.execute(
            "INSERT INTO usuario (id, nome, perfil, senha_hash, local, criado_em, matricula, recuperacao_hash)"
            " VALUES (?, ?, ?, ?, 1, ?, ?, ?)",
            (usuario.id, usuario.nome, usuario.perfil, gerar_hash(senha), agora(),
             m, gerar_hash(codigo_recuperacao(m)) if m else None),
        )
        registrar_historico(
            con, "usuario", usuario.id, "CONFIGURADO", {"nome": nome, "perfil": perfil, "matricula": m},
            usuario.id, perfil,
        )
    return usuario


def autenticar(con: sqlite3.Connection, perfil_escolhido: str, senha: str) -> Usuario:
    """Confere a senha do usuário local.

    A escolha do perfil na tela NÃO concede permissão: o perfil válido é o gravado na conta.
    """
    linha = con.execute(
        "SELECT id, nome, perfil, senha_hash FROM usuario WHERE local = 1"
    ).fetchone()
    if not linha:
        raise ValueError("Este computador ainda não foi configurado.")
    if not conferir_senha(senha, linha["senha_hash"]):
        raise ValueError("Senha incorreta.")
    if perfil_escolhido != linha["perfil"]:
        raise ValueError(
            f"Esta conta é de {PERFIS[linha['perfil']]}. "
            f"Escolha o perfil {PERFIS[linha['perfil']]} para entrar."
        )
    return Usuario(linha["id"], linha["nome"], linha["perfil"])
