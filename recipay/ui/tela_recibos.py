"""Telas de recibos do supervisor: cadastrar (um ou em lote) e lista."""

import json
import sqlite3
from datetime import date

from PySide6.QtCore import QAbstractTableModel, QDate, QModelIndex, Qt
from PySide6.QtGui import QColor, QKeySequence, QShortcut
from PySide6.QtWidgets import (
    QAbstractItemView,
    QCheckBox,
    QComboBox,
    QDialog,
    QFormLayout,
    QHBoxLayout,
    QHeaderView,
    QLabel,
    QLineEdit,
    QMenu,
    QMessageBox,
    QPushButton,
    QTableView,
    QTableWidget,
    QTableWidgetItem,
    QTabWidget,
    QVBoxLayout,
    QWidget,
)

from recipay import colaboradores, comprovantes, dinheiro, recibos
from recipay.auth import Usuario
from recipay.recibos import STATUS, Recibo
from recipay.ui import tema
from recipay.ui.componentes import CampoComprovante, ComboColaborador, abrir_arquivo, campo_data

CORES_STATUS = {
    "RASCUNHO": tema.TEXTO_SUAVE,
    "ENVIADO": tema.AZUL,
    "PENDENTE": tema.LARANJA,
    "PARCIAL": tema.LARANJA,
    "PAGO": tema.VERDE,
    "CANCELADO": tema.VERMELHO,
}


def _data_br(iso: str) -> str:
    return date.fromisoformat(iso).strftime("%d/%m/%Y")


def _rotulo(texto: str, nome: str = "") -> QLabel:
    r = QLabel(texto)
    if nome:
        r.setObjectName(nome)
    r.setWordWrap(True)
    return r


# ---------------------------------------------------------------- formulário
class FormRecibo(QWidget):
    """Campos de um recibo. Usado no cadastro e na edição."""

    def __init__(self, con: sqlite3.Connection, recibo: Recibo | None = None):
        super().__init__()
        self.con = con
        form = QFormLayout(self)
        form.setContentsMargins(0, 0, 0, 0)

        self.colaborador = ComboColaborador(con)
        self.equipe = QLineEdit()
        self.equipe.setPlaceholderText("Preenchida pelo colaborador; pode ser alterada")
        self.data = campo_data()
        self.valor = QLineEdit()
        self.valor.setPlaceholderText("0,00")
        self.valor.setMaximumWidth(180)
        self.descricao = QLineEdit()
        self.descricao.setPlaceholderText("Ex.: Almoço com cliente, combustível, estacionamento")
        self.comprovante = CampoComprovante()

        form.addRow("Colaborador", self.colaborador)
        form.addRow("Equipe", self.equipe)
        form.addRow("Data da despesa", self.data)
        form.addRow("Valor (R$)", self.valor)
        form.addRow("Descrição", self.descricao)
        form.addRow("Comprovante", self.comprovante)

        self.colaborador.currentIndexChanged.connect(self._preencher_equipe)

        if recibo:
            self.colaborador.selecionar(recibo.colaborador_id)
            self.equipe.setText(recibo.equipe)
            self.data.setDate(QDate.fromString(recibo.data_despesa, "yyyy-MM-dd"))
            self.valor.setText(dinheiro.formatar(recibo.valor_centavos, simbolo=False))
            self.descricao.setText(recibo.descricao)
            self.comprovante.rotulo.setText("Comprovante atual mantido. Anexe outro para trocar.")

    def _preencher_equipe(self) -> None:
        c = colaboradores.buscar(self.con, self.colaborador.colaborador_id() or "")
        if c:
            self.equipe.setText(c.equipe)

    def dados(self) -> dict:
        """Lê os campos. Levanta ValueError se o valor for inválido."""
        return {
            "colaborador_id": self.colaborador.colaborador_id(),
            "equipe": self.equipe.text(),
            "data_despesa": self.data.date().toString("yyyy-MM-dd"),
            "valor_centavos": dinheiro.para_centavos(self.valor.text()),
            "descricao": self.descricao.text(),
        }

    def limpar(self, manter_colaborador: bool = True) -> None:
        if not manter_colaborador:
            self.colaborador.selecionar(None)
            self.equipe.clear()
        self.valor.clear()
        self.descricao.clear()
        self.comprovante.definir(None)
        self.valor.setFocus()


