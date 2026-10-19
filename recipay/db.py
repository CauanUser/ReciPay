"""Banco de dados local (SQLite) do Recipay.

Regras seguidas (ver docs/ESPECIFICACAO.md):
- Valores sempre em centavos (inteiro).
- IDs de recibos, transferências etc. são UUIDs criados na origem (RN-08).
- Nada é apagado: correções viram novos registros e entram no histórico (RN-12).
"""

import json
import sqlite3
import uuid
from datetime import datetime
from pathlib import Path

VERSAO_SCHEMA = 1

SCHEMA_V1 = """
CREATE TABLE usuario (
    id          TEXT PRIMARY KEY,
    nome        TEXT NOT NULL,
    perfil      TEXT NOT NULL CHECK (perfil IN ('SUPERVISOR', 'GESTOR')),
    senha_hash  TEXT,               -- só existe para o usuário deste computador
    local       INTEGER NOT NULL DEFAULT 0 CHECK (local IN (0, 1)),
    criado_em   TEXT NOT NULL
);
-- Só pode existir um usuário local por computador.
CREATE UNIQUE INDEX ux_usuario_local ON usuario(local) WHERE local = 1;

CREATE TABLE colaborador (
    id             TEXT PRIMARY KEY,
    supervisor_id  TEXT NOT NULL REFERENCES usuario(id),
    nome           TEXT NOT NULL,
    equipe         TEXT NOT NULL,
    chave_pix      TEXT NOT NULL,
    tipo_chave     TEXT NOT NULL
                   CHECK (tipo_chave IN ('CPF', 'CNPJ', 'EMAIL', 'TELEFONE', 'ALEATORIA')),
    titular_pix    TEXT NOT NULL,
    ativo          INTEGER NOT NULL DEFAULT 1 CHECK (ativo IN (0, 1)),
    criado_em      TEXT NOT NULL,
    atualizado_em  TEXT NOT NULL
);

CREATE TABLE lote (
    id              TEXT PRIMARY KEY,
    tipo            TEXT NOT NULL CHECK (tipo IN ('RECIBOS', 'RETORNO')),
    gerado_por      TEXT NOT NULL REFERENCES usuario(id),
    gerado_em       TEXT NOT NULL,
    hash_conteudo   TEXT NOT NULL,
    qtd_itens       INTEGER NOT NULL,
    total_centavos  INTEGER NOT NULL
);

CREATE TABLE recibo (
    id                   TEXT PRIMARY KEY,
    colaborador_id       TEXT NOT NULL REFERENCES colaborador(id),
    supervisor_id        TEXT NOT NULL REFERENCES usuario(id),
    equipe               TEXT NOT NULL,
    valor_centavos       INTEGER NOT NULL CHECK (valor_centavos > 0),
    descricao            TEXT NOT NULL,
    data_despesa         TEXT NOT NULL,
    comprovante_arquivo  TEXT NOT NULL,      -- RN-01: obrigatório
    comprovante_hash     TEXT NOT NULL,
    status               TEXT NOT NULL DEFAULT 'RASCUNHO'
                         CHECK (status IN ('RASCUNHO', 'ENVIADO', 'PENDENTE',
                                           'PARCIAL', 'PAGO', 'CANCELADO')),
    cancelamento_motivo  TEXT,
    lote_id              TEXT REFERENCES lote(id),
    criado_em            TEXT NOT NULL
);

CREATE TABLE transferencia (
    id                   TEXT PRIMARY KEY,
    colaborador_id       TEXT NOT NULL REFERENCES colaborador(id),
    valor_centavos       INTEGER NOT NULL CHECK (valor_centavos > 0),
    data_pagamento       TEXT NOT NULL,
    chave_pix_usada      TEXT NOT NULL,
    tipo_chave_usada     TEXT NOT NULL,
    titular_usado        TEXT NOT NULL,
    comprovante_arquivo  TEXT NOT NULL,      -- comprovante bancário obrigatório
    comprovante_hash     TEXT NOT NULL,
    estornado            INTEGER NOT NULL DEFAULT 0 CHECK (estornado IN (0, 1)),
    estorno_motivo       TEXT,
    registrado_por       TEXT NOT NULL REFERENCES usuario(id),
    registrado_em        TEXT NOT NULL
);

-- Um Pix (transferência) pode quitar vários recibos do mesmo colaborador (D-03).
CREATE TABLE aplicacao (
    id                TEXT PRIMARY KEY,
    transferencia_id  TEXT NOT NULL REFERENCES transferencia(id),
    recibo_id         TEXT NOT NULL REFERENCES recibo(id),
    valor_centavos    INTEGER NOT NULL CHECK (valor_centavos > 0),
    UNIQUE (transferencia_id, recibo_id)
);

-- Controla quais transferências já foram exportadas no retorno de cada supervisor.
CREATE TABLE retorno_envio (
    transferencia_id  TEXT NOT NULL REFERENCES transferencia(id),
    supervisor_id     TEXT NOT NULL REFERENCES usuario(id),
    lote_id           TEXT NOT NULL REFERENCES lote(id),
    PRIMARY KEY (transferencia_id, supervisor_id)
);

CREATE TABLE importacao (
    id             TEXT PRIMARY KEY,
    lote_id        TEXT NOT NULL,
    arquivo_nome   TEXT NOT NULL,
    hash_arquivo   TEXT NOT NULL,
    importado_por  TEXT NOT NULL REFERENCES usuario(id),
    importado_em   TEXT NOT NULL,
    novos          INTEGER NOT NULL,
    ignorados      INTEGER NOT NULL
);

CREATE TABLE historico (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    entidade     TEXT NOT NULL,
    entidade_id  TEXT NOT NULL,
    acao         TEXT NOT NULL,
    detalhes     TEXT,              -- JSON
    usuario_id   TEXT,
    perfil       TEXT,
    data_hora    TEXT NOT NULL
);

CREATE TABLE configuracao (
    chave  TEXT PRIMARY KEY,
    valor  TEXT
);

-- Saldo sempre calculado, nunca digitado (RN-04).
CREATE VIEW recibo_saldo AS
SELECT r.id AS recibo_id,
       r.valor_centavos,
       COALESCE(SUM(CASE WHEN t.estornado = 0 THEN a.valor_centavos END), 0) AS pago_centavos,
       r.valor_centavos
         - COALESCE(SUM(CASE WHEN t.estornado = 0 THEN a.valor_centavos END), 0) AS saldo_centavos
FROM recibo r
LEFT JOIN aplicacao a     ON a.recibo_id = r.id
LEFT JOIN transferencia t ON t.id = a.transferencia_id
GROUP BY r.id;
"""


