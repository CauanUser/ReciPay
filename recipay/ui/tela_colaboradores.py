"""Tela de Colaboradores: lista, cadastro, edição, desativação e importação de planilha."""

import sqlite3
from pathlib import Path

from PySide6.QtCore import QAbstractTableModel, QModelIndex, Qt
from PySide6.QtGui import QColor
from PySide6.QtWidgets import (
    QAbstractItemView,
    QCheckBox,
    QComboBox,
    QDialog,
    QFileDialog,
    QFormLayout,
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

from recipay import colaboradores, planilha_colaboradores
from recipay.auth import Usuario
from recipay.colaboradores import Colaborador
from recipay.pix import TIPOS_CHAVE, formatar_chave, normalizar_chave
from recipay.ui import tema

TODAS_EQUIPES = "Todas as equipes"


class ModeloColaboradores(QAbstractTableModel):
    COLUNAS = ["Nome", "Equipe", "Tipo da chave", "Chave Pix", "Titular", "Situação"]

    def __init__(self):
        super().__init__()
        self.itens: list[Colaborador] = []

    def definir(self, itens: list[Colaborador]) -> None:
        self.beginResetModel()
        self.itens = itens
        self.endResetModel()

    def rowCount(self, parent=QModelIndex()) -> int:
        return 0 if parent.isValid() else len(self.itens)

    def columnCount(self, parent=QModelIndex()) -> int:
        return len(self.COLUNAS)

    def headerData(self, secao, orientacao, papel=Qt.ItemDataRole.DisplayRole):
        if orientacao == Qt.Orientation.Horizontal and papel == Qt.ItemDataRole.DisplayRole:
            return self.COLUNAS[secao]
        return None

    def data(self, indice, papel=Qt.ItemDataRole.DisplayRole):
        c = self.itens[indice.row()]
        if papel == Qt.ItemDataRole.DisplayRole:
            return [
                c.nome, c.equipe, TIPOS_CHAVE[c.tipo_chave],
                formatar_chave(c.tipo_chave, c.chave_pix), c.titular_pix,
                "Ativo" if c.ativo else "Inativo",
            ][indice.column()]
        if papel == Qt.ItemDataRole.ForegroundRole:
            if not c.ativo:
                return QColor(tema.TEXTO_SUAVE)
            if indice.column() == 5:
                return QColor(tema.VERDE)
        return None


class DialogoColaborador(QDialog):
    """Formulário de cadastro/edição com validação da chave Pix em tempo real."""

    def __init__(self, con: sqlite3.Connection, usuario: Usuario, colaborador: Colaborador | None = None):
        super().__init__()
        self.con = con
        self.usuario = usuario
        self.colaborador = colaborador
        self.setWindowTitle("Editar colaborador" if colaborador else "Novo colaborador")
        self.setMinimumWidth(480)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(24, 20, 24, 20)
        form = QFormLayout()

        self.nome = QLineEdit()
        self.equipe = QComboBox()
        self.equipe.setEditable(True)
        self.equipe.addItems(colaboradores.equipes(con))
        self.equipe.setCurrentText("")
        self.tipo = QComboBox()
        for codigo, nome in TIPOS_CHAVE.items():
            self.tipo.addItem(nome, codigo)
        self.chave = QLineEdit()
        self.situacao_chave = QLabel("")
        self.titular = QLineEdit()
        self.titular.setPlaceholderText("Nome do titular da conta, conforme informado")

        form.addRow("Nome completo", self.nome)
        form.addRow("Equipe", self.equipe)
        form.addRow("Tipo da chave", self.tipo)
        form.addRow("Chave Pix", self.chave)
        form.addRow("", self.situacao_chave)
        form.addRow("Titular da conta", self.titular)
        layout.addLayout(form)

        self.erro = QLabel("")
        self.erro.setObjectName("erro")
        self.erro.setWordWrap(True)
        layout.addWidget(self.erro)

        botoes = QHBoxLayout()
        botoes.addStretch()
        cancelar = QPushButton("Cancelar")
        cancelar.setObjectName("secundario")
        cancelar.clicked.connect(self.reject)
        salvar = QPushButton("Salvar")
        salvar.clicked.connect(self._salvar)
        botoes.addWidget(cancelar)
        botoes.addWidget(salvar)
        layout.addLayout(botoes)

        if colaborador:
            self.nome.setText(colaborador.nome)
            self.equipe.setCurrentText(colaborador.equipe)
            self.tipo.setCurrentIndex(self.tipo.findData(colaborador.tipo_chave))
            self.chave.setText(formatar_chave(colaborador.tipo_chave, colaborador.chave_pix))
            self.titular.setText(colaborador.titular_pix)

        self.chave.textChanged.connect(self._conferir_chave)
        self.tipo.currentIndexChanged.connect(self._conferir_chave)
        # Sugere o titular igual ao nome enquanto o titular estiver vazio.
        self.nome.editingFinished.connect(
            lambda: self.titular.setText(self.nome.text().strip()) if not self.titular.text() else None
        )

    def _conferir_chave(self) -> None:
        if not self.chave.text().strip():
            self.situacao_chave.setText("")
            return
        try:
            chave = normalizar_chave(self.tipo.currentData(), self.chave.text())
            self.situacao_chave.setStyleSheet(f"color: {tema.VERDE}")
            self.situacao_chave.setText("✓ " + formatar_chave(self.tipo.currentData(), chave))
        except ValueError as e:
            self.situacao_chave.setStyleSheet(f"color: {tema.VERMELHO}")
            self.situacao_chave.setText(str(e))

    def dados(self) -> dict:
        return {
            "nome": self.nome.text(),
            "equipe": self.equipe.currentText(),
            "tipo_chave": self.tipo.currentData(),
            "chave_pix": self.chave.text(),
            "titular_pix": self.titular.text(),
        }

    def _salvar(self) -> None:
        try:
            if self.colaborador:
                colaboradores.atualizar(self.con, self.usuario, self.colaborador.id, self.dados())
            else:
                colaboradores.criar(self.con, self.usuario, self.dados())
        except (ValueError, PermissionError) as e:
            self.erro.setText(str(e))
            return
        self.accept()


class DialogoPreviaPlanilha(QDialog):
    """Mostra cada linha da planilha com OK ou o erro, antes de gravar."""

    def __init__(self, previa: list[planilha_colaboradores.LinhaPrevia], nome_arquivo: str):
        super().__init__()
        self.setWindowTitle("Prévia da importação")
        self.resize(1000, 560)
        validas = sum(1 for l in previa if l.valida)
        invalidas = len(previa) - validas

        layout = QVBoxLayout(self)
        titulo = QLabel(f"Planilha: {nome_arquivo}")
        titulo.setObjectName("subtitulo")
        layout.addWidget(titulo)
        resumo = QLabel(
            f"<b style='color:{tema.VERDE}'>{validas} prontas para importar</b>"
            + (f" · <b style='color:{tema.VERMELHO}'>{invalidas} com erro (serão ignoradas)</b>"
               if invalidas else "")
        )
        layout.addWidget(resumo)

        colunas = ["Linha", "Nome", "Equipe", "Tipo", "Chave Pix", "Titular", "Situação"]
        tabela = QTableWidget(len(previa), len(colunas))
        tabela.setHorizontalHeaderLabels(colunas)
        tabela.verticalHeader().setVisible(False)
        tabela.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers)
        for i, linha in enumerate(previa):
            d = linha.dados or {}
            tipo = d.get("tipo_chave")
            valores = [
                str(linha.numero),
                d.get("nome", linha.original.get("nome", "")),
                d.get("equipe", linha.original.get("equipe", "")),
                TIPOS_CHAVE.get(tipo, linha.original.get("tipo_chave", "")),
                formatar_chave(tipo, d["chave_pix"]) if d else linha.original.get("chave_pix", ""),
                d.get("titular_pix", linha.original.get("titular_pix", "")),
                "; ".join(linha.erros) if linha.erros else ("OK. " + " ".join(linha.avisos)).strip(),
            ]
            for j, valor in enumerate(valores):
                item = QTableWidgetItem(valor)
                if j == 6:
                    item.setForeground(QColor(tema.VERMELHO if linha.erros else tema.VERDE))
                tabela.setItem(i, j, item)
        tabela.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeMode.ResizeToContents)
        tabela.horizontalHeader().setStretchLastSection(True)
        layout.addWidget(tabela, 1)

        botoes = QHBoxLayout()
        botoes.addStretch()
        cancelar = QPushButton("Cancelar")
        cancelar.setObjectName("secundario")
        cancelar.clicked.connect(self.reject)
        importar = QPushButton(f"Importar {validas} colaborador(es)")
        importar.setEnabled(validas > 0)
        importar.clicked.connect(self.accept)
        botoes.addWidget(cancelar)
        botoes.addWidget(importar)
        layout.addLayout(botoes)