# ---------------------------------------------------------------- cadastro
class AbaUmRecibo(QWidget):
    def __init__(self, con: sqlite3.Connection, usuario: Usuario):
        super().__init__()
        self.con, self.usuario = con, usuario
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 16, 0, 0)
        self.form = FormRecibo(con)
        layout.addWidget(self.form)
        self.mensagem = _rotulo("", "erro")
        layout.addWidget(self.mensagem)
        botoes = QHBoxLayout()
        botoes.addStretch()
        salvar = QPushButton("Salvar recibo")
        salvar.clicked.connect(self._salvar)
        botoes.addWidget(salvar)
        layout.addLayout(botoes)
        layout.addStretch()

    def _salvar(self) -> None:
        try:
            r = recibos.criar(self.con, self.usuario, self.form.dados(), self.form.comprovante.caminho)
        except (ValueError, PermissionError) as e:
            self.mensagem.setObjectName("erro")
            self.mensagem.setStyleSheet(f"color: {tema.VERMELHO}")
            self.mensagem.setText(str(e))
            return
        self.mensagem.setStyleSheet(f"color: {tema.VERDE}")
        self.mensagem.setText(
            f"✓ Recibo salvo: {r.colaborador_nome}, {dinheiro.formatar(r.valor_centavos)}. "
            "Pode cadastrar o próximo."
        )
        self.form.limpar(manter_colaborador=True)