def agora() -> str:
    return datetime.now().isoformat(timespec="seconds")


def novo_id() -> str:
    return str(uuid.uuid4())


def conectar(caminho: Path | str) -> sqlite3.Connection:
    """Abre o banco e cria ou atualiza as tabelas quando necessário."""
    con = sqlite3.connect(str(caminho))
    con.row_factory = sqlite3.Row
    con.execute("PRAGMA foreign_keys = ON")
    con.execute("PRAGMA journal_mode = WAL")
    _migrar(con)
    return con


def _migrar(con: sqlite3.Connection) -> None:
    versao = con.execute("PRAGMA user_version").fetchone()[0]
    if versao < 1:
        with con:
            con.executescript(SCHEMA_V1)
            con.execute(f"PRAGMA user_version = {VERSAO_SCHEMA}")


def registrar_historico(
    con: sqlite3.Connection,
    entidade: str,
    entidade_id: str,
    acao: str,
    detalhes: dict | None = None,
    usuario_id: str | None = None,
    perfil: str | None = None,
) -> None:
    con.execute(
        "INSERT INTO historico (entidade, entidade_id, acao, detalhes, usuario_id, perfil, data_hora)"
        " VALUES (?, ?, ?, ?, ?, ?, ?)",
        (
            entidade,
            entidade_id,
            acao,
            json.dumps(detalhes, ensure_ascii=False) if detalhes else None,
            usuario_id,
            perfil,
            agora(),
        ),
    )
