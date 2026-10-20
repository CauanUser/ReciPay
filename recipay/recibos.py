"""Regras de cadastro de recibos (seção 4.1 da especificação)."""

import sqlite3
from dataclasses import dataclass
from datetime import date, timedelta

from recipay import colaboradores, comprovantes
from recipay.auth import Usuario
from recipay.db import agora, novo_id, registrar_historico

STATUS = {
    "RASCUNHO": "Rascunho",
    "ENVIADO": "Enviado",
    "PENDENTE": "Pendente",
    "PARCIAL": "Parcial",
    "PAGO": "Pago",
    "CANCELADO": "Cancelado",
}
DIAS_MAXIMOS_NO_PASSADO = 365


@dataclass
class Recibo:
    id: str
    colaborador_id: str
    colaborador_nome: str
    equipe: str
    valor_centavos: int
    saldo_centavos: int
    descricao: str
    data_despesa: str  # AAAA-MM-DD
    comprovante_arquivo: str
    status: str
    cancelamento_motivo: str | None
    criado_em: str
    supervisor_id: str = ""
    supervisor_nome: str = ""
    lote_id: str | None = None

    @property
    def editavel(self) -> bool:
        return self.status == "RASCUNHO"


_SELECT = """
SELECT r.*, c.nome AS colaborador_nome, s.saldo_centavos, u.nome AS supervisor_nome
FROM recibo r
JOIN colaborador c ON c.id = r.colaborador_id
JOIN recibo_saldo s ON s.recibo_id = r.id
LEFT JOIN usuario u ON u.id = r.supervisor_id
"""


def _de_linha(l: sqlite3.Row) -> Recibo:
    return Recibo(
        l["id"], l["colaborador_id"], l["colaborador_nome"], l["equipe"], l["valor_centavos"],
        l["saldo_centavos"], l["descricao"], l["data_despesa"], l["comprovante_arquivo"],
        l["status"], l["cancelamento_motivo"], l["criado_em"],
        l["supervisor_id"], l["supervisor_nome"] or "", l["lote_id"],
    )


def _exigir_supervisor(usuario: Usuario) -> None:
    if usuario.perfil != "SUPERVISOR":
        raise PermissionError("Somente o supervisor pode cadastrar ou alterar recibos.")


def validar(con: sqlite3.Connection, dados: dict) -> dict:
    """Confere os campos (exceto o comprovante). Levanta ValueError."""
    colaborador = colaboradores.buscar(con, dados.get("colaborador_id") or "")
    if not colaborador:
        raise ValueError("Escolha o colaborador.")
    if not colaborador.ativo:
        raise ValueError("Este colaborador está inativo.")

    valor = dados.get("valor_centavos")
    if not isinstance(valor, int) or valor <= 0:
        raise ValueError("Informe um valor maior que zero.")

    descricao = " ".join((dados.get("descricao") or "").split())
    if len(descricao) < 3:
        raise ValueError("Descreva a despesa (ex.: Almoço com cliente).")

    data_txt = dados.get("data_despesa") or ""
    try:
        data = date.fromisoformat(data_txt)
    except ValueError as e:
        raise ValueError("Data inválida.") from e
    if data > date.today():
        raise ValueError("A data da despesa não pode ser no futuro.")
    if data < date.today() - timedelta(days=DIAS_MAXIMOS_NO_PASSADO):
        raise ValueError("A data da despesa tem mais de um ano. Confira a data.")

    equipe = " ".join((dados.get("equipe") or colaborador.equipe).split())
    return {
        "colaborador_id": colaborador.id,
        "equipe": equipe,
        "valor_centavos": valor,
        "descricao": descricao,
        "data_despesa": data.isoformat(),
    }


def _inserir(con: sqlite3.Connection, usuario: Usuario, v: dict, arquivo: str, hash_: str) -> str:
    recibo_id = novo_id()
    con.execute(
        "INSERT INTO recibo (id, colaborador_id, supervisor_id, equipe, valor_centavos,"
        " descricao, data_despesa, comprovante_arquivo, comprovante_hash, status, criado_em)"
        " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'RASCUNHO', ?)",
        (recibo_id, v["colaborador_id"], usuario.id, v["equipe"], v["valor_centavos"],
         v["descricao"], v["data_despesa"], arquivo, hash_, agora()),
    )
    registrar_historico(con, "recibo", recibo_id, "CRIADO", {**v, "comprovante": arquivo},
                        usuario.id, usuario.perfil)
    return recibo_id


