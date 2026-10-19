"""Registro de pagamentos Pix pelo gestor (seção 4.4, RN-03 a RN-07, RN-10, RN-14, RN-15).

Um Pix (transferência) pode quitar um ou vários recibos do mesmo colaborador.
O valor de cada recibo coberto pelo Pix fica numa "aplicação".
"""

import sqlite3
from dataclasses import dataclass
from datetime import date

from recipay import colaboradores, comprovantes, recibos
from recipay.auth import Usuario
from recipay.db import agora, novo_id, registrar_historico
from recipay.pix import TIPOS_CHAVE, normalizar_chave

SITUACOES_ABERTAS = ("PENDENTE", "PARCIAL")


@dataclass
class ColaboradorPendente:
    id: str
    nome: str
    equipe: str
    supervisor_nome: str
    qtd_recibos: int
    saldo_centavos: int


@dataclass
class Transferencia:
    id: str
    colaborador_id: str
    colaborador_nome: str
    valor_centavos: int
    data_pagamento: str
    chave_pix_usada: str
    tipo_chave_usada: str
    titular_usado: str
    comprovante_arquivo: str
    estornado: bool
    estorno_motivo: str | None
    registrado_em: str
    qtd_recibos: int


def _exigir_gestor(usuario: Usuario) -> None:
    if usuario.perfil != "GESTOR":
        raise PermissionError("Somente o gestor registra pagamentos.")


def colaboradores_com_pendencia(
    con: sqlite3.Connection, busca: str = "", supervisor_id: str | None = None
) -> list[ColaboradorPendente]:
    sql = """
        SELECT c.id, c.nome, c.equipe, u.nome AS supervisor_nome,
               COUNT(r.id) AS qtd, SUM(s.saldo_centavos) AS saldo
        FROM recibo r
        JOIN colaborador c ON c.id = r.colaborador_id
        JOIN recibo_saldo s ON s.recibo_id = r.id
        LEFT JOIN usuario u ON u.id = c.supervisor_id
        WHERE r.status IN ('PENDENTE', 'PARCIAL')
    """
    params: list = []
    if busca.strip():
        sql += " AND c.nome LIKE ?"
        params.append(f"%{busca.strip()}%")
    if supervisor_id:
        sql += " AND r.supervisor_id = ?"
        params.append(supervisor_id)
    sql += " GROUP BY c.id ORDER BY c.nome COLLATE NOCASE"
    return [ColaboradorPendente(l["id"], l["nome"], l["equipe"], l["supervisor_nome"] or "",
                                l["qtd"], l["saldo"]) for l in con.execute(sql, params)]


def recibos_em_aberto(con: sqlite3.Connection, colaborador_id: str) -> list[recibos.Recibo]:
    """Recibos com saldo, do mais antigo para o mais novo."""
    lista = [r for r in recibos.listar(con, status=SITUACOES_ABERTAS)
             if r.colaborador_id == colaborador_id]
    return sorted(lista, key=lambda r: (r.data_despesa, r.criado_em))


def distribuir(saldos: list[tuple[str, int]], valor_centavos: int) -> dict[str, int]:
    """Distribui o valor do Pix do recibo mais antigo para o mais novo.

    saldos: [(recibo_id, saldo)] já na ordem desejada. O que sobrar não é aplicado.
    """
    restante = valor_centavos
    resultado = {}
    for recibo_id, saldo in saldos:
        parte = min(saldo, max(restante, 0))
        resultado[recibo_id] = parte
        restante -= parte
    return resultado


def _atualizar_situacao(con: sqlite3.Connection, recibo_id: str) -> str:
    l = con.execute("SELECT valor_centavos, saldo_centavos FROM recibo_saldo WHERE recibo_id = ?",
                    (recibo_id,)).fetchone()
    if l["saldo_centavos"] <= 0:
        nova = "PAGO"
    elif l["saldo_centavos"] < l["valor_centavos"]:
        nova = "PARCIAL"
    else:
        nova = "PENDENTE"
    con.execute("UPDATE recibo SET status = ? WHERE id = ?", (nova, recibo_id))
    return nova