class AbaLote(QWidget):
    """Vários recibos de uma vez. Data e descrição padrão preenchem as novas linhas."""

    COL_COLAB, COL_DATA, COL_VALOR, COL_DESCR, COL_COMPROV = range(5)

    def __init__(self, con: sqlite3.Connection, usuario: Usuario):
        super().__init__()
        self.con, self.usuario = con, usuario
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 16, 0, 0)

        padrao = QHBoxLayout()
        padrao.addWidget(QLabel("Data padrão"))
        self.data_padrao = campo_data()
        padrao.addWidget(self.data_padrao)
        padrao.addSpacing(16)
        padrao.addWidget(QLabel("Descrição padrão"))
        self.descr_padrao = QLineEdit()
        self.descr_padrao.setPlaceholderText("Ex.: Reembolso de combustível — semana 42")
        padrao.addWidget(self.descr_padrao, 1)
        layout.addLayout(padrao)
        layout.addWidget(_rotulo(
            "Os valores padrão preenchem as novas linhas. Cada linha precisa do seu comprovante.",
            "subtitulo"))

        self.tabela = QTableWidget(0, 5)
        self.tabela.setHorizontalHeaderLabels(
            ["Colaborador", "Data", "Valor (R$)", "Descrição", "Comprovante"])
        self.tabela.verticalHeader().setDefaultSectionSize(48)
        cab = self.tabela.horizontalHeader()
        cab.setSectionResizeMode(self.COL_COLAB, QHeaderView.ResizeMode.Stretch)
        cab.setSectionResizeMode(self.COL_DESCR, QHeaderView.ResizeMode.Stretch)
        for col, largura in ((self.COL_DATA, 130), (self.COL_VALOR, 120), (self.COL_COMPROV, 260)):
            self.tabela.setColumnWidth(col, largura)
        layout.addWidget(self.tabela, 1)

        self.total = _rotulo("", "subtitulo")
        layout.addWidget(self.total)
        self.mensagem = _rotulo("", "erro")
        layout.addWidget(self.mensagem)

        botoes = QHBoxLayout()
        bt_add = QPushButton("+ Adicionar linha")
        bt_add.setObjectName("secundario")
        bt_add.clicked.connect(self.adicionar_linha)
        bt_rem = QPushButton("Remover linha selecionada")
        bt_rem.setObjectName("secundario")
        bt_rem.clicked.connect(self._remover)
        bt_salvar = QPushButton("Salvar todos")
        bt_salvar.clicked.connect(self._salvar)
        botoes.addWidget(bt_add)
        botoes.addWidget(bt_rem)
        botoes.addStretch()
        botoes.addWidget(bt_salvar)
        layout.addLayout(botoes)

        for _ in range(3):
            self.adicionar_linha()

    def adicionar_linha(self) -> None:
        i = self.tabela.rowCount()
        self.tabela.insertRow(i)
        colab = ComboColaborador(self.con)
        data = campo_data(self.data_padrao.date())
        valor = QLineEdit()
        valor.setPlaceholderText("0,00")
        valor.textChanged.connect(self._atualizar_total)
        descr = QLineEdit(self.descr_padrao.text())
        comprov = CampoComprovante(compacto=True)
        for col, w in ((self.COL_COLAB, colab), (self.COL_DATA, data), (self.COL_VALOR, valor),
                       (self.COL_DESCR, descr), (self.COL_COMPROV, comprov)):
            self.tabela.setCellWidget(i, col, w)

    def _remover(self) -> None:
        linha = self.tabela.currentRow()
        if linha >= 0:
            self.tabela.removeRow(linha)
            self._atualizar_total()

    def _w(self, linha: int, col: int):
        return self.tabela.cellWidget(linha, col)

    def _linha_vazia(self, i: int) -> bool:
        return (not self._w(i, self.COL_COLAB).colaborador_id()
                and not self._w(i, self.COL_VALOR).text().strip()
                and not self._w(i, self.COL_COMPROV).caminho)

    def _atualizar_total(self) -> None:
        total, qtd = 0, 0
        for i in range(self.tabela.rowCount()):
            try:
                total += dinheiro.para_centavos(self._w(i, self.COL_VALOR).text())
                qtd += 1
            except ValueError:
                pass
        self.total.setText(f"{qtd} recibo(s) com valor · Total {dinheiro.formatar(total)}")

    def _salvar(self) -> None:
        itens = []
        for i in range(self.tabela.rowCount()):
            if self._linha_vazia(i):
                continue
            try:
                valor = dinheiro.para_centavos(self._w(i, self.COL_VALOR).text())
            except ValueError as e:
                self._erro(f"Linha {i + 1}: {e}")
                return
            colab_id = self._w(i, self.COL_COLAB).colaborador_id()
            c = colaboradores.buscar(self.con, colab_id or "")
            dados = {
                "colaborador_id": colab_id,
                "equipe": c.equipe if c else "",
                "data_despesa": self._w(i, self.COL_DATA).date().toString("yyyy-MM-dd"),
                "valor_centavos": valor,
                "descricao": self._w(i, self.COL_DESCR).text(),
            }
            comprovante = self._w(i, self.COL_COMPROV).caminho
            try:  # confere antes, para o erro apontar a linha que aparece na tela
                recibos.validar(self.con, dados)
                if not comprovante:
                    raise ValueError("Anexe o comprovante da despesa.")
            except ValueError as e:
                self._erro(f"Linha {i + 1}: {e}\nNenhum recibo foi salvo. Corrija e tente de novo.")
                return
            itens.append((dados, comprovante))
        try:
            salvos = recibos.criar_lote(self.con, self.usuario, itens)
        except (ValueError, PermissionError) as e:
            self._erro(f"{e}\nNenhum recibo foi salvo. Corrija e tente de novo.")
            return
        total = sum(r.valor_centavos for r in salvos)
        self.mensagem.setStyleSheet(f"color: {tema.VERDE}")
        self.mensagem.setText(f"✓ {len(salvos)} recibo(s) salvos. Total {dinheiro.formatar(total)}.")
        self.tabela.setRowCount(0)
        for _ in range(3):
            self.adicionar_linha()
        self._atualizar_total()

    def _erro(self, texto: str) -> None:
        self.mensagem.setStyleSheet(f"color: {tema.VERMELHO}")
        self.mensagem.setText(texto)

    def recarregar_colaboradores(self) -> None:
        for i in range(self.tabela.rowCount()):
            self._w(i, self.COL_COLAB).recarregar()