def criar(con: sqlite3.Connection, usuario: Usuario, dados: dict, comprovante_origem: str) -> Recibo:
    """Cadastra o recibo como RASCUNHO. O comprovante é obrigatório (RN-01)."""
    return criar_lote(con, usuario, [(dados, comprovante_origem)], numerar=False)[0]


def criar_lote(
    con: sqlite3.Connection,
    usuario: Usuario,
    itens: list[tuple[dict, str]],
    numerar: bool = True,
) -> list[Recibo]:
    """Cadastra vários recibos de uma vez: tudo ou nada.

    Valida todos e prepara os comprovantes antes de gravar. Em caso de erro,
    informa a linha e não grava nenhum recibo.
    """
    _exigir_supervisor(usuario)
    if not itens:
        raise ValueError("Adicione pelo menos um recibo.")
    preparados = []
    for i, (dados, comprovante) in enumerate(itens, start=1):
        try:
            v = validar(con, dados)
            if not comprovante:
                raise ValueError("Anexe o comprovante da despesa.")
            arquivo, hash_ = comprovantes.guardar(comprovante)
        except ValueError as e:
            raise ValueError(f"Linha {i}: {e}" if numerar else str(e)) from e
        preparados.append((v, arquivo, hash_))
    with con:
        ids = [_inserir(con, usuario, v, arquivo, hash_) for v, arquivo, hash_ in preparados]
    return [buscar(con, i) for i in ids]


def buscar(con: sqlite3.Connection, recibo_id: str) -> Recibo | None:
    l = con.execute(_SELECT + " WHERE r.id = ?", (recibo_id,)).fetchone()
    return _de_linha(l) if l else None


def listar(
    con: sqlite3.Connection,
    status: str | tuple[str, ...] | None = None,
    equipe: str | None = None,
    busca: str = "",
    data_inicio: str | None = None,
    data_fim: str | None = None,
    supervisor_id: str | None = None,
) -> list[Recibo]:
    sql = _SELECT + " WHERE 1 = 1"
    params: list = []
    if isinstance(status, tuple):
        sql += f" AND r.status IN ({','.join('?' * len(status))})"
        params += list(status)
    elif status:
        sql += " AND r.status = ?"
        params.append(status)
    if equipe:
        sql += " AND r.equipe = ?"
        params.append(equipe)
    if busca.strip():
        sql += " AND (c.nome LIKE ? OR r.descricao LIKE ?)"
        termo = f"%{busca.strip()}%"
        params += [termo, termo]
    if supervisor_id:
        sql += " AND r.supervisor_id = ?"
        params.append(supervisor_id)
    if data_inicio:
        sql += " AND r.data_despesa >= ?"
        params.append(data_inicio)
    if data_fim:
        sql += " AND r.data_despesa <= ?"
        params.append(data_fim)
    sql += " ORDER BY r.data_despesa DESC, r.criado_em DESC"
    return [_de_linha(l) for l in con.execute(sql, params)]


def equipes(con: sqlite3.Connection) -> list[str]:
    return [l[0] for l in con.execute(
        "SELECT DISTINCT equipe FROM recibo ORDER BY equipe COLLATE NOCASE")]


def atualizar(
    con: sqlite3.Connection,
    usuario: Usuario,
    recibo_id: str,
    dados: dict,
    novo_comprovante: str | None = None,
) -> Recibo:
    """Só recibos em RASCUNHO podem ser editados (depois de exportados, não)."""
    _exigir_supervisor(usuario)
    atual = buscar(con, recibo_id)
    if not atual:
        raise ValueError("Recibo não encontrado.")
    if not atual.editavel:
        raise ValueError("Este recibo já foi exportado e não pode mais ser editado.")
    v = validar(con, dados)

    campos = ("colaborador_id", "equipe", "valor_centavos", "descricao", "data_despesa")
    mudancas = {c: {"antes": getattr(atual, c), "depois": v[c]}
                for c in campos if getattr(atual, c) != v[c]}
    arquivo, hash_ = atual.comprovante_arquivo, None
    if novo_comprovante:
        arquivo, hash_ = comprovantes.guardar(novo_comprovante)
        mudancas["comprovante"] = {"antes": atual.comprovante_arquivo, "depois": arquivo}
    if not mudancas:
        return atual

    with con:
        con.execute(
            "UPDATE recibo SET colaborador_id = ?, equipe = ?, valor_centavos = ?, descricao = ?,"
            " data_despesa = ?, comprovante_arquivo = ?,"
            " comprovante_hash = COALESCE(?, comprovante_hash) WHERE id = ?",
            (v["colaborador_id"], v["equipe"], v["valor_centavos"], v["descricao"],
             v["data_despesa"], arquivo, hash_, recibo_id),
        )
        registrar_historico(con, "recibo", recibo_id, "ALTERADO", mudancas,
                            usuario.id, usuario.perfil)
    return buscar(con, recibo_id)