def registrar(
    con: sqlite3.Connection,
    usuario: Usuario,
    colaborador_id: str,
    aplicacoes: dict[str, int],
    data_pagamento: str,
    comprovante_origem: str | None,
    outra_chave: dict | None = None,
) -> str:
    """Registra um Pix já feito no banco. Devolve o ID da transferência.

    aplicacoes: {recibo_id: centavos abatidos}. A soma é o valor do Pix (RN-14).
    outra_chave: {"tipo_chave", "chave_pix", "titular_pix"} usada só neste pagamento (RN-10).
    """
    _exigir_gestor(usuario)
    colab = colaboradores.buscar(con, colaborador_id)
    if not colab:
        raise ValueError("Colaborador não encontrado.")

    aplicacoes = {rid: v for rid, v in aplicacoes.items() if v}
    if not aplicacoes:
        raise ValueError("Informe o valor pago em pelo menos um recibo.")
    for rid, valor in aplicacoes.items():
        r = recibos.buscar(con, rid)
        if not r or r.colaborador_id != colaborador_id:
            raise ValueError("Todos os recibos do pagamento precisam ser do mesmo colaborador.")
        if r.status not in SITUACOES_ABERTAS:
            raise ValueError(f"O recibo \"{r.descricao}\" não está em aberto.")
        if valor < 0:
            raise ValueError("Valor negativo não é permitido.")
        if valor > r.saldo_centavos:
            raise ValueError(f"O valor para \"{r.descricao}\" é maior que o saldo do recibo (RN-06).")
    total = sum(aplicacoes.values())

    try:
        data = date.fromisoformat(data_pagamento)
    except ValueError as e:
        raise ValueError("Data do pagamento inválida.") from e
    if data > date.today():
        raise ValueError("A data do pagamento não pode ser no futuro.")

    if outra_chave:
        tipo = outra_chave.get("tipo_chave") or ""
        if tipo not in TIPOS_CHAVE:
            raise ValueError("Escolha o tipo da outra chave Pix.")
        chave = normalizar_chave(tipo, outra_chave.get("chave_pix") or "")
        titular = " ".join((outra_chave.get("titular_pix") or "").split())
        if not titular:
            raise ValueError("Informe o titular da outra chave Pix.")
    else:
        tipo, chave, titular = colab.tipo_chave, colab.chave_pix, colab.titular_pix

    if not comprovante_origem:
        raise ValueError("Anexe o comprovante bancário do Pix.")
    arquivo, hash_ = comprovantes.guardar(comprovante_origem)

    # Evita lançar o mesmo Pix duas vezes.
    repetido = con.execute(
        "SELECT t.data_pagamento, c.nome FROM transferencia t JOIN colaborador c ON c.id = t.colaborador_id"
        " WHERE t.comprovante_hash = ? AND t.estornado = 0", (hash_,)).fetchone()
    if repetido:
        comprovantes.caminho_absoluto(arquivo).unlink(missing_ok=True)
        raise ValueError(f"Este comprovante já foi usado no pagamento de {repetido['nome']} "
                         f"em {date.fromisoformat(repetido['data_pagamento']).strftime('%d/%m/%Y')}.")

    transf_id = novo_id()
    with con:
        con.execute(
            "INSERT INTO transferencia (id, colaborador_id, valor_centavos, data_pagamento,"
            " chave_pix_usada, tipo_chave_usada, titular_usado, comprovante_arquivo, comprovante_hash,"
            " registrado_por, registrado_em) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (transf_id, colaborador_id, total, data.isoformat(), chave, tipo, titular,
             arquivo, hash_, usuario.id, agora()))
        for rid, valor in aplicacoes.items():
            con.execute("INSERT INTO aplicacao (id, transferencia_id, recibo_id, valor_centavos)"
                        " VALUES (?, ?, ?, ?)", (novo_id(), transf_id, rid, valor))
            situacao = _atualizar_situacao(con, rid)
            registrar_historico(con, "recibo", rid, "PAGAMENTO",
                                {"transferencia_id": transf_id, "valor_centavos": valor,
                                 "situacao": situacao}, usuario.id, usuario.perfil)
        registrar_historico(
            con, "transferencia", transf_id, "REGISTRADA",
            {"valor_centavos": total, "recibos": list(aplicacoes), "outra_chave": bool(outra_chave)},
            usuario.id, usuario.perfil)
    return transf_id


