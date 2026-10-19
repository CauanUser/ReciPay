"""Telas de troca de planilhas: Exportar recibos (Supervisor) e Importar arquivo (Gestor),
e a lista de Pagamentos pendentes do gestor."""

import sqlite3
from datetime import date
from pathlib import Path

from PySide6.QtCore import Qt, QUrl
from PySide6.QtGui import QColor, QDesktopServices
from PySide6.QtWidgets import (
    QAbstractItemView,
    QCheckBox,
    QComboBox,
    QFileDialog,
    QFrame,
    QHBoxLayout,
    QHeaderView,
    QLabel,
    QLineEdit,
    QMessageBox,
    QPushButton,
    QTableView,
    QTableWidget,
    QTableWidgetItem,
    QVBoxLayout,
    QWidget,
)

from recipay import comprovantes, dinheiro, recibos, troca
from recipay.auth import Usuario
from recipay.pix import formatar_chave
from recipay.ui import tema
from recipay.ui.componentes import abrir_arquivo
from recipay.ui.tela_recibos import ModeloRecibos


def _rotulo(texto: str, nome: str = "") -> QLabel:
    r = QLabel(texto)
    if nome:
        r.setObjectName(nome)
    r.setWordWrap(True)
    return r


def _data_br(iso: str) -> str:
    return date.fromisoformat(iso[:10]).strftime("%d/%m/%Y")


def _item(texto: str, cor: str | None = None, direita: bool = False) -> QTableWidgetItem:
    item = QTableWidgetItem(texto)
    item.setFlags(item.flags() & ~Qt.ItemFlag.ItemIsEditable)
    if cor:
        item.setForeground(QColor(cor))
    if direita:
        item.setTextAlignment(int(Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter))
    return item


def _tabela(colunas: list[str]) -> QTableWidget:
    t = QTableWidget(0, len(colunas))
    t.setHorizontalHeaderLabels(colunas)
    t.verticalHeader().setVisible(False)
    t.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
    t.setAlternatingRowColors(True)
    t.horizontalHeader().setStretchLastSection(True)
    return t


def _abrir_pasta(caminho: Path) -> None:
    QDesktopServices.openUrl(QUrl.fromLocalFile(str(caminho.parent)))


