"""Tela Início: resumo da situação e atalhos, por perfil."""

import sqlite3
from datetime import date

from PySide6.QtCore import Qt
from PySide6.QtWidgets import QFrame, QGridLayout, QHBoxLayout, QLabel, QPushButton, QVBoxLayout, QWidget

from recipay import backup, dinheiro
from recipay.auth import Usuario
from recipay.ui import tema


class Cartao(QFrame):
    """Indicador com rótulo, número grande e detalhe."""

    def __init__(self, rotulo: str, cor: str):
        super().__init__()
        self.setObjectName("cartao")
        self.setStyleSheet(
            f"QFrame#cartao {{ border: 1px solid {tema.BORDA}; border-left: 4px solid {cor};"
            f" border-radius: 8px; background: #FFFFFF; }}"
            "QFrame#cartao QLabel { background: transparent; border: none; }")
        layout = QVBoxLayout(self)
        layout.setContentsMargins(16, 12, 16, 12)
        titulo = QLabel(rotulo)
        titulo.setObjectName("subtitulo")
        self.valor = QLabel("—")
        self.valor.setStyleSheet(f"font-size: 20pt; font-weight: 600; color: {tema.TEXTO};")
        self.detalhe = QLabel("")
        self.detalhe.setObjectName("subtitulo")
        layout.addWidget(titulo)
        layout.addWidget(self.valor)
        layout.addWidget(self.detalhe)

    def definir(self, valor: str, detalhe: str = "") -> None:
        self.valor.setText(valor)
        self.detalhe.setText(detalhe)


def _um(con: sqlite3.Connection, sql: str, params: tuple = ()) -> tuple:
    return tuple(con.execute(sql, params).fetchone())


