"""Retorno dos pagamentos: exportar (Gestor) e importar + histórico (Supervisor)."""

import sqlite3
from datetime import date
from pathlib import Path

from PySide6.QtCore import Qt, QUrl
from PySide6.QtGui import QColor, QDesktopServices
from PySide6.QtWidgets import (
    QAbstractItemView,
    QCheckBox,
    QComboBox,
    QDialog,
    QFileDialog,
    QFrame,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QMessageBox,
    QPushButton,
    QTableWidget,
    QTableWidgetItem,
    QVBoxLayout,
    QWidget,
)

from recipay import comprovantes, dinheiro, pagamentos, retorno
from recipay.auth import Usuario
from recipay.ui import tema
from recipay.ui.componentes import abrir_arquivo
from recipay.ui.tela_pagamento import DialogoDetalhePagamento


def _rotulo(texto: str, nome: str = "") -> QLabel:
    r = QLabel(texto)
    if nome:
        r.setObjectName(nome)
    r.setWordWrap(True)
    return r


def _data_br(iso: str) -> str:
    return date.fromisoformat(iso[:10]).strftime("%d/%m/%Y")


def _item(texto: str, direita: bool = False, cor: str | None = None) -> QTableWidgetItem:
    item = QTableWidgetItem(texto)
    item.setFlags(item.flags() & ~Qt.ItemFlag.ItemIsEditable)
    if direita:
        item.setTextAlignment(int(Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter))
    if cor:
        item.setForeground(QColor(cor))
    return item


def _tabela(colunas: list[str]) -> QTableWidget:
    t = QTableWidget(0, len(colunas))
    t.setHorizontalHeaderLabels(colunas)
    t.verticalHeader().setVisible(False)
    t.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
    t.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection)
    t.setAlternatingRowColors(True)
    t.horizontalHeader().setStretchLastSection(True)
    return t


# ================================================================ Gestor
class DialogoExportarRetorno(QDialog):
    def __init__(self, con: sqlite3.Connection, usuario: Usuario):
        super().__init__()
        self.con, self.usuario = con, usuario
        self.setWindowTitle("Exportar retorno para o supervisor")
        self.setMinimumWidth(520)
        layout = QVBoxLayout(self)
        layout.setContentsMargins(24, 20, 24, 20)
        layout.addWidget(_rotulo(
            "Gera a planilha com os pagamentos de um supervisor para ele atualizar os recibos. "
            "Cada supervisor recebe só os pagamentos dos recibos dele.", "subtitulo"))
        self.supervisor = QComboBox()
        layout.addWidget(self.supervisor)
        self.reenviar = QCheckBox("Incluir pagamentos já enviados (reenviar, se o arquivo se perdeu)")
        layout.addWidget(self.reenviar)
        self.erro = _rotulo("", "erro")
        layout.addWidget(self.erro)
        botoes = QHBoxLayout()
        botoes.addStretch()
        cancelar = QPushButton("Cancelar")
        cancelar.setObjectName("secundario")
        cancelar.clicked.connect(self.reject)
        self.bt_exportar = QPushButton("Exportar planilha…")
        self.bt_exportar.clicked.connect(self._exportar)
        botoes.addWidget(cancelar)
        botoes.addWidget(self.bt_exportar)
        layout.addLayout(botoes)

        for s in retorno.supervisores_para_retorno(con):
            texto = (f"{s.nome} — {s.pendentes} pagamento(s) novo(s), {dinheiro.formatar(s.total_centavos)}"
                     if s.pendentes else f"{s.nome} — nada novo para enviar")
            self.supervisor.addItem(texto, s.id)
        if self.supervisor.count() == 0:
            self.supervisor.addItem("Nenhum pagamento registrado ainda", None)
            self.bt_exportar.setEnabled(False)

    def _exportar(self) -> None:
        sup_id = self.supervisor.currentData()
        nome = self.supervisor.currentText().split(" — ")[0]
        caminho, _ = QFileDialog.getSaveFileName(
            self, "Salvar retorno", str(Path.home() / "Documents" / retorno.nome_sugerido(nome)),
            "Planilhas do Excel (*.xlsx)")
        if not caminho:
            return
        try:
            res = retorno.exportar_retorno(self.con, self.usuario, sup_id, caminho,
                                           incluir_enviados=self.reenviar.isChecked())
        except (ValueError, PermissionError, OSError) as e:
            self.erro.setText(str(e))
            return
        caixa = QMessageBox(self)
        caixa.setWindowTitle("Retorno gerado")
        caixa.setText(f"✓ {res.quantidade} pagamento(s), total {dinheiro.formatar(res.total_centavos)}.\n\n"
                      f"Arquivo: {caminho}\n\nAgora envie este arquivo para {nome}.")
        abrir = caixa.addButton("Abrir pasta", QMessageBox.ButtonRole.ActionRole)
        caixa.addButton("OK", QMessageBox.ButtonRole.AcceptRole)
        caixa.exec()
        if caixa.clickedButton() == abrir:
            QDesktopServices.openUrl(QUrl.fromLocalFile(str(Path(caminho).parent)))
        self.accept()