class TelaCadastrarRecibos(QWidget):
    def __init__(self, con: sqlite3.Connection, usuario: Usuario):
        super().__init__()
        layout = QVBoxLayout(self)
        layout.setContentsMargins(32, 24, 32, 24)
        layout.addWidget(_rotulo("Cadastrar recibos", "titulo"))
        layout.addWidget(_rotulo(
            "O recibo fica como rascunho e pode ser editado até ser exportado para o gestor.",
            "subtitulo"))
        self.abas = QTabWidget()
        self.um = AbaUmRecibo(con, usuario)
        self.lote = AbaLote(con, usuario)
        self.abas.addTab(self.um, "Um recibo")
        self.abas.addTab(self.lote, "Vários recibos (lote)")
        layout.addWidget(self.abas, 1)

    def atualizar(self) -> None:
        """Chamado ao abrir a tela: pega colaboradores cadastrados nesse meio-tempo."""
        self.um.form.colaborador.recarregar()
        self.lote.recarregar_colaboradores()


# ---------------------------------------------------------------- lista
class ModeloRecibos(QAbstractTableModel):
    COLUNAS = ["Data", "Colaborador", "Equipe", "Descrição", "Valor", "Saldo", "Situação"]

    def __init__(self):
        super().__init__()
        self.itens: list[Recibo] = []

    def definir(self, itens: list[Recibo]) -> None:
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
        r = self.itens[indice.row()]
        col = indice.column()
        if papel == Qt.ItemDataRole.DisplayRole:
            saldo = "—" if r.status in ("RASCUNHO", "CANCELADO") else dinheiro.formatar(r.saldo_centavos)
            return [_data_br(r.data_despesa), r.colaborador_nome, r.equipe, r.descricao,
                    dinheiro.formatar(r.valor_centavos), saldo, STATUS[r.status]][col]
        if papel == Qt.ItemDataRole.TextAlignmentRole and col in (4, 5):
            return int(Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter)
        if papel == Qt.ItemDataRole.ForegroundRole and col == 6:
            return QColor(CORES_STATUS[r.status])
        if papel == Qt.ItemDataRole.ToolTipRole and r.status == "CANCELADO":
            return f"Cancelado: {r.cancelamento_motivo}"
        return None


class DialogoEditarRecibo(QDialog):
    """Rascunho: edita direto. Já enviado: salva como correção (cancela o original e cria outro)."""

    def __init__(self, con: sqlite3.Connection, usuario: Usuario, recibo: Recibo):
        super().__init__()
        self.con, self.usuario, self.recibo = con, usuario, recibo
        self.correcao = recibo.status == "ENVIADO"
        self.setWindowTitle("Corrigir recibo enviado" if self.correcao else "Editar recibo")
        self.setMinimumWidth(560)
        layout = QVBoxLayout(self)
        layout.setContentsMargins(24, 20, 24, 20)
        if self.correcao:
            aviso = _rotulo(
                "ⓘ Este recibo já foi enviado ao gestor. Ao salvar, o Recipay <b>cancela o original</b> "
                "e cria um <b>novo rascunho</b> com os dados corrigidos. Na próxima exportação, o gestor "
                "recebe o recibo corrigido e o aviso de cancelamento do antigo.")
            aviso.setStyleSheet(f"background: {tema.AZUL_CLARO}; padding: 10px; border-radius: 6px;")
            layout.addWidget(aviso)
        self.form = FormRecibo(con, recibo)
        layout.addWidget(self.form)
        self.erro = _rotulo("", "erro")
        layout.addWidget(self.erro)
        botoes = QHBoxLayout()
        botoes.addStretch()
        cancelar = QPushButton("Fechar sem salvar")
        cancelar.setObjectName("secundario")
        cancelar.clicked.connect(self.reject)
        salvar = QPushButton("Salvar correção" if self.correcao else "Salvar alterações")
        salvar.setDefault(True)
        salvar.clicked.connect(self._salvar)
        botoes.addWidget(cancelar)
        botoes.addWidget(salvar)
        layout.addLayout(botoes)

    def _salvar(self) -> None:
        try:
            recibos.corrigir(self.con, self.usuario, self.recibo.id, self.form.dados(),
                             self.form.comprovante.caminho)
        except (ValueError, PermissionError) as e:
            self.erro.setText(str(e))
            return
        self.accept()