# ================================================================ Supervisor
class TelaExportarRecibos(QWidget):
    COLUNAS = ["", "Data", "Colaborador", "Equipe", "Descrição", "Valor", "Situação"]

    def __init__(self, con: sqlite3.Connection, usuario: Usuario):
        super().__init__()
        self.con, self.usuario = con, usuario
        self.itens: list[recibos.Recibo] = []

        layout = QVBoxLayout(self)
        layout.setContentsMargins(32, 24, 32, 24)
        layout.addWidget(_rotulo("Exportar recibos", "titulo"))
        layout.addWidget(_rotulo(
            "Marque os recibos e gere a planilha para enviar ao gestor (e-mail, Teams, WhatsApp Web…). "
            "Depois de exportados, os recibos não podem mais ser editados.", "subtitulo"))

        opcoes = QHBoxLayout()
        self.todos = QCheckBox("Marcar todos")
        self.reenviar = QCheckBox("Mostrar também os já enviados (para reenviar)")
        opcoes.addWidget(self.todos)
        opcoes.addSpacing(16)
        opcoes.addWidget(self.reenviar)
        opcoes.addStretch()
        layout.addLayout(opcoes)

        self.tabela = _tabela(self.COLUNAS)
        self.tabela.setColumnWidth(0, 36)
        layout.addWidget(self.tabela, 1)

        rodape = QHBoxLayout()
        self.resumo = _rotulo("", "subtitulo")
        rodape.addWidget(self.resumo, 1)
        self.bt_exportar = QPushButton("Exportar planilha…")
        self.bt_exportar.clicked.connect(self._exportar)
        rodape.addWidget(self.bt_exportar)
        layout.addLayout(rodape)

        self.todos.toggled.connect(self._marcar_todos)
        self.reenviar.toggled.connect(self.atualizar)
        self.tabela.itemChanged.connect(self._atualizar_resumo)
        self.atualizar()

    def atualizar(self) -> None:
        situacoes = ("RASCUNHO", "ENVIADO") if self.reenviar.isChecked() else ("RASCUNHO",)
        self.itens = recibos.listar(self.con, status=situacoes)
        self.itens.sort(key=lambda r: (r.status != "RASCUNHO", r.colaborador_nome.lower()))
        self.tabela.blockSignals(True)
        self.tabela.setRowCount(0)
        for r in self.itens:
            i = self.tabela.rowCount()
            self.tabela.insertRow(i)
            marca = QTableWidgetItem()
            marca.setFlags(Qt.ItemFlag.ItemIsUserCheckable | Qt.ItemFlag.ItemIsEnabled)
            marca.setCheckState(Qt.CheckState.Checked if r.status == "RASCUNHO" and self.todos.isChecked()
                                else Qt.CheckState.Unchecked)
            self.tabela.setItem(i, 0, marca)
            cor = tema.TEXTO_SUAVE if r.status == "RASCUNHO" else tema.AZUL
            for j, (texto, direita) in enumerate((
                (_data_br(r.data_despesa), False), (r.colaborador_nome, False), (r.equipe, False),
                (r.descricao, False), (dinheiro.formatar(r.valor_centavos), True),
                (recibos.STATUS[r.status], False)), start=1):
                self.tabela.setItem(i, j, _item(texto, cor if j == 6 else None, direita))
        self.tabela.resizeColumnsToContents()
        self.tabela.setColumnWidth(0, 36)
        self.tabela.blockSignals(False)
        self._atualizar_resumo()

    def _marcar_todos(self, marcado: bool) -> None:
        self.tabela.blockSignals(True)
        for i in range(self.tabela.rowCount()):
            self.tabela.item(i, 0).setCheckState(
                Qt.CheckState.Checked if marcado else Qt.CheckState.Unchecked)
        self.tabela.blockSignals(False)
        self._atualizar_resumo()

    def _marcados(self) -> list[recibos.Recibo]:
        return [r for i, r in enumerate(self.itens)
                if self.tabela.item(i, 0).checkState() == Qt.CheckState.Checked]

    def _atualizar_resumo(self, *_) -> None:
        marcados = self._marcados()
        total = sum(r.valor_centavos for r in marcados)
        rascunhos = sum(1 for r in self.itens if r.status == "RASCUNHO")
        self.resumo.setText(
            f"{len(marcados)} marcado(s) · Total {dinheiro.formatar(total)}   "
            f"({rascunhos} rascunho(s) aguardando envio)")
        self.bt_exportar.setEnabled(bool(marcados))

    def _exportar(self) -> None:
        marcados = self._marcados()
        if not marcados:
            return
        reenvios = sum(1 for r in marcados if r.status == "ENVIADO")
        if reenvios:
            resp = QMessageBox.question(
                self, "Reenviar recibos",
                f"{reenvios} recibo(s) marcado(s) já foram enviados antes. O gestor não vai importá-los "
                "em dobro, mas confirme que quer incluí-los nesta planilha.")
            if resp != QMessageBox.StandardButton.Yes:
                return
        caminho, _ = QFileDialog.getSaveFileName(
            self, "Salvar planilha para o gestor",
            str(Path.home() / "Documents" / troca.nome_sugerido(self.usuario)),
            "Planilhas do Excel (*.xlsx)")
        if not caminho:
            return
        try:
            res = troca.exportar_recibos(self.con, self.usuario, [r.id for r in marcados], caminho)
        except (ValueError, PermissionError, OSError) as e:
            QMessageBox.warning(self, "Não foi possível exportar", str(e))
            return
        tamanho = res.tamanho_bytes / (1024 * 1024)
        aviso = ("\n\nAtenção: o arquivo passou de 20 MB e pode não ir por e-mail. "
                 "Se precisar, exporte em partes menores.") if res.grande_demais else ""
        caixa = QMessageBox(self)
        caixa.setWindowTitle("Planilha gerada")
        caixa.setText(
            f"✓ {res.quantidade} recibo(s) exportado(s), total {dinheiro.formatar(res.total_centavos)}.\n\n"
            f"Arquivo: {caminho}\nTamanho: {tamanho:.1f} MB\n\n"
            f"Agora envie este arquivo ao gestor.{aviso}")
        abrir = caixa.addButton("Abrir pasta", QMessageBox.ButtonRole.ActionRole)
        caixa.addButton("OK", QMessageBox.ButtonRole.AcceptRole)
        caixa.exec()
        if caixa.clickedButton() == abrir:
            _abrir_pasta(Path(caminho))
        self.todos.setChecked(False)
        self.atualizar()