def pode_cancelar(r: Recibo) -> tuple[bool, str]:
    """Diz se o recibo pode ser cancelado/corrigido e, se não, por quê (para mostrar na tela)."""
    if r.status == "RASCUNHO":
        return True, ""
    if r.status == "ENVIADO":
        return True, "O gestor será avisado do cancelamento na próxima planilha que você exportar."
    if r.status == "CANCELADO":
        return False, "Este recibo já está cancelado."
    if r.status in ("PARCIAL", "PAGO"):
        return False, ("O gestor já registrou pagamento para este recibo. "
                       "Para corrigir, combine com o gestor (ele pode estornar o pagamento).")
    return False, "Este recibo não pode ser alterado aqui."


def cancelar(con: sqlite3.Connection, usuario: Usuario, recibo_id: str, motivo: str) -> None:
    """Cancela com motivo. Nada é apagado (RN-12).

    Rascunho: cancela direto. Enviado: cancela aqui e o aviso vai ao gestor na próxima
    planilha exportada (ele tira o recibo da fila, se ainda não tiver pago).
    """
    _exigir_supervisor(usuario)
    atual = buscar(con, recibo_id)
    if not atual:
        raise ValueError("Recibo não encontrado.")
    permitido, motivo_bloqueio = pode_cancelar(atual)
    if not permitido:
        raise ValueError(motivo_bloqueio)
    motivo = " ".join((motivo or "").split())
    if len(motivo) < 3:
        raise ValueError("Informe o motivo do cancelamento.")
    with con:
        con.execute("UPDATE recibo SET status = 'CANCELADO', cancelamento_motivo = ? WHERE id = ?",
                    (motivo, recibo_id))
        registrar_historico(con, "recibo", recibo_id, "CANCELADO",
                            {"motivo": motivo, "avisar_gestor": atual.status == "ENVIADO"},
                            usuario.id, usuario.perfil)


def corrigir(
    con: sqlite3.Connection,
    usuario: Usuario,
    recibo_id: str,
    dados: dict,
    novo_comprovante: str | None = None,
) -> Recibo:
    """Corrige um recibo. Rascunho: edita direto. Enviado: cancela o original (o gestor é
    avisado) e cria um novo rascunho com os dados corrigidos, para enviar de novo."""
    atual = buscar(con, recibo_id)
    if not atual:
        raise ValueError("Recibo não encontrado.")
    if atual.status == "RASCUNHO":
        return atualizar(con, usuario, recibo_id, dados, novo_comprovante)
    permitido, motivo_bloqueio = pode_cancelar(atual)
    if not permitido:
        raise ValueError(motivo_bloqueio)
    validar(con, dados)
    comprovante = novo_comprovante or str(comprovantes.caminho_absoluto(atual.comprovante_arquivo))
    novo = criar(con, usuario, dados, comprovante)
    cancelar(con, usuario, recibo_id, f"Corrigido (substituído por novo recibo de "
                                      f"{novo.data_despesa[8:10]}/{novo.data_despesa[5:7]})")
    registrar_historico(con, "recibo", novo.id, "CORRECAO_DE", {"original": recibo_id},
                        usuario.id, usuario.perfil)
    con.commit()
    return novo


def cancelamentos_a_avisar(con: sqlite3.Connection, supervisor_id: str) -> list[Recibo]:
    """Recibos que já tinham sido enviados e foram cancelados, com aviso ainda não enviado."""
    return [r for r in listar(con, status="CANCELADO", supervisor_id=supervisor_id)
            if r.lote_id and not con.execute(
                "SELECT cancelamento_enviado_lote FROM recibo WHERE id = ?", (r.id,)).fetchone()[0]]


def historico(con: sqlite3.Connection, recibo_id: str) -> list[sqlite3.Row]:
    return con.execute(
        "SELECT h.acao, h.detalhes, h.data_hora, u.nome AS usuario"
        " FROM historico h LEFT JOIN usuario u ON u.id = h.usuario_id"
        " WHERE h.entidade = 'recibo' AND h.entidade_id = ? ORDER BY h.id",
        (recibo_id,),
    ).fetchall()