# ================================================================ Supervisor
class TelaRetornoHistorico(QWidget):
    def __init__(self, con: sqlite3.Connection, usuario: Usuario):
        super().__init__()
        self.con, self.usuario = con, usuario
        self.previa: retorno.PreviaRetorno | None = None
        self.itens: list[pagamentos.Transferencia] = []

        layout = QVBoxLayout(self)
        layout.setContentsMargins(32, 24, 32, 24)
        layout.addWidget(_rotulo("Retorno e histórico", "titulo"))
        layout.addWidget(_rotulo(
            "Importe a planilha de retorno enviada pelo gestor para atualizar os recibos pagos.",
            "subtitulo"))
        topo = QHBoxLayout()
        bt_importar = QPushButton("Importar retorno do gestor…")
        bt_importar.clicked.connect(self._escolher)
        topo.addWidget(bt_importar)
        topo.addStretch()
        layout.addLayout(topo)

        # Prévia
        self.painel = QFrame()
        p = QVBoxLayout(self.painel)
        p.setContentsMargins(0, 8, 0, 8)
        self.cabecalho = _rotulo("")
        p.addWidget(self.cabecalho)
        self.avisos = _rotulo("")
        self.avisos.setStyleSheet(f"color: {tema.LARANJA}")
        p.addWidget(self.avisos)
        self.tabela_previa = _tabela(["Ref Pix", "Data", "Colaborador", "Valor do Pix", "Recibos", "Situação"])
        self.tabela_previa.setMaximumHeight(180)
        p.addWidget(self.tabela_previa)
        botoes = QHBoxLayout()
        botoes.addStretch()
        cancelar = QPushButton("Cancelar")
        cancelar.setObjectName("secundario")
        cancelar.clicked.connect(self._fechar_previa)
        self.bt_confirmar = QPushButton("Confirmar")
        self.bt_confirmar.clicked.connect(self._confirmar)
        botoes.addWidget(cancelar)
        botoes.addWidget(self.bt_confirmar)
        p.addLayout(botoes)
        layout.addWidget(self.painel)

        # Histórico de pagamentos recebidos
        layout.addWidget(_rotulo("<b>Pagamentos recebidos</b>"))
        filtros = QHBoxLayout()
        self.busca = QLineEdit()
        self.busca.setPlaceholderText("Buscar por colaborador…")
        self.busca.setClearButtonEnabled(True)
        self.bt_detalhe = QPushButton("Ver detalhes")
        self.bt_comprov = QPushButton("Ver comprovante bancário")
        for b in (self.bt_detalhe, self.bt_comprov):
            b.setObjectName("secundario")
        filtros.addWidget(self.busca, 1)
        filtros.addWidget(self.bt_detalhe)
        filtros.addWidget(self.bt_comprov)
        layout.addLayout(filtros)
        self.tabela = _tabela(["Data do Pix", "Colaborador", "Valor", "Recibos", "Situação"])
        layout.addWidget(self.tabela, 1)
        self.resumo = _rotulo("", "subtitulo")
        layout.addWidget(self.resumo)

        self.busca.textChanged.connect(self.atualizar)
        self.tabela.doubleClicked.connect(self._detalhe)
        self.bt_detalhe.clicked.connect(self._detalhe)
        self.bt_comprov.clicked.connect(self._comprovante)
        self._fechar_previa()

    # ---------- histórico
    def atualizar(self) -> None:
        self.itens = pagamentos.listar_transferencias(self.con, self.busca.text())
        self.tabela.setRowCount(0)
        for i, t in enumerate(self.itens):
            self.tabela.insertRow(i)
            for j, (v, direita) in enumerate((
                    (_data_br(t.data_pagamento), False), (t.colaborador_nome, False),
                    (dinheiro.formatar(t.valor_centavos), True), (str(t.qtd_recibos), True),
                    ("Pago", False))):
                self.tabela.setItem(i, j, _item(v, direita, tema.VERDE if j == 4 else None))
        self.tabela.resizeColumnsToContents()
        pendentes = self.con.execute(
            "SELECT COUNT(*), COALESCE(SUM(s.saldo_centavos), 0) FROM recibo r"
            " JOIN recibo_saldo s ON s.recibo_id = r.id WHERE r.status IN ('ENVIADO', 'PARCIAL')").fetchone()
        self.resumo.setText(
            f"{len(self.itens)} pagamento(s) recebido(s), total "
            f"{dinheiro.formatar(sum(t.valor_centavos for t in self.itens))} · "
            f"Aguardando pagamento: {pendentes[0]} recibo(s), {dinheiro.formatar(pendentes[1])}")

    def _selecionado(self) -> pagamentos.Transferencia | None:
        linhas = self.tabela.selectionModel().selectedRows()
        return self.itens[linhas[0].row()] if linhas else None

    def _detalhe(self, *_) -> None:
        t = self._selecionado()
        if t:
            DialogoDetalhePagamento(self.con, t).exec()

    def _comprovante(self) -> None:
        t = self._selecionado()
        if t:
            abrir_arquivo(comprovantes.caminho_absoluto(t.comprovante_arquivo), self)

    # ---------- importação
    def _fechar_previa(self) -> None:
        self.previa = None
        self.painel.setVisible(False)
        self.atualizar()

    def _escolher(self) -> None:
        caminho, _ = QFileDialog.getOpenFileName(
            self, "Planilha de retorno do gestor", str(Path.home()), "Planilhas do Excel (*.xlsx)")
        if not caminho:
            return
        try:
            self.previa = retorno.ler_retorno(self.con, self.usuario, caminho)
        except ValueError as e:
            QMessageBox.warning(self, "Não foi possível importar", str(e))
            return
        pv = self.previa
        d = pv.dados
        self.cabecalho.setText(
            f"<b>{pv.arquivo_nome}</b><br>Enviado por <b>{pv.gestor_nome}</b> em "
            f"{_data_br(d['gerado_em'])} {d['gerado_em'][11:16]} · {len(pv.itens)} pagamento(s) · "
            f"<b style='color:{tema.VERDE}'>{len(pv.novos)} novo(s), "
            f"{dinheiro.formatar(pv.total_novos_centavos)}</b>")
        self.avisos.setText("<br>".join(f"⚠ {a}" for a in pv.avisos))
        self.tabela_previa.setRowCount(0)
        for i, item in enumerate(pv.itens):
            t = item.dados
            self.tabela_previa.insertRow(i)
            if item.recibos_desconhecidos:
                situacao, cor = "Recibo desconhecido (ignorado)", tema.VERMELHO
            elif item.novo:
                situacao, cor = "Novo", tema.VERDE
            else:
                situacao, cor = "Já importado (ignorado)", tema.TEXTO_SUAVE
            for j, (v, direita) in enumerate((
                    (t["ref"], False), (_data_br(t["data_pagamento"]), False), (item.colaborador_nome, False),
                    (dinheiro.formatar(t["valor_centavos"]), True), (str(len(t["aplicacoes"])), True),
                    (situacao, False))):
                self.tabela_previa.setItem(i, j, _item(v, direita, cor if j == 5 else None))
        self.tabela_previa.resizeColumnsToContents()
        self.bt_confirmar.setText(f"Atualizar {len(pv.novos)} pagamento(s)" if pv.novos else "Nada novo")
        self.bt_confirmar.setEnabled(bool(pv.novos))
        self.painel.setVisible(True)

    def _confirmar(self) -> None:
        if not self.previa:
            return
        try:
            r = retorno.importar_retorno(self.con, self.usuario, self.previa)
        except (ValueError, PermissionError, OSError) as e:
            QMessageBox.warning(self, "Não foi possível importar", str(e))
            return
        QMessageBox.information(
            self, "Retorno importado",
            f"✓ {r.novos} pagamento(s) registrados, total {dinheiro.formatar(r.total_centavos)}.\n"
            f"{r.recibos_pagos} recibo(s) pago(s), {r.recibos_parciais} parcial(is).\n\n"
            "Veja a situação de cada recibo na tela Recibos.")
        self._fechar_previa()