# ================================================================ Gestor
class TelaImportarArquivo(QWidget):
    def __init__(self, con: sqlite3.Connection, usuario: Usuario):
        super().__init__()
        self.con, self.usuario = con, usuario
        self.previa: troca.PreviaImportacao | None = None
        self.combos_chave: dict[str, QComboBox] = {}

        layout = QVBoxLayout(self)
        layout.setContentsMargins(32, 24, 32, 24)
        layout.addWidget(_rotulo("Importar arquivo", "titulo"))
        layout.addWidget(_rotulo(
            "Abra a planilha enviada pelo supervisor. Você confere a prévia antes de confirmar. "
            "Importar não significa que o recibo foi pago.", "subtitulo"))

        topo = QHBoxLayout()
        self.bt_escolher = QPushButton("Escolher planilha…")
        self.bt_escolher.clicked.connect(self._escolher)
        topo.addWidget(self.bt_escolher)
        topo.addStretch()
        layout.addLayout(topo)

        # Prévia
        self.painel = QFrame()
        p = QVBoxLayout(self.painel)
        p.setContentsMargins(0, 8, 0, 0)
        self.cabecalho = _rotulo("")
        p.addWidget(self.cabecalho)
        self.avisos = _rotulo("")
        p.addWidget(self.avisos)
        self.tabela = _tabela(["Ref", "Data", "Colaborador", "Equipe", "Descrição", "Valor", "Situação"])
        p.addWidget(self.tabela, 1)
        self.area_chaves = QVBoxLayout()
        p.addLayout(self.area_chaves)
        botoes = QHBoxLayout()
        botoes.addStretch()
        self.bt_cancelar = QPushButton("Cancelar")
        self.bt_cancelar.setObjectName("secundario")
        self.bt_cancelar.clicked.connect(self._limpar)
        self.bt_confirmar = QPushButton("Confirmar importação")
        self.bt_confirmar.clicked.connect(self._confirmar)
        botoes.addWidget(self.bt_cancelar)
        botoes.addWidget(self.bt_confirmar)
        p.addLayout(botoes)
        layout.addWidget(self.painel, 3)

        layout.addWidget(_rotulo("Importações anteriores", "subtitulo"))
        self.historico = _tabela(["Quando", "Supervisor", "Arquivo", "Novos", "Já existentes"])
        self.historico.setMaximumHeight(170)
        layout.addWidget(self.historico, 1)

        self._limpar()

    def atualizar(self) -> None:
        self.historico.setRowCount(0)
        for h in troca.historico_importacoes(self.con):
            i = self.historico.rowCount()
            self.historico.insertRow(i)
            for j, v in enumerate((f"{_data_br(h['importado_em'])} {h['importado_em'][11:16]}",
                                   h["supervisor"] or "", h["arquivo_nome"],
                                   str(h["novos"]), str(h["ignorados"]))):
                self.historico.setItem(i, j, _item(v))
        self.historico.resizeColumnsToContents()

    def _limpar(self) -> None:
        self.previa = None
        self.painel.setVisible(False)
        self.atualizar()

    def _escolher(self) -> None:
        caminho, _ = QFileDialog.getOpenFileName(
            self, "Planilha do supervisor", str(Path.home()), "Planilhas do Excel (*.xlsx)")
        if not caminho:
            return
        try:
            self.previa = troca.ler_arquivo(self.con, caminho)
        except ValueError as e:
            QMessageBox.warning(self, "Não foi possível importar", str(e))
            return
        self._mostrar_previa()

    def _mostrar_previa(self) -> None:
        pv = self.previa
        d = pv.dados
        self.cabecalho.setText(
            f"<b>{pv.arquivo_nome}</b><br>Enviado por <b>{pv.supervisor_nome}</b> em "
            f"{_data_br(d['gerado_em'])} {d['gerado_em'][11:16]} · {len(pv.itens)} recibo(s) · "
            f"<b style='color:{tema.VERDE}'>{len(pv.novos)} novo(s), "
            f"{dinheiro.formatar(pv.total_novos_centavos)}</b>"
            + (f" · {len(pv.itens) - len(pv.novos)} já importado(s)" if len(pv.novos) < len(pv.itens) else "")
            + (f" · {len(pv.colaboradores_novos)} colaborador(es) novo(s)" if pv.colaboradores_novos else ""))
        avisos = list(pv.avisos)
        if pv.lote_ja_importado_em:
            avisos.append(f"Este lote já foi importado em {_data_br(pv.lote_ja_importado_em)}.")
        self.avisos.setText("<br>".join(f"⚠ {a}" for a in avisos))
        self.avisos.setStyleSheet(f"color: {tema.LARANJA}")

        self.tabela.setRowCount(0)
        for item in pv.itens:
            r = item.dados
            i = self.tabela.rowCount()
            self.tabela.insertRow(i)
            valores = (r["ref"], _data_br(r["data_despesa"]), item.colaborador_nome, r["equipe"],
                       r["descricao"], dinheiro.formatar(r["valor_centavos"]),
                       "Novo" if item.novo else "Já importado (será ignorado)")
            for j, v in enumerate(valores):
                cor = (tema.VERDE if item.novo else tema.TEXTO_SUAVE) if j == 6 else None
                self.tabela.setItem(i, j, _item(v, cor, direita=(j == 5)))
        self.tabela.resizeColumnsToContents()

        # Chaves Pix alteradas: gestor escolhe
        while self.area_chaves.count():
            w = self.area_chaves.takeAt(0).widget()
            if w:
                w.deleteLater()
        self.combos_chave = {}
        for alt in pv.chaves_alteradas:
            linha = QWidget()
            h = QHBoxLayout(linha)
            h.setContentsMargins(0, 0, 0, 0)
            h.addWidget(_rotulo(
                f"⚠ A chave Pix de <b>{alt.nome}</b> mudou: atual "
                f"{formatar_chave(alt.tipo_atual, alt.atual)} → planilha "
                f"{formatar_chave(alt.tipo_nova, alt.nova)}"), 1)
            combo = QComboBox()
            combo.addItem("Usar a chave nova", True)
            combo.addItem("Manter a chave atual", False)
            h.addWidget(combo)
            self.combos_chave[alt.colaborador_id] = combo
            self.area_chaves.addWidget(linha)

        self.bt_confirmar.setText(f"Importar {len(pv.novos)} recibo(s)" if pv.novos else "Nada novo para importar")
        self.bt_confirmar.setEnabled(bool(pv.novos) or bool(pv.chaves_alteradas))
        self.painel.setVisible(True)

    def _confirmar(self) -> None:
        if not self.previa:
            return
        escolhas = {cid: combo.currentData() for cid, combo in self.combos_chave.items()}
        try:
            res = troca.importar(self.con, self.usuario, self.previa, escolhas)
        except (ValueError, PermissionError, OSError) as e:
            QMessageBox.warning(self, "Não foi possível importar", str(e))
            return
        QMessageBox.information(
            self, "Importação concluída",
            f"✓ {res.novos} recibo(s) na fila de pagamentos, total {dinheiro.formatar(res.total_centavos)}."
            + (f"\n{res.ignorados} já existiam e foram ignorados." if res.ignorados else "")
            + "\n\nVeja em Pagamentos pendentes.")
        self._limpar()