def estornar(con: sqlite3.Connection, usuario: Usuario, transferencia_id: str, motivo: str) -> None:
    """Desfaz um pagamento lançado errado: o Pix inteiro, com todos os recibos (RN-15)."""
    _exigir_gestor(usuario)
    t = con.execute("SELECT * FROM transferencia WHERE id = ?", (transferencia_id,)).fetchone()
    if not t:
        raise ValueError("Pagamento não encontrado.")
    if t["estornado"]:
        raise ValueError("Este pagamento já foi estornado.")
    enviado = con.execute("SELECT 1 FROM retorno_envio WHERE transferencia_id = ?",
                          (transferencia_id,)).fetchone()
    if enviado:
        raise ValueError("Este pagamento já foi enviado ao supervisor no retorno e não pode ser estornado.")
    motivo = " ".join((motivo or "").split())
    if len(motivo) < 3:
        raise ValueError("Informe o motivo do estorno.")
    with con:
        con.execute("UPDATE transferencia SET estornado = 1, estorno_motivo = ? WHERE id = ?",
                    (motivo, transferencia_id))
        for (rid,) in con.execute("SELECT recibo_id FROM aplicacao WHERE transferencia_id = ?",
                                  (transferencia_id,)).fetchall():
            _atualizar_situacao(con, rid)
            registrar_historico(con, "recibo", rid, "PAGAMENTO_ESTORNADO",
                                {"transferencia_id": transferencia_id, "motivo": motivo},
                                usuario.id, usuario.perfil)
        registrar_historico(con, "transferencia", transferencia_id, "ESTORNADA",
                            {"motivo": motivo}, usuario.id, usuario.perfil)


def listar_transferencias(
    con: sqlite3.Connection, busca: str = "", incluir_estornadas: bool = True
) -> list[Transferencia]:
    sql = """
        SELECT t.*, c.nome AS colaborador_nome,
               (SELECT COUNT(*) FROM aplicacao a WHERE a.transferencia_id = t.id) AS qtd
        FROM transferencia t JOIN colaborador c ON c.id = t.colaborador_id WHERE 1 = 1
    """
    params: list = []
    if busca.strip():
        sql += " AND c.nome LIKE ?"
        params.append(f"%{busca.strip()}%")
    if not incluir_estornadas:
        sql += " AND t.estornado = 0"
    sql += " ORDER BY t.data_pagamento DESC, t.registrado_em DESC"
    return [Transferencia(
        l["id"], l["colaborador_id"], l["colaborador_nome"], l["valor_centavos"], l["data_pagamento"],
        l["chave_pix_usada"], l["tipo_chave_usada"], l["titular_usado"], l["comprovante_arquivo"],
        bool(l["estornado"]), l["estorno_motivo"], l["registrado_em"], l["qtd"],
    ) for l in con.execute(sql, params)]


def aplicacoes_da_transferencia(con: sqlite3.Connection, transferencia_id: str) -> list[sqlite3.Row]:
    return con.execute(
        "SELECT a.valor_centavos, r.descricao, r.data_despesa, r.valor_centavos AS valor_recibo"
        " FROM aplicacao a JOIN recibo r ON r.id = a.recibo_id WHERE a.transferencia_id = ?"
        " ORDER BY r.data_despesa", (transferencia_id,)).fetchall()