MOTIVOS_PRONTOS = ["Lançado em duplicidade", "Valor errado", "Colaborador errado",
                   "Despesa não aprovada", "Outro motivo…"]


class DialogoCancelarRecibo(QDialog):
    def __init__(self, recibo: Recibo, aviso: str):
        super().__init__()
        self.setWindowTitle("Cancelar recibo")
        self.setMinimumWidth(460)
        layout = QVBoxLayout(self)
        layout.setContentsMargins(24, 20, 24, 20)
        layout.addWidget(_rotulo(
            f"<b>{recibo.colaborador_nome}</b> · {dinheiro.formatar(recibo.valor_centavos)} · "
            f"{_data_br(recibo.data_despesa)}<br>{recibo.descricao}"))
        if aviso:
            info = _rotulo(f"ⓘ {aviso}")
            info.setStyleSheet(f"background: {tema.AZUL_CLARO}; padding: 8px; border-radius: 6px;")
            layout.addWidget(info)
        layout.addWidget(_rotulo("Motivo do cancelamento:"))
        self.motivos = QComboBox()
        self.motivos.addItems(MOTIVOS_PRONTOS)
        layout.addWidget(self.motivos)
        self.outro = QLineEdit()
        self.outro.setPlaceholderText("Descreva o motivo")
        self.outro.setVisible(False)
        layout.addWidget(self.outro)
        self.motivos.currentTextChanged.connect(
            lambda t: (self.outro.setVisible(t.endswith("…")), self.outro.setFocus()))
        layout.addWidget(_rotulo("O recibo não é apagado: ele continua na lista como Cancelado, "
                                 "com o motivo no histórico.", "subtitulo"))
        botoes = QHBoxLayout()
        botoes.addStretch()
        voltar = QPushButton("Voltar")
        voltar.setObjectName("secundario")
        voltar.clicked.connect(self.reject)
        confirmar = QPushButton("Cancelar recibo")
        confirmar.setStyleSheet(f"background: {tema.VERMELHO};")
        confirmar.clicked.connect(self.accept)
        botoes.addWidget(voltar)
        botoes.addWidget(confirmar)
        layout.addLayout(botoes)

    def motivo(self) -> str:
        t = self.motivos.currentText()
        return self.outro.text() if t.endswith("…") else t


EXPLICACAO_STATUS = {
    "RASCUNHO": "Rascunho: ainda não foi enviado. Você pode editar ou cancelar à vontade.",
    "ENVIADO": "Enviado ao gestor, aguardando pagamento. Editar cria uma correção; cancelar avisa o gestor "
               "na próxima exportação.",
    "PARCIAL": "Pago em parte pelo gestor. Para mudar algo, combine com o gestor.",
    "PAGO": "Pago pelo gestor. Não pode mais ser alterado.",
    "CANCELADO": "Cancelado. Veja o motivo no Histórico.",
    "PENDENTE": "Na fila de pagamento do gestor.",
}