class TelaPagamentosPendentes(QWidget):
    """Fila do gestor: recibos importados ainda não quitados."""

    def __init__(self, con: sqlite3.Connection, usuario: Usuario):
        super().__init__()
        self.con, self.usuario = con, usuario
        layout = QVBoxLayout(self)
        layout.setContentsMargins(32, 24, 32, 24)
        layout.addWidget(_rotulo("Pagamentos pendentes", "titulo"))

        filtros = QHBoxLayout()
        self.busca = QLineEdit()
        self.busca.setPlaceholderText("Buscar por colaborador ou descrição…")
        self.busca.setClearButtonEnabled(True)
        self.supervisor = QComboBox()
        self.equipe = QComboBox()
        for combo in (self.supervisor, self.equipe):
            combo.setMinimumWidth(170)
        filtros.addWidget(self.busca, 1)
        filtros.addWidget(self.supervisor)
        filtros.addWidget(self.equipe)
        layout.addLayout(filtros)

        acoes = QHBoxLayout()
        self.bt_comprov = QPushButton("Ver comprovante")
        self.bt_comprov.setObjectName("secundario")
        self.bt_comprov.clicked.connect(self._ver_comprovante)
        self.bt_pagar = QPushButton("Pagar este colaborador →")
        self.bt_pagar.clicked.connect(self._pagar)
        acoes.addWidget(self.bt_comprov)
        acoes.addStretch()
        acoes.addWidget(self.bt_pagar)
        self.ir_para_pagamento = None  # definido pela janela principal
        layout.addLayout(acoes)

        self.modelo = ModeloRecibos()
        self.tabela = QTableView()
        self.tabela.setModel(self.modelo)
        self.tabela.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        self.tabela.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection)
        self.tabela.verticalHeader().setVisible(False)
        self.tabela.setAlternatingRowColors(True)
        self.tabela.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeMode.Interactive)
        self.tabela.horizontalHeader().setStretchLastSection(True)
        layout.addWidget(self.tabela, 1)
        self.resumo = _rotulo("", "subtitulo")
        layout.addWidget(self.resumo)

        self.busca.textChanged.connect(self._listar)
        self.supervisor.currentIndexChanged.connect(self._listar)
        self.equipe.currentIndexChanged.connect(self._listar)
        self.tabela.doubleClicked.connect(self._ver_comprovante)
        self.atualizar()

    def atualizar(self) -> None:
        for combo, consulta, todos in (
            (self.supervisor, "SELECT DISTINCT u.id, u.nome FROM recibo r JOIN usuario u"
                              " ON u.id = r.supervisor_id ORDER BY u.nome", "Todos os supervisores"),
            (self.equipe, "SELECT DISTINCT equipe, equipe FROM recibo ORDER BY equipe", "Todas as equipes"),
        ):
            atual = combo.currentData()
            combo.blockSignals(True)
            combo.clear()
            combo.addItem(todos, None)
            for valor, nome in self.con.execute(consulta):
                combo.addItem(nome, valor)
            combo.setCurrentIndex(max(combo.findData(atual), 0))
            combo.blockSignals(False)
        self._listar()

    def _listar(self) -> None:
        itens = recibos.listar(self.con, status=("PENDENTE", "PARCIAL"), busca=self.busca.text(),
                               equipe=self.equipe.currentData(),
                               supervisor_id=self.supervisor.currentData())
        self.modelo.definir(itens)
        self.tabela.resizeColumnsToContents()
        aberto = sum(r.saldo_centavos for r in itens)
        self.resumo.setText(f"{len(itens)} recibo(s) em aberto · Saldo a pagar {dinheiro.formatar(aberto)}")

    def _ver_comprovante(self, *_) -> None:
        linhas = self.tabela.selectionModel().selectedRows()
        if linhas:
            r = self.modelo.itens[linhas[0].row()]
            abrir_arquivo(comprovantes.caminho_absoluto(r.comprovante_arquivo), self)

    def _pagar(self) -> None:
        linhas = self.tabela.selectionModel().selectedRows()
        if not linhas:
            QMessageBox.information(self, "Pagar", "Selecione um recibo do colaborador que vai receber.")
            return
        if self.ir_para_pagamento:
            self.ir_para_pagamento(self.modelo.itens[linhas[0].row()].colaborador_id)
