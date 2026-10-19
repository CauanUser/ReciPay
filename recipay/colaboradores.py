"""Regras de cadastro de colaboradores (seção 4 do resumo / RN-12, RN-13)."""

import sqlite3
from dataclasses import dataclass

from recipay.auth import Usuario
from recipay.db import agora, novo_id, registrar_historico
from recipay.pix import TIPOS_CHAVE, normalizar_chave

CAMPOS = ("nome", "equipe", "chave_pix", "tipo_chave", "titular_pix")


@dataclass
class Colaborador:
    id: str
    supervisor_id: str
    nome: str
    equipe: str
    chave_pix: str
    tipo_chave: str
    titular_pix: str
    ativo: bool


def _de_linha(linha: sqlite3.Row) -> Colaborador:
    return Colaborador(
        linha["id"], linha["supervisor_id"], linha["nome"], linha["equipe"],
        linha["chave_pix"], linha["tipo_chave"], linha["titular_pix"], bool(linha["ativo"]),
    )


def validar(dados: dict) -> dict:
    """Confere e normaliza os campos. Levanta ValueError com a mensagem do problema."""
    nome = " ".join((dados.get("nome") or "").split())
    equipe = " ".join((dados.get("equipe") or "").split())
    tipo = dados.get("tipo_chave") or ""
    titular = " ".join((dados.get("titular_pix") or "").split())

    if len(nome) < 3:
        raise ValueError("Informe o nome completo.")
    if not equipe:
        raise ValueError("Informe a equipe.")
    if tipo not in TIPOS_CHAVE:
        raise ValueError("Escolha o tipo da chave Pix.")
    chave = normalizar_chave(tipo, dados.get("chave_pix") or "")
    if not titular:
        raise ValueError("Informe o nome do titular da conta Pix.")

    return {"nome": nome, "equipe": equipe, "chave_pix": chave,
            "tipo_chave": tipo, "titular_pix": titular}


def buscar(con: sqlite3.Connection, colaborador_id: str) -> Colaborador | None:
    linha = con.execute("SELECT * FROM colaborador WHERE id = ?", (colaborador_id,)).fetchone()
    return _de_linha(linha) if linha else None


def listar(
    con: sqlite3.Connection,
    busca: str = "",
    equipe: str | None = None,
    incluir_inativos: bool = False,
) -> list[Colaborador]:
    sql = "SELECT * FROM colaborador WHERE 1 = 1"
    params: list = []
    if not incluir_inativos:
        sql += " AND ativo = 1"
    if equipe:
        sql += " AND equipe = ?"
        params.append(equipe)
    if busca.strip():
        sql += " AND (nome LIKE ? OR titular_pix LIKE ? OR chave_pix LIKE ?)"
        termo = f"%{busca.strip()}%"
        params += [termo, termo, termo]
    sql += " ORDER BY nome COLLATE NOCASE"
    return [_de_linha(l) for l in con.execute(sql, params)]


def equipes(con: sqlite3.Connection) -> list[str]:
    return [l[0] for l in con.execute(
        "SELECT DISTINCT equipe FROM colaborador ORDER BY equipe COLLATE NOCASE")]


def chave_em_uso(con: sqlite3.Connection, chave: str, ignorar_id: str | None = None) -> Colaborador | None:
    linha = con.execute(
        "SELECT * FROM colaborador WHERE chave_pix = ? AND ativo = 1 AND id IS NOT ?",
        (chave, ignorar_id),
    ).fetchone()
    return _de_linha(linha) if linha else None


def _exigir_supervisor(usuario: Usuario) -> None:
    if usuario.perfil != "SUPERVISOR":
        raise PermissionError("Somente o supervisor pode cadastrar ou alterar colaboradores.")


def criar(con: sqlite3.Connection, usuario: Usuario, dados: dict, origem: str = "MANUAL") -> Colaborador:
    _exigir_supervisor(usuario)
    v = validar(dados)
    existente = chave_em_uso(con, v["chave_pix"])
    if existente:
        raise ValueError(f"Esta chave Pix já está cadastrada para {existente.nome}.")

    c = Colaborador(novo_id(), usuario.id, v["nome"], v["equipe"], v["chave_pix"],
                    v["tipo_chave"], v["titular_pix"], True)
    momento = agora()
    with con:
        con.execute(
            "INSERT INTO colaborador (id, supervisor_id, nome, equipe, chave_pix, tipo_chave,"
            " titular_pix, ativo, criado_em, atualizado_em) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?)",
            (c.id, c.supervisor_id, c.nome, c.equipe, c.chave_pix, c.tipo_chave,
             c.titular_pix, momento, momento),
        )
        registrar_historico(con, "colaborador", c.id, "CRIADO", {**v, "origem": origem},
                            usuario.id, usuario.perfil)
    return c


def atualizar(con: sqlite3.Connection, usuario: Usuario, colaborador_id: str, dados: dict) -> Colaborador:
    _exigir_supervisor(usuario)
    atual = buscar(con, colaborador_id)
    if not atual:
        raise ValueError("Colaborador não encontrado.")
    v = validar(dados)
    existente = chave_em_uso(con, v["chave_pix"], ignorar_id=colaborador_id)
    if existente:
        raise ValueError(f"Esta chave Pix já está cadastrada para {existente.nome}.")

    mudancas = {
        campo: {"antes": getattr(atual, campo), "depois": v[campo]}
        for campo in CAMPOS if getattr(atual, campo) != v[campo]
    }
    if not mudancas:
        return atual
    with con:
        con.execute(
            "UPDATE colaborador SET nome = ?, equipe = ?, chave_pix = ?, tipo_chave = ?,"
            " titular_pix = ?, atualizado_em = ? WHERE id = ?",
            (v["nome"], v["equipe"], v["chave_pix"], v["tipo_chave"], v["titular_pix"],
             agora(), colaborador_id),
        )
        registrar_historico(con, "colaborador", colaborador_id, "ALTERADO", mudancas,
                            usuario.id, usuario.perfil)
    return buscar(con, colaborador_id)


def definir_ativo(con: sqlite3.Connection, usuario: Usuario, colaborador_id: str, ativo: bool) -> None:
    """Desativa ou reativa. Nada é apagado (RN-12)."""
    _exigir_supervisor(usuario)
    atual = buscar(con, colaborador_id)
    if not atual:
        raise ValueError("Colaborador não encontrado.")
    if ativo and chave_em_uso(con, atual.chave_pix, ignorar_id=colaborador_id):
        raise ValueError("Já existe um colaborador ativo com esta chave Pix.")
    with con:
        con.execute("UPDATE colaborador SET ativo = ?, atualizado_em = ? WHERE id = ?",
                    (int(ativo), agora(), colaborador_id))
        registrar_historico(con, "colaborador", colaborador_id,
                            "REATIVADO" if ativo else "DESATIVADO", None,
                            usuario.id, usuario.perfil)