ACOES_HISTORICO = {
    "CRIADO": "Cadastrado", "ALTERADO": "Alterado", "CANCELADO": "Cancelado",
    "EXPORTADO": "Enviado ao gestor", "REENVIADO": "Reenviado ao gestor",
    "IMPORTADO": "Importado", "PAGAMENTO": "Pagamento", "PAGAMENTO_ESTORNADO": "Pagamento estornado",
    "CORRECAO_DE": "Criado como correção", "CANCELAMENTO_AVISADO": "Cancelamento avisado ao gestor",
}
NOMES_CAMPOS = {"colaborador_id": "Colaborador", "equipe": "Equipe", "valor_centavos": "Valor",
                "descricao": "Descrição", "data_despesa": "Data"}


def _descrever_mudanca(con: sqlite3.Connection, campo: str, valores: dict) -> str:
    if campo == "comprovante":
        return "Comprovante trocado"

    def legivel(v):
        if campo == "valor_centavos":
            return dinheiro.formatar(v)
        if campo == "data_despesa":
            return _data_br(v)
        if campo == "colaborador_id":
            c = colaboradores.buscar(con, v)
            return c.nome if c else "?"
        return v

    return f"{NOMES_CAMPOS.get(campo, campo)}: {legivel(valores['antes'])} → {legivel(valores['depois'])}"


class DialogoHistorico(QDialog):
    def __init__(self, con: sqlite3.Connection, recibo: Recibo):
        super().__init__()
        self.setWindowTitle("Histórico do recibo")
        self.resize(640, 380)
        layout = QVBoxLayout(self)
        layout.addWidget(_rotulo(
            f"{recibo.colaborador_nome} · {dinheiro.formatar(recibo.valor_centavos)} · "
            f"{_data_br(recibo.data_despesa)}", "subtitulo"))
        tabela = QTableWidget(0, 3)
        tabela.setHorizontalHeaderLabels(["Quando", "Ação", "Detalhes"])
        tabela.verticalHeader().setVisible(False)
        tabela.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers)
        tabela.horizontalHeader().setStretchLastSection(True)
        for h in recibos.historico(con, recibo.id):
            i = tabela.rowCount()
            tabela.insertRow(i)
            detalhes = json.loads(h["detalhes"]) if h["detalhes"] else {}
            if h["acao"] == "ALTERADO":
                partes = [_descrever_mudanca(con, campo, v) for campo, v in detalhes.items()]
                texto = "; ".join(partes)
            elif h["acao"] == "PAGAMENTO":
                texto = (f"Pix abateu {dinheiro.formatar(detalhes.get('valor_centavos', 0))}; "
                         f"situação: {STATUS.get(detalhes.get('situacao'), '')}")
            elif h["acao"] == "PAGAMENTO_ESTORNADO":
                texto = f"Motivo: {detalhes.get('motivo', '')}"
            elif h["acao"] in ("EXPORTADO", "REENVIADO", "IMPORTADO"):
                texto = f"Arquivo: {detalhes.get('arquivo', '')}"
            elif h["acao"] == "CANCELADO":
                texto = f"Motivo: {detalhes.get('motivo', '')}"
            else:
                texto = ""
            if h["usuario"]:
                texto = f"{texto} — por {h['usuario']}" if texto else f"Por {h['usuario']}"
            data_hora = h["data_hora"]
            quando = f"{_data_br(data_hora[:10])} {data_hora[11:16]}"
            for j, valor in enumerate((quando, ACOES_HISTORICO.get(h["acao"], h["acao"]), texto)):
                tabela.setItem(i, j, QTableWidgetItem(valor))
        tabela.resizeColumnsToContents()
        layout.addWidget(tabela)
        fechar = QPushButton("Fechar")
        fechar.clicked.connect(self.accept)
        layout.addWidget(fechar, alignment=Qt.AlignmentFlag.AlignRight)


