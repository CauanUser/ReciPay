"""Tela Relatórios: totais do mês por categoria, equipe e colaborador, e exportação em Excel."""

import sqlite3
from pathlib import Path

from PySide6.QtCore import Qt, QUrl
from PySide6.QtGui import QDesktopServices
from PySide6.QtWidgets import (
    QAbstractItemView,
    QComboBox,
    QFileDialog,
    QGridLayout,
    QHBoxLayout,
    QHeaderView,
    QLabel,
    QMessageBox,
    QPushButton,
    QTableWidget,
    QTableWidgetItem,
    QTabWidget,
    QVBoxLayout,
    QWidget,
)

from recipay import dinheiro, relatorio
from recipay.auth import Usuario
from recipay.recibos import CATEGORIAS
from recipay.ui import tema
from recipay.ui.tela_inicio import Cartao


def _item(texto: str, direita: bool = False) -> QTableWidgetItem:
    item = QTableWidgetItem(texto)
    item.setFlags(item.flags() & ~Qt.ItemFlag.ItemIsEditable)
    if direita:
        item.setTextAlignment(int(Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter))
    return item


class TelaRelatorios(QWidget):
    def __init__(self, con: sqlite3.Connection, usuario: Usuario):
        super().__init__()
        self.con, self.usuario = con, usuario
        self.gestor = usuario.perfil == "GESTOR"
        layout = QVBoxLayout(self)
        layout.setContentsMargins(32, 24, 32, 24)
        titulo = QLabel("Relatórios")
        titulo.setObjectName("titulo")
        layout.addWidget(titulo)
        sub = QLabel("Totais do mês pela data da despesa (recibos cancelados não entram). "
                     "Exporte em Excel para prestar contas ao coordenador.")
        sub.setObjectName("subtitulo")
        sub.setWordWrap(True)
        layout.addWidget(sub)

        topo = QHBoxLayout()
        topo.addWidget(QLabel("Mês:"))
        self.mes = QComboBox()
        self.mes.setMinimumWidth(180)
        topo.addWidget(self.mes)
        topo.addStretch()
        self.bt_exportar = QPushButton("Exportar relatório em Excel…")
        self.bt_exportar.clicked.connect(self._exportar)
        topo.addWidget(self.bt_exportar)
        layout.addLayout(topo)

        grade = QGridLayout()
        grade.setSpacing(16)
        self.c_lancado = Cartao("Lançado", tema.AZUL)
        self.c_pago = Cartao("Pago", tema.VERDE)
        self.c_pendente = Cartao("Pendente", tema.LARANJA)
        for i, c in enumerate((self.c_lancado, self.c_pago, self.c_pendente)):
            grade.addWidget(c, 0, i)
        layout.addLayout(grade)

        self.abas = QTabWidget()
        colunas = ["Recibos", "Lançado", "Pago", "Pendente", "% do total"]
        self.t_categoria = self._tabela(["Categoria"] + colunas)
        self.t_equipe = self._tabela(["Equipe"] + colunas)
        self.t_colab = self._tabela(["Colaborador", "Equipe"] + (["Supervisor"] if self.gestor else []) + colunas)
        self.abas.addTab(self.t_categoria, "Por categoria")
        self.abas.addTab(self.t_equipe, "Por equipe")
        self.abas.addTab(self.t_colab, "Por colaborador")
        layout.addWidget(self.abas, 1)

        self.mes.currentIndexChanged.connect(self._mostrar)
        self.atualizar()

    @staticmethod
    def _tabela(colunas: list[str]) -> QTableWidget:
        t = QTableWidget(0, len(colunas))
        t.setHorizontalHeaderLabels(colunas)
        t.verticalHeader().setVisible(False)
        t.setAlternatingRowColors(True)
        t.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        t.horizontalHeader().setSectionResizeMode(0, QHeaderView.ResizeMode.Stretch)
        return t

    def atualizar(self) -> None:
        atual = self.mes.currentData()
        self.mes.blockSignals(True)
        self.mes.clear()
        for ano, mes in relatorio.meses_com_dados(self.con):
            self.mes.addItem(relatorio.nome_mes(ano, mes).capitalize(), (ano, mes))
        self.mes.setCurrentIndex(max(self.mes.findData(atual), 0) if atual else 0)
        self.mes.blockSignals(False)
        self._mostrar()

    def _preencher(self, tabela: QTableWidget, linhas: list[tuple[list[str], relatorio.Totais]], total: int) -> None:
        tabela.setRowCount(0)
        for textos, t in linhas:
            i = tabela.rowCount()
            tabela.insertRow(i)
            valores = textos + [str(t.quantidade), dinheiro.formatar(t.lancado), dinheiro.formatar(t.pago),
                                dinheiro.formatar(t.pendente),
                                f"{100 * t.lancado / total:.1f}%".replace(".", ",") if total else "—"]
            for j, v in enumerate(valores):
                tabela.setItem(i, j, _item(v, direita=j >= len(textos)))
        tabela.resizeColumnsToContents()
        tabela.horizontalHeader().setSectionResizeMode(0, QHeaderView.ResizeMode.Stretch)

    def _mostrar(self) -> None:
        ano, mes = self.mes.currentData()
        rel = relatorio.do_mes(self.con, ano, mes)
        g = rel.geral
        self.c_lancado.definir(dinheiro.formatar(g.lancado), f"{g.quantidade} recibo(s)")
        self.c_pago.definir(dinheiro.formatar(g.pago),
                            f"{100 * g.pago / g.lancado:.0f}% do lançado".replace(".", ",") if g.lancado else "")
        self.c_pendente.definir(dinheiro.formatar(g.pendente), "a pagar")
        ordenar = lambda d: sorted(d.items(), key=lambda x: -x[1].lancado)  # noqa: E731
        self._preencher(self.t_categoria, [([CATEGORIAS.get(k, k)], t) for k, t in ordenar(rel.por_categoria)],
                        g.lancado)
        self._preencher(self.t_equipe, [([k], t) for k, t in ordenar(rel.por_equipe)], g.lancado)
        self._preencher(self.t_colab, [([n, e] + ([s] if self.gestor else []), t)
                                       for (n, e, s), t in ordenar(rel.por_colaborador)], g.lancado)
        self.bt_exportar.setEnabled(g.quantidade > 0)
        self.bt_exportar.setToolTip("" if g.quantidade else "Não há recibos neste mês.")

    def _exportar(self) -> None:
        ano, mes = self.mes.currentData()
        nome = f"relatorio_recipay_{ano}-{mes:02d}.xlsx"
        caminho, _ = QFileDialog.getSaveFileName(self, "Salvar relatório", str(Path.home() / "Documents" / nome),
                                                 "Planilhas do Excel (*.xlsx)")
        if not caminho:
            return
        try:
            relatorio.exportar_excel(self.con, self.usuario, ano, mes, caminho)
        except OSError as e:
            QMessageBox.warning(self, "Não foi possível salvar", str(e))
            return
        caixa = QMessageBox(self)
        caixa.setWindowTitle("Relatório gerado")
        caixa.setText(f"✓ Relatório de {relatorio.nome_mes(ano, mes)} salvo em:\n{caminho}")
        abrir = caixa.addButton("Abrir no Excel", QMessageBox.ButtonRole.ActionRole)
        caixa.addButton("OK", QMessageBox.ButtonRole.AcceptRole)
        caixa.exec()
        if caixa.clickedButton() == abrir:
            QDesktopServices.openUrl(QUrl.fromLocalFile(caminho))