class TelaColaboradores(QWidget):
    def __init__(self, con: sqlite3.Connection, usuario: Usuario):
        super().__init__()
        self.con = con
        self.usuario = usuario
        self.pode_editar = usuario.perfil == "SUPERVISOR"

        layout = QVBoxLayout(self)
        layout.setContentsMargins(32, 24, 32, 24)
        titulo = QLabel("Colaboradores")
        titulo.setObjectName("titulo")
        layout.addWidget(titulo)
        if not self.pode_editar:
            aviso = QLabel("Consulta. Os colaboradores chegam junto com os arquivos dos supervisores.")
            aviso.setObjectName("subtitulo")
            layout.addWidget(aviso)

        # Filtros
        filtros = QHBoxLayout()
        self.busca = QLineEdit()
        self.busca.setPlaceholderText("Buscar por nome, titular ou chave…")
        self.busca.setClearButtonEnabled(True)
        self.filtro_equipe = QComboBox()
        self.filtro_equipe.setMinimumWidth(180)
        self.inativos = QCheckBox("Mostrar inativos")
        filtros.addWidget(self.busca, 1)
        filtros.addWidget(self.filtro_equipe)
        filtros.addWidget(self.inativos)
        layout.addLayout(filtros)

        # Ações
        acoes = QHBoxLayout()
        self.bt_novo = QPushButton("+ Novo colaborador")
        self.bt_editar = QPushButton("Editar")
        self.bt_editar.setObjectName("secundario")
        self.bt_ativo = QPushButton("Desativar")
        self.bt_ativo.setObjectName("secundario")
        self.bt_importar = QPushButton("Importar planilha")
        self.bt_importar.setObjectName("secundario")
        self.bt_modelo = QPushButton("Baixar modelo de planilha")
        self.bt_modelo.setObjectName("secundario")
        for b in (self.bt_novo, self.bt_editar, self.bt_ativo):
            acoes.addWidget(b)
        acoes.addStretch()
        acoes.addWidget(self.bt_modelo)
        acoes.addWidget(self.bt_importar)
        if self.pode_editar:
            layout.addLayout(acoes)

        # Tabela
        self.modelo = ModeloColaboradores()
        self.tabela = QTableView()
        self.tabela.setModel(self.modelo)
        self.tabela.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        self.tabela.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection)
        self.tabela.verticalHeader().setVisible(False)
        self.tabela.setAlternatingRowColors(True)
        cab = self.tabela.horizontalHeader()
        cab.setSectionResizeMode(QHeaderView.ResizeMode.Interactive)
        cab.setStretchLastSection(True)
        layout.addWidget(self.tabela, 1)

        self.contador = QLabel("")
        self.contador.setObjectName("subtitulo")
        layout.addWidget(self.contador)

        # Sinais
        self.busca.textChanged.connect(self.atualizar)
        self.filtro_equipe.currentIndexChanged.connect(self.atualizar)
        self.inativos.toggled.connect(self.atualizar)
        self.tabela.selectionModel().selectionChanged.connect(self._ajustar_botoes)
        if self.pode_editar:
            self.tabela.doubleClicked.connect(self._editar)
            self.bt_novo.clicked.connect(self._novo)
            self.bt_editar.clicked.connect(self._editar)
            self.bt_ativo.clicked.connect(self._alternar_ativo)
            self.bt_importar.clicked.connect(self._importar)
            self.bt_modelo.clicked.connect(self._baixar_modelo)

        self._recarregar_equipes()
        self.atualizar()

    # ---------- dados ----------
    def _recarregar_equipes(self) -> None:
        atual = self.filtro_equipe.currentText()
        self.filtro_equipe.blockSignals(True)
        self.filtro_equipe.clear()
        self.filtro_equipe.addItem(TODAS_EQUIPES)
        self.filtro_equipe.addItems(colaboradores.equipes(self.con))
        indice = self.filtro_equipe.findText(atual)
        self.filtro_equipe.setCurrentIndex(max(indice, 0))
        self.filtro_equipe.blockSignals(False)

    def atualizar(self) -> None:
        equipe = self.filtro_equipe.currentText()
        itens = colaboradores.listar(
            self.con,
            busca=self.busca.text(),
            equipe=None if equipe in ("", TODAS_EQUIPES) else equipe,
            incluir_inativos=self.inativos.isChecked(),
        )
        self.modelo.definir(itens)
        self.tabela.resizeColumnsToContents()
        self.contador.setText(f"{len(itens)} colaborador(es)")
        self._ajustar_botoes()

    def _selecionado(self) -> Colaborador | None:
        linhas = self.tabela.selectionModel().selectedRows()
        return self.modelo.itens[linhas[0].row()] if linhas else None

    def _ajustar_botoes(self, *_) -> None:
        c = self._selecionado()
        self.bt_editar.setEnabled(c is not None)
        self.bt_ativo.setEnabled(c is not None)
        self.bt_ativo.setText("Reativar" if c and not c.ativo else "Desativar")

    # ---------- ações ----------
    def _novo(self) -> None:
        if DialogoColaborador(self.con, self.usuario).exec() == QDialog.DialogCode.Accepted:
            self._recarregar_equipes()
            self.atualizar()

    def _editar(self, *_) -> None:
        c = self._selecionado()
        if c and DialogoColaborador(self.con, self.usuario, c).exec() == QDialog.DialogCode.Accepted:
            self._recarregar_equipes()
            self.atualizar()

    def _alternar_ativo(self) -> None:
        c = self._selecionado()
        if not c:
            return
        acao = "reativar" if not c.ativo else "desativar"
        resposta = QMessageBox.question(
            self, "Confirmar", f"Deseja {acao} {c.nome}?\nO histórico é mantido."
        )
        if resposta != QMessageBox.StandardButton.Yes:
            return
        try:
            colaboradores.definir_ativo(self.con, self.usuario, c.id, not c.ativo)
        except (ValueError, PermissionError) as e:
            QMessageBox.warning(self, "Não foi possível", str(e))
        self.atualizar()

    def _importar(self) -> None:
        caminho, _ = QFileDialog.getOpenFileName(
            self, "Escolha a planilha de colaboradores", "", "Planilhas do Excel (*.xlsx)"
        )
        if not caminho:
            return
        try:
            previa = planilha_colaboradores.ler(self.con, caminho)
        except ValueError as e:
            QMessageBox.warning(self, "Planilha inválida", str(e))
            return
        dialogo = DialogoPreviaPlanilha(previa, Path(caminho).name)
        if dialogo.exec() != QDialog.DialogCode.Accepted:
            return
        total = planilha_colaboradores.gravar(self.con, self.usuario, previa)
        QMessageBox.information(self, "Importação concluída", f"{total} colaborador(es) importado(s).")
        self._recarregar_equipes()
        self.atualizar()

    def _baixar_modelo(self) -> None:
        caminho, _ = QFileDialog.getSaveFileName(
            self, "Salvar modelo de planilha",
            str(Path.home() / "Documents" / "modelo_colaboradores.xlsx"),
            "Planilhas do Excel (*.xlsx)",
        )
        if not caminho:
            return
        try:
            planilha_colaboradores.salvar_modelo(caminho)
        except OSError as e:
            QMessageBox.warning(self, "Não foi possível salvar", f"{e}\nO arquivo está aberto no Excel?")
            return
        QMessageBox.information(self, "Modelo salvo", f"Modelo salvo em:\n{caminho}")