class TelaRecibos(QWidget):
    def __init__(self, con: sqlite3.Connection, usuario: Usuario):
        super().__init__()
        self.con, self.usuario = con, usuario
        layout = QVBoxLayout(self)
        layout.setContentsMargins(32, 24, 32, 24)
        layout.addWidget(_rotulo("Recibos", "titulo"))

        filtros = QHBoxLayout()
        self.busca = QLineEdit()
        self.busca.setPlaceholderText("Buscar por colaborador ou descrição…")
        self.busca.setClearButtonEnabled(True)
        self.status = QComboBox()
        self.status.addItem("Todas as situações", None)
        for codigo, nome in STATUS.items():
            self.status.addItem(nome, codigo)
        self.equipe = QComboBox()
        self.equipe.setMinimumWidth(160)
        self.ver_cancelados = QCheckBox("Mostrar cancelados")
        filtros.addWidget(self.busca, 1)
        filtros.addWidget(self.status)
        filtros.addWidget(self.equipe)
        filtros.addWidget(self.ver_cancelados)
        layout.addLayout(filtros)

        acoes = QHBoxLayout()
        self.bt_novo = QPushButton("+ Novo recibo")
        self.bt_novo.clicked.connect(lambda: self.ir_para and self.ir_para("Cadastrar recibos"))
        self.bt_editar = QPushButton("Editar / corrigir")
        self.bt_editar.setToolTip("Atalho: Enter ou duplo clique")
        self.bt_cancelar = QPushButton("Cancelar recibo")
        self.bt_cancelar.setToolTip("Atalho: tecla Delete")
        self.bt_comprov = QPushButton("Ver comprovante")
        self.bt_hist = QPushButton("Histórico")
        acoes.addWidget(self.bt_novo)
        for b in (self.bt_editar, self.bt_cancelar, self.bt_comprov, self.bt_hist):
            b.setObjectName("secundario")
            acoes.addWidget(b)
        acoes.addStretch()
        layout.addLayout(acoes)
        self.ir_para = None  # definido pela janela principal

        self.dica = _rotulo("Selecione um recibo na lista para ver o que dá para fazer com ele.", "subtitulo")
        self.dica.setStyleSheet(f"background: {tema.FUNDO_MENU}; padding: 8px; border-radius: 6px;")
        layout.addWidget(self.dica)

        self.modelo = ModeloRecibos()
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

        self.resumo = _rotulo("", "subtitulo")
        layout.addWidget(self.resumo)

        self.busca.textChanged.connect(self.atualizar)
        self.status.currentIndexChanged.connect(self.atualizar)
        self.equipe.currentIndexChanged.connect(self.atualizar)
        self.ver_cancelados.toggled.connect(self.atualizar)
        self.tabela.selectionModel().selectionChanged.connect(self._ajustar_botoes)
        self.tabela.doubleClicked.connect(self._editar)
        self.tabela.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self.tabela.customContextMenuRequested.connect(self._menu_contexto)
        QShortcut(QKeySequence(Qt.Key.Key_Delete), self.tabela, self._cancelar)
        QShortcut(QKeySequence(Qt.Key.Key_Return), self.tabela, self._editar)
        self.bt_comprov.clicked.connect(self._ver_comprovante)
        self.bt_editar.clicked.connect(self._editar)
        self.bt_cancelar.clicked.connect(self._cancelar)
        self.bt_hist.clicked.connect(self._historico)
        self.atualizar()

    def _recarregar_equipes(self) -> None:
        atual = self.equipe.currentText()
        self.equipe.blockSignals(True)
        self.equipe.clear()
        self.equipe.addItem("Todas as equipes", None)
        for e in recibos.equipes(self.con):
            self.equipe.addItem(e, e)
        self.equipe.setCurrentIndex(max(self.equipe.findText(atual), 0))
        self.equipe.blockSignals(False)

    def atualizar(self) -> None:
        self._recarregar_equipes()
        itens = recibos.listar(self.con, status=self.status.currentData(),
                               equipe=self.equipe.currentData(), busca=self.busca.text())
        if not self.ver_cancelados.isChecked() and self.status.currentData() != "CANCELADO":
            itens = [r for r in itens if r.status != "CANCELADO"]
        self.modelo.definir(itens)
        self.tabela.resizeColumnsToContents()
        validos = [r for r in itens if r.status != "CANCELADO"]
        total = sum(r.valor_centavos for r in validos)
        rascunhos = sum(1 for r in itens if r.status == "RASCUNHO")
        self.resumo.setText(
            f"{len(itens)} recibo(s) · Total {dinheiro.formatar(total)} · "
            f"{rascunhos} rascunho(s) aguardando exportação")
        self._ajustar_botoes()

    def _selecionado(self) -> Recibo | None:
        linhas = self.tabela.selectionModel().selectedRows()
        return self.modelo.itens[linhas[0].row()] if linhas else None

    def _ajustar_botoes(self, *_) -> None:
        """Os botões ficam ativos com um recibo selecionado; se a ação não for possível,
        o clique explica o motivo (em vez de um botão cinza sem explicação)."""
        r = self._selecionado()
        for b in (self.bt_comprov, self.bt_hist, self.bt_editar, self.bt_cancelar):
            b.setEnabled(r is not None)
        if not r:
            self.dica.setText("Selecione um recibo na lista para ver o que dá para fazer com ele.")
            return
        cor = CORES_STATUS.get(r.status, tema.TEXTO)
        self.dica.setText(f"<b style='color:{cor}'>{STATUS[r.status]}</b> — {EXPLICACAO_STATUS[r.status]}")
        pode, _ = recibos.pode_cancelar(r)
        self.bt_editar.setText("Corrigir…" if r.status == "ENVIADO" else "Editar / corrigir")
        self.bt_cancelar.setToolTip("Atalho: tecla Delete" if pode else recibos.pode_cancelar(r)[1])

    def _menu_contexto(self, posicao) -> None:
        if not self.tabela.indexAt(posicao).isValid():
            return
        self.tabela.selectRow(self.tabela.indexAt(posicao).row())
        menu = QMenu(self)
        menu.addAction("Editar / corrigir", self._editar)
        menu.addAction("Cancelar recibo", self._cancelar)
        menu.addSeparator()
        menu.addAction("Ver comprovante", self._ver_comprovante)
        menu.addAction("Histórico", self._historico)
        menu.exec(self.tabela.viewport().mapToGlobal(posicao))

    def _ver_comprovante(self) -> None:
        r = self._selecionado()
        if r:
            abrir_arquivo(comprovantes.caminho_absoluto(r.comprovante_arquivo), self)

    def _editar(self, *_) -> None:
        r = self._selecionado()
        if not r:
            return
        pode, motivo = recibos.pode_cancelar(r)
        if not pode:
            QMessageBox.information(self, "Não é possível editar", motivo)
            return
        dialogo = DialogoEditarRecibo(self.con, self.usuario, r)
        if dialogo.exec() == QDialog.DialogCode.Accepted:
            self.atualizar()
            if dialogo.correcao:
                QMessageBox.information(
                    self, "Correção salva",
                    "✓ O recibo corrigido foi criado como rascunho e o original foi cancelado.\n\n"
                    "Vá em Exportar recibos para enviar a correção ao gestor.")

    def _cancelar(self) -> None:
        r = self._selecionado()
        if not r:
            return
        pode, aviso = recibos.pode_cancelar(r)
        if not pode:
            QMessageBox.information(self, "Não é possível cancelar", aviso)
            return
        dialogo = DialogoCancelarRecibo(r, aviso)
        if dialogo.exec() != QDialog.DialogCode.Accepted:
            return
        try:
            recibos.cancelar(self.con, self.usuario, r.id, dialogo.motivo())
        except (ValueError, PermissionError) as e:
            QMessageBox.warning(self, "Não foi possível cancelar", str(e))
            return
        self.atualizar()

    def _historico(self) -> None:
        r = self._selecionado()
        if r:
            DialogoHistorico(self.con, r).exec()