class TelaInicio(QWidget):
    def __init__(self, con: sqlite3.Connection, usuario: Usuario):
        super().__init__()
        self.con, self.usuario = con, usuario
        self.ir_para = None  # definido pela janela principal: ir_para("Título da tela")
        self.gestor = usuario.perfil == "GESTOR"

        layout = QVBoxLayout(self)
        layout.setContentsMargins(32, 24, 32, 24)
        ola = QLabel(f"Olá, {usuario.nome.split()[0]}")
        ola.setObjectName("titulo")
        layout.addWidget(ola)
        self.data = QLabel("")
        self.data.setObjectName("subtitulo")
        layout.addWidget(self.data)

        self.aviso_backup = QPushButton("")
        self.aviso_backup.setStyleSheet(
            f"QPushButton {{ background: #FFF4E0; color: {tema.TEXTO}; border: 1px solid {tema.LARANJA};"
            " text-align: left; padding: 10px 14px; font-weight: normal; }")
        self.aviso_backup.clicked.connect(lambda: self._ir("Backup"))
        layout.addWidget(self.aviso_backup)
        layout.addSpacing(8)

        grade = QGridLayout()
        grade.setSpacing(16)
        if self.gestor:
            self.c1 = Cartao("A pagar", tema.LARANJA)
            self.c2 = Cartao("Pago neste mês", tema.VERDE)
            self.c3 = Cartao("Retorno a enviar", tema.AZUL)
            self.c4 = Cartao("Supervisores", tema.TEXTO_SUAVE)
        else:
            self.c1 = Cartao("Rascunhos a enviar", tema.TEXTO_SUAVE)
            self.c2 = Cartao("Aguardando pagamento", tema.LARANJA)
            self.c3 = Cartao("Pago neste mês", tema.VERDE)
            self.c4 = Cartao("Colaboradores ativos", tema.AZUL)
        for i, c in enumerate((self.c1, self.c2, self.c3, self.c4)):
            grade.addWidget(c, 0, i)
        layout.addLayout(grade)
        layout.addSpacing(16)

        rotulo = QLabel("<b>O que fazer agora</b>")
        layout.addWidget(rotulo)
        atalhos = QHBoxLayout()
        opcoes = (
            [("Importar arquivo do supervisor", "Importar arquivo"),
             ("Realizar pagamento", "Realizar pagamento"),
             ("Exportar retorno", "Pagamentos concluídos")]
            if self.gestor else
            [("Cadastrar recibos", "Cadastrar recibos"),
             ("Exportar para o gestor", "Exportar recibos"),
             ("Importar retorno do gestor", "Retorno e histórico")])
        for i, (texto, destino) in enumerate(opcoes):
            b = QPushButton(texto)
            if i:
                b.setObjectName("secundario")
            b.clicked.connect(lambda _=False, d=destino: self._ir(d))
            atalhos.addWidget(b)
        atalhos.addStretch()
        layout.addLayout(atalhos)
        layout.addStretch()
        self.atualizar()

    def _ir(self, titulo: str) -> None:
        if self.ir_para:
            self.ir_para(titulo)

    def atualizar(self) -> None:
        con = self.con
        hoje = date.today()
        self.data.setText(hoje.strftime("%d/%m/%Y"))
        inicio_mes = hoje.replace(day=1).isoformat()
        pago_mes = _um(con, "SELECT COUNT(*), COALESCE(SUM(valor_centavos), 0) FROM transferencia"
                            " WHERE estornado = 0 AND data_pagamento >= ?", (inicio_mes,))
        if self.gestor:
            n, v = _um(con, "SELECT COUNT(*), COALESCE(SUM(s.saldo_centavos), 0) FROM recibo r"
                            " JOIN recibo_saldo s ON s.recibo_id = r.id WHERE r.status IN ('PENDENTE','PARCIAL')")
            colabs = _um(con, "SELECT COUNT(DISTINCT colaborador_id) FROM recibo"
                              " WHERE status IN ('PENDENTE','PARCIAL')")[0]
            self.c1.definir(dinheiro.formatar(v), f"{n} recibo(s) de {colabs} colaborador(es)")
            self.c2.definir(dinheiro.formatar(pago_mes[1]), f"{pago_mes[0]} Pix registrado(s)")
            r = _um(con, "SELECT COUNT(*), COALESCE(SUM(t.valor_centavos), 0) FROM transferencia t"
                         " WHERE t.estornado = 0 AND NOT EXISTS (SELECT 1 FROM retorno_envio e"
                         " WHERE e.transferencia_id = t.id)")
            self.c3.definir(str(r[0]), f"pagamento(s) · {dinheiro.formatar(r[1])}")
            sups = _um(con, "SELECT COUNT(*) FROM usuario WHERE perfil = 'SUPERVISOR'")[0]
            self.c4.definir(str(sups), "enviaram arquivos")
        else:
            n, v = _um(con, "SELECT COUNT(*), COALESCE(SUM(valor_centavos), 0) FROM recibo WHERE status = 'RASCUNHO'")
            self.c1.definir(str(n), dinheiro.formatar(v))
            n, v = _um(con, "SELECT COUNT(*), COALESCE(SUM(s.saldo_centavos), 0) FROM recibo r"
                            " JOIN recibo_saldo s ON s.recibo_id = r.id WHERE r.status IN ('ENVIADO','PARCIAL')")
            self.c2.definir(dinheiro.formatar(v), f"{n} recibo(s) com o gestor")
            self.c3.definir(dinheiro.formatar(pago_mes[1]), f"{pago_mes[0]} Pix recebido(s)")
            ativos = _um(con, "SELECT COUNT(*) FROM colaborador WHERE ativo = 1")[0]
            self.c4.definir(str(ativos), "cadastrados")

        if backup.precisa_lembrar(con):
            ultimo = backup.ultimo_backup(con)
            quando = f"O último backup foi em {ultimo:%d/%m/%Y}." if ultimo else "Você ainda não fez nenhum backup."
            self.aviso_backup.setText(f"⚠  {quando} Clique aqui para fazer um backup e não perder os dados.")
            self.aviso_backup.setVisible(True)
        else:
            self.aviso_backup.setVisible(False)
        self.aviso_backup.setCursor(Qt.CursorShape.PointingHandCursor)
