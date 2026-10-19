"""Tela Realizar pagamento (Gestor): escolher colaborador, recibos, QR Code e registrar o Pix."""

import sqlite3
from datetime import date

from PySide6.QtCore import Qt
from PySide6.QtGui import QColor, QGuiApplication, QPixmap
from PySide6.QtWidgets import (
    QAbstractItemView,
    QCheckBox,
    QComboBox,
    QDialog,
    QFormLayout,
    QFrame,
    QGroupBox,
    QHBoxLayout,
    QHeaderView,
    QInputDialog,
    QLabel,
    QLineEdit,
    QMessageBox,
    QPushButton,
    QScrollArea,
    QSplitter,
    QTableWidget,
    QTableWidgetItem,
    QVBoxLayout,
    QWidget,
)

from recipay import brcode, colaboradores, comprovantes, dinheiro, pagamentos, recibos
from recipay.auth import Usuario
from recipay.pix import TIPOS_CHAVE, formatar_chave, normalizar_chave
from recipay.ui import tema
from recipay.ui.componentes import CampoComprovante, abrir_arquivo, campo_data


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


class DialogoQrCode(QDialog):
    """Mostra o QR Code Pix e o "copia e cola". Não comprova pagamento (RN-11)."""

    def __init__(self, chave: str, tipo: str, titular: str, valor_centavos: int, descricao: str):
        super().__init__()
        self.setWindowTitle("QR Code Pix")
        layout = QVBoxLayout(self)
        layout.setContentsMargins(24, 20, 24, 20)
        self.payload = brcode.gerar_payload(chave, titular, valor_centavos, descricao=descricao)

        layout.addWidget(_rotulo(f"<b style='font-size:16pt'>{dinheiro.formatar(valor_centavos)}</b>"))
        layout.addWidget(_rotulo(f"{titular}<br>{TIPOS_CHAVE[tipo]}: {formatar_chave(tipo, chave)}",
                                 "subtitulo"))
        imagem = QLabel()
        pix = QPixmap()
        pix.loadFromData(brcode.imagem_png(self.payload))
        imagem.setPixmap(pix.scaledToWidth(300, Qt.TransformationMode.SmoothTransformation))
        imagem.setAlignment(Qt.AlignmentFlag.AlignCenter)
        layout.addWidget(imagem)

        copia = QLineEdit(self.payload)
        copia.setReadOnly(True)
        layout.addWidget(_rotulo("Pix copia e cola:", "subtitulo"))
        layout.addWidget(copia)

        aviso = _rotulo("⚠ Confira no app do banco se o nome do recebedor está correto antes de pagar. "
                        "O QR Code não comprova o pagamento: depois de pagar, registre o Pix com o "
                        "comprovante bancário.")
        aviso.setStyleSheet(f"color: {tema.LARANJA}")
        layout.addWidget(aviso)

        botoes = QHBoxLayout()
        self.bt_copiar = QPushButton("Copiar código")
        self.bt_copiar.setObjectName("secundario")
        self.bt_copiar.clicked.connect(self._copiar)
        fechar = QPushButton("Fechar")
        fechar.clicked.connect(self.accept)
        botoes.addWidget(self.bt_copiar)
        botoes.addStretch()
        botoes.addWidget(fechar)
        layout.addLayout(botoes)

    def _copiar(self) -> None:
        QGuiApplication.clipboard().setText(self.payload)
        self.bt_copiar.setText("✓ Copiado")


class TelaRealizarPagamento(QWidget):
    COL_MARCA, COL_DATA, COL_DESCR, COL_VALOR, COL_SALDO, COL_ABATER = range(6)

    def __init__(self, con: sqlite3.Connection, usuario: Usuario):
        super().__init__()
        self.con, self.usuario = con, usuario
        self.colab: colaboradores.Colaborador | None = None
        self.abertos: list[recibos.Recibo] = []
        self.campos_abater: list[QLineEdit] = []

        raiz = QVBoxLayout(self)
        raiz.setContentsMargins(32, 24, 32, 16)
        raiz.addWidget(_rotulo("Realizar pagamento", "titulo"))
        raiz.addWidget(_rotulo(
            "1) Escolha o colaborador  2) Marque os recibos  3) Faça o Pix no banco (o QR Code ajuda)  "
            "4) Registre com o comprovante.", "subtitulo"))

        divisor = QSplitter(Qt.Orientation.Horizontal)
        raiz.addWidget(divisor, 1)

        # ---------------- esquerda: colaboradores com pendência
        esquerda = QWidget()
        e = QVBoxLayout(esquerda)
        e.setContentsMargins(0, 8, 12, 0)
        self.busca = QLineEdit()
        self.busca.setPlaceholderText("Buscar colaborador…")
        self.busca.setClearButtonEnabled(True)
        self.supervisor = QComboBox()
        e.addWidget(self.busca)
        e.addWidget(self.supervisor)
        self.lista = QTableWidget(0, 3)
        self.lista.setHorizontalHeaderLabels(["Colaborador", "Recibos", "Saldo"])
        self.lista.verticalHeader().setVisible(False)
        self.lista.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        self.lista.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection)
        cab = self.lista.horizontalHeader()
        cab.setSectionResizeMode(0, QHeaderView.ResizeMode.Stretch)
        cab.setSectionResizeMode(1, QHeaderView.ResizeMode.ResizeToContents)
        cab.setSectionResizeMode(2, QHeaderView.ResizeMode.ResizeToContents)
        self.lista.setWordWrap(False)
        e.addWidget(self.lista, 1)
        self.total_geral = _rotulo("", "subtitulo")
        e.addWidget(self.total_geral)
        divisor.addWidget(esquerda)

        # ---------------- direita: detalhe do pagamento (com rolagem)
        rolagem = QScrollArea()
        rolagem.setWidgetResizable(True)
        rolagem.setFrameShape(QFrame.Shape.NoFrame)
        self.detalhe = QWidget()
        d = QVBoxLayout(self.detalhe)
        d.setContentsMargins(12, 8, 4, 0)
        rolagem.setWidget(self.detalhe)
        divisor.addWidget(rolagem)
        divisor.setSizes([400, 820])

        self.vazio = _rotulo("Escolha um colaborador na lista ao lado.", "subtitulo")
        d.addWidget(self.vazio)

        self.conteudo = QWidget()
        c = QVBoxLayout(self.conteudo)
        c.setContentsMargins(0, 0, 0, 0)

        # Dados do Pix
        self.caixa_pix = QGroupBox("Dados para o Pix")
        p = QHBoxLayout(self.caixa_pix)
        self.info_pix = _rotulo("")
        self.info_pix.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        p.addWidget(self.info_pix, 1)
        self.bt_copiar_chave = QPushButton("Copiar chave")
        self.bt_copiar_chave.setObjectName("secundario")
        self.bt_copiar_chave.clicked.connect(self._copiar_chave)
        p.addWidget(self.bt_copiar_chave)
        c.addWidget(self.caixa_pix)

        # Recibos em aberto
        c.addWidget(_rotulo("<b>Recibos em aberto</b> (do mais antigo para o mais novo)"))
        self.tabela = QTableWidget(0, 6)
        self.tabela.setHorizontalHeaderLabels(["", "Data", "Descrição", "Valor", "Saldo", "Abater (R$)"])
        self.tabela.verticalHeader().setVisible(False)
        self.tabela.verticalHeader().setDefaultSectionSize(40)
        self.tabela.horizontalHeader().setSectionResizeMode(self.COL_DESCR, QHeaderView.ResizeMode.Stretch)
        self.tabela.setMinimumHeight(170)
        c.addWidget(self.tabela)

        linha_valor = QHBoxLayout()
        linha_valor.addWidget(QLabel("Valor do Pix (R$)"))
        self.valor_pix = QLineEdit()
        self.valor_pix.setPlaceholderText("0,00")
        self.valor_pix.setMaximumWidth(150)
        linha_valor.addWidget(self.valor_pix)
        self.bt_distribuir = QPushButton("Distribuir nos recibos")
        self.bt_distribuir.setObjectName("secundario")
        self.bt_distribuir.setToolTip("Abate do recibo mais antigo para o mais novo.")
        self.bt_distribuir.clicked.connect(self._distribuir)
        linha_valor.addWidget(self.bt_distribuir)
        linha_valor.addStretch()
        self.bt_qr = QPushButton("Gerar QR Code Pix")
        self.bt_qr.setObjectName("secundario")
        self.bt_qr.clicked.connect(self._qr_code)
        linha_valor.addWidget(self.bt_qr)
        c.addLayout(linha_valor)
        self.resumo = _rotulo("")
        c.addWidget(self.resumo)

        # Registro do pagamento
        registro = QGroupBox("Registrar o Pix já feito no banco")
        f = QFormLayout(registro)
        self.data_pag = campo_data()
        self.comprovante = CampoComprovante()
        self.outra = QCheckBox("Usei outra chave Pix só neste pagamento")
        self.outra_tipo = QComboBox()
        for codigo, nome in TIPOS_CHAVE.items():
            self.outra_tipo.addItem(nome, codigo)
        self.outra_chave = QLineEdit()
        self.outra_titular = QLineEdit()
        self.outra_titular.setPlaceholderText("Titular da conta usada")
        self.caixa_outra = QWidget()
        o = QHBoxLayout(self.caixa_outra)
        o.setContentsMargins(0, 0, 0, 0)
        o.addWidget(self.outra_tipo)
        o.addWidget(self.outra_chave, 1)
        o.addWidget(self.outra_titular, 1)
        self.caixa_outra.setVisible(False)
        f.addRow("Data do pagamento", self.data_pag)
        f.addRow("Comprovante bancário", self.comprovante)
        f.addRow("", self.outra)
        f.addRow("", self.caixa_outra)
        c.addWidget(registro)

        self.erro = _rotulo("", "erro")
        c.addWidget(self.erro)
        botoes = QHBoxLayout()
        botoes.addStretch()
        self.bt_registrar = QPushButton("Registrar pagamento")
        self.bt_registrar.clicked.connect(self._registrar)
        botoes.addWidget(self.bt_registrar)
        c.addLayout(botoes)
        c.addStretch()

        d.addWidget(self.conteudo)
        self.conteudo.setVisible(False)

        # Sinais
        self.busca.textChanged.connect(self._listar_colaboradores)
        self.supervisor.currentIndexChanged.connect(self._listar_colaboradores)
        self.lista.itemSelectionChanged.connect(self._abrir_colaborador)
        self.tabela.itemChanged.connect(self._marcacao_mudou)
        self.outra.toggled.connect(self.caixa_outra.setVisible)
        self.atualizar()

    # ------------------------------------------------------------ lista
    def atualizar(self) -> None:
        atual = self.supervisor.currentData()
        self.supervisor.blockSignals(True)
        self.supervisor.clear()
        self.supervisor.addItem("Todos os supervisores", None)
        for uid, nome in self.con.execute(
                "SELECT DISTINCT u.id, u.nome FROM recibo r JOIN usuario u ON u.id = r.supervisor_id"
                " WHERE r.status IN ('PENDENTE','PARCIAL') ORDER BY u.nome"):
            self.supervisor.addItem(nome, uid)
        self.supervisor.setCurrentIndex(max(self.supervisor.findData(atual), 0))
        self.supervisor.blockSignals(False)
        self._listar_colaboradores()

    def _listar_colaboradores(self) -> None:
        selecionado = self.colab.id if self.colab else None
        self.pendentes = pagamentos.colaboradores_com_pendencia(
            self.con, self.busca.text(), self.supervisor.currentData())
        self.lista.blockSignals(True)
        self.lista.setRowCount(0)
        linha_sel = -1
        for i, c in enumerate(self.pendentes):
            self.lista.insertRow(i)
            nome = _item(f"{c.nome}\n{c.equipe} · {c.supervisor_nome}")
            nome.setToolTip(f"{c.nome} — {c.equipe} — supervisor {c.supervisor_nome}")
            self.lista.setItem(i, 0, nome)
            self.lista.setItem(i, 1, _item(str(c.qtd_recibos), direita=True))
            self.lista.setItem(i, 2, _item(dinheiro.formatar(c.saldo_centavos), direita=True))
            if c.id == selecionado:
                linha_sel = i
        self.lista.resizeRowsToContents()
        self.lista.blockSignals(False)
        total = sum(c.saldo_centavos for c in self.pendentes)
        self.total_geral.setText(f"{len(self.pendentes)} colaborador(es) · {dinheiro.formatar(total)} a pagar")
        if linha_sel >= 0:
            self.lista.selectRow(linha_sel)
            self._abrir_colaborador()
        else:
            self.colab = None
            self.conteudo.setVisible(False)
            self.vazio.setVisible(True)

    def selecionar_colaborador(self, colaborador_id: str) -> None:
        """Usado pela tela Pagamentos pendentes para abrir direto um colaborador."""
        self.busca.clear()
        self.supervisor.setCurrentIndex(0)
        for i, c in enumerate(self.pendentes):
            if c.id == colaborador_id:
                self.lista.selectRow(i)
                return

    # ------------------------------------------------------------ detalhe
    def _abrir_colaborador(self) -> None:
        linhas = self.lista.selectionModel().selectedRows()
        if not linhas:
            return
        self.colab = colaboradores.buscar(self.con, self.pendentes[linhas[0].row()].id)
        c = self.colab
        self.info_pix.setText(
            f"<b>{c.titular_pix}</b> (titular)<br>{TIPOS_CHAVE[c.tipo_chave]}: "
            f"<b>{formatar_chave(c.tipo_chave, c.chave_pix)}</b><br>"
            f"<span style='color:{tema.TEXTO_SUAVE}'>{c.nome} · {c.equipe}</span>")
        self.abertos = pagamentos.recibos_em_aberto(self.con, c.id)
        self.tabela.blockSignals(True)
        self.tabela.setRowCount(0)
        self.campos_abater = []
        for i, r in enumerate(self.abertos):
            self.tabela.insertRow(i)
            marca = QTableWidgetItem()
            marca.setFlags(Qt.ItemFlag.ItemIsUserCheckable | Qt.ItemFlag.ItemIsEnabled)
            marca.setCheckState(Qt.CheckState.Checked)
            self.tabela.setItem(i, self.COL_MARCA, marca)
            self.tabela.setItem(i, self.COL_DATA, _item(_data_br(r.data_despesa)))
            descr = r.descricao + (" (parcial)" if r.status == "PARCIAL" else "")
            self.tabela.setItem(i, self.COL_DESCR, _item(descr))
            self.tabela.setItem(i, self.COL_VALOR, _item(dinheiro.formatar(r.valor_centavos), True))
            self.tabela.setItem(i, self.COL_SALDO, _item(dinheiro.formatar(r.saldo_centavos), True))
            campo = QLineEdit(dinheiro.formatar(r.saldo_centavos, simbolo=False))
            campo.setAlignment(Qt.AlignmentFlag.AlignRight)
            campo.textChanged.connect(self._atualizar_resumo)
            self.tabela.setCellWidget(i, self.COL_ABATER, campo)
            self.campos_abater.append(campo)
        self.tabela.resizeColumnsToContents()
        self.tabela.setColumnWidth(self.COL_ABATER, 120)
        self.tabela.blockSignals(False)

        self.valor_pix.setText(dinheiro.formatar(sum(r.saldo_centavos for r in self.abertos), simbolo=False))
        self.comprovante.definir(None)
        self.outra.setChecked(False)
        self.outra_chave.clear()
        self.outra_titular.clear()
        self.data_pag.setDate(self.data_pag.maximumDate())
        self.erro.setText("")
        self.vazio.setVisible(False)
        self.conteudo.setVisible(True)
        self._atualizar_resumo()

    def _marcacao_mudou(self, item: QTableWidgetItem) -> None:
        if item.column() != self.COL_MARCA:
            return
        i = item.row()
        marcado = item.checkState() == Qt.CheckState.Checked
        campo = self.campos_abater[i]
        campo.setEnabled(marcado)
        campo.setText(dinheiro.formatar(self.abertos[i].saldo_centavos, simbolo=False) if marcado else "0,00")
        self.valor_pix.setText(dinheiro.formatar(self._total_abatido()[0], simbolo=False))

    def _aplicacoes(self) -> dict[str, int]:
        """Lê a coluna Abater. Levanta ValueError apontando a linha com problema."""
        resultado = {}
        for i, r in enumerate(self.abertos):
            if self.tabela.item(i, self.COL_MARCA).checkState() != Qt.CheckState.Checked:
                continue
            texto = self.campos_abater[i].text().strip()
            if texto in ("", "0", "0,00"):
                continue
            try:
                valor = dinheiro.para_centavos(texto)
            except ValueError as e:
                raise ValueError(f"Recibo \"{r.descricao}\": {e}") from e
            if valor > r.saldo_centavos:
                raise ValueError(f"Recibo \"{r.descricao}\": valor maior que o saldo "
                                 f"({dinheiro.formatar(r.saldo_centavos)}).")
            resultado[r.id] = valor
        return resultado

    def _total_abatido(self) -> tuple[int, str]:
        try:
            return sum(self._aplicacoes().values()), ""
        except ValueError as e:
            return 0, str(e)

    def _atualizar_resumo(self, *_) -> None:
        total, erro = self._total_abatido()
        if erro:
            self.resumo.setStyleSheet(f"color: {tema.VERMELHO}")
            self.resumo.setText(erro)
            return
        try:
            pix = dinheiro.para_centavos(self.valor_pix.text())
        except ValueError:
            pix = 0
        saldo_total = sum(r.saldo_centavos for r in self.abertos)
        texto = f"Abatendo <b>{dinheiro.formatar(total)}</b> de {dinheiro.formatar(saldo_total)} em aberto."
        if pix and pix != total:
            texto += (f" <span style='color:{tema.LARANJA}'>O valor do Pix ({dinheiro.formatar(pix)}) "
                      "é diferente do total abatido: clique em Distribuir ou ajuste a coluna Abater.</span>")
        self.resumo.setStyleSheet("")
        self.resumo.setText(texto)

    def _distribuir(self) -> None:
        try:
            valor = dinheiro.para_centavos(self.valor_pix.text())
        except ValueError as e:
            self.resumo.setStyleSheet(f"color: {tema.VERMELHO}")
            self.resumo.setText(f"Valor do Pix: {e}")
            return
        marcados = [(i, r) for i, r in enumerate(self.abertos)
                    if self.tabela.item(i, self.COL_MARCA).checkState() == Qt.CheckState.Checked]
        divisao = pagamentos.distribuir([(r.id, r.saldo_centavos) for _, r in marcados], valor)
        for i, r in marcados:
            self.campos_abater[i].setText(dinheiro.formatar(divisao[r.id], simbolo=False))
        sobra = valor - sum(divisao.values())
        if sobra > 0:
            self.valor_pix.setText(dinheiro.formatar(valor - sobra, simbolo=False))
            QMessageBox.information(self, "Valor ajustado",
                                    f"O valor era maior que o saldo dos recibos marcados. "
                                    f"Ajustado para {dinheiro.formatar(valor - sobra)}.")
        self._atualizar_resumo()

    def _chave_em_uso(self) -> tuple[str, str, str]:
        if self.outra.isChecked():
            tipo = self.outra_tipo.currentData()
            return normalizar_chave(tipo, self.outra_chave.text()), tipo, self.outra_titular.text().strip()
        c = self.colab
        return c.chave_pix, c.tipo_chave, c.titular_pix

    def _copiar_chave(self) -> None:
        if self.colab:
            QGuiApplication.clipboard().setText(self.colab.chave_pix)
            self.bt_copiar_chave.setText("✓ Copiada")

    def _qr_code(self) -> None:
        try:
            total = sum(self._aplicacoes().values())
            if total <= 0:
                raise ValueError("Marque pelo menos um recibo com valor a abater.")
            chave, tipo, titular = self._chave_em_uso()
            if not titular:
                raise ValueError("Informe o titular da outra chave Pix.")
        except ValueError as e:
            QMessageBox.warning(self, "QR Code", str(e))
            return
        DialogoQrCode(chave, tipo, titular, total, "Reembolso").exec()

    def _registrar(self) -> None:
        try:
            aplic = self._aplicacoes()
        except ValueError as e:
            self.erro.setText(str(e))
            return
        total = sum(aplic.values())
        if total <= 0:
            self.erro.setText("Marque pelo menos um recibo com valor a abater.")
            return
        outra = None
        if self.outra.isChecked():
            outra = {"tipo_chave": self.outra_tipo.currentData(), "chave_pix": self.outra_chave.text(),
                     "titular_pix": self.outra_titular.text()}
        data_iso = self.data_pag.date().toString("yyyy-MM-dd")
        resp = QMessageBox.question(
            self, "Confirmar pagamento",
            f"Registrar Pix de {dinheiro.formatar(total)} para {self.colab.nome} em {_data_br(data_iso)}, "
            f"abatendo {len(aplic)} recibo(s)?\n\nConfirme que o Pix já foi feito no banco.")
        if resp != QMessageBox.StandardButton.Yes:
            return
        try:
            pagamentos.registrar(self.con, self.usuario, self.colab.id, aplic, data_iso,
                                 self.comprovante.caminho, outra)
        except (ValueError, PermissionError) as e:
            self.erro.setText(str(e))
            return
        QMessageBox.information(self, "Pagamento registrado",
                                f"✓ Pix de {dinheiro.formatar(total)} registrado para {self.colab.nome}.")
        self.atualizar()


class DialogoDetalhePagamento(QDialog):
    def __init__(self, con: sqlite3.Connection, t: pagamentos.Transferencia):
        super().__init__()
        self.setWindowTitle("Detalhe do pagamento")
        self.setMinimumWidth(520)
        layout = QVBoxLayout(self)
        layout.setContentsMargins(24, 20, 24, 20)
        situacao = (f"<b style='color:{tema.VERMELHO}'>Estornado:</b> {t.estorno_motivo}"
                    if t.estornado else f"<b style='color:{tema.VERDE}'>Válido</b>")
        layout.addWidget(_rotulo(
            f"<b style='font-size:14pt'>{dinheiro.formatar(t.valor_centavos)}</b> · {_data_br(t.data_pagamento)}"
            f"<br>{t.colaborador_nome}<br>Chave usada: {TIPOS_CHAVE.get(t.tipo_chave_usada, '')} "
            f"{formatar_chave(t.tipo_chave_usada, t.chave_pix_usada)} ({t.titular_usado})<br>{situacao}"))
        tabela = QTableWidget(0, 3)
        tabela.setHorizontalHeaderLabels(["Recibo", "Valor do recibo", "Abatido"])
        tabela.verticalHeader().setVisible(False)
        tabela.horizontalHeader().setSectionResizeMode(0, QHeaderView.ResizeMode.Stretch)
        for a in pagamentos.aplicacoes_da_transferencia(con, t.id):
            i = tabela.rowCount()
            tabela.insertRow(i)
            tabela.setItem(i, 0, _item(f"{_data_br(a['data_despesa'])} · {a['descricao']}"))
            tabela.setItem(i, 1, _item(dinheiro.formatar(a["valor_recibo"]), True))
            tabela.setItem(i, 2, _item(dinheiro.formatar(a["valor_centavos"]), True))
        layout.addWidget(tabela)
        fechar = QPushButton("Fechar")
        fechar.clicked.connect(self.accept)
        layout.addWidget(fechar, alignment=Qt.AlignmentFlag.AlignRight)


class TelaPagamentosConcluidos(QWidget):
    def __init__(self, con: sqlite3.Connection, usuario: Usuario):
        super().__init__()
        self.con, self.usuario = con, usuario
        self.itens: list[pagamentos.Transferencia] = []
        layout = QVBoxLayout(self)
        layout.setContentsMargins(32, 24, 32, 24)
        layout.addWidget(_rotulo("Pagamentos concluídos", "titulo"))
        layout.addWidget(_rotulo("Pix registrados. A planilha de retorno para os supervisores vem na "
                                 "próxima etapa.", "subtitulo"))

        filtros = QHBoxLayout()
        self.busca = QLineEdit()
        self.busca.setPlaceholderText("Buscar por colaborador…")
        self.busca.setClearButtonEnabled(True)
        self.estornados = QCheckBox("Mostrar estornados")
        filtros.addWidget(self.busca, 1)
        filtros.addWidget(self.estornados)
        layout.addLayout(filtros)

        acoes = QHBoxLayout()
        self.bt_detalhe = QPushButton("Ver detalhes")
        self.bt_comprov = QPushButton("Ver comprovante bancário")
        self.bt_estornar = QPushButton("Estornar…")
        for b in (self.bt_detalhe, self.bt_comprov, self.bt_estornar):
            b.setObjectName("secundario")
            acoes.addWidget(b)
        acoes.addStretch()
        layout.addLayout(acoes)

        self.tabela = QTableWidget(0, 6)
        self.tabela.setHorizontalHeaderLabels(["Data", "Colaborador", "Valor", "Recibos", "Chave usada", "Situação"])
        self.tabela.verticalHeader().setVisible(False)
        self.tabela.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        self.tabela.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection)
        self.tabela.setAlternatingRowColors(True)
        self.tabela.horizontalHeader().setStretchLastSection(True)
        layout.addWidget(self.tabela, 1)
        self.resumo = _rotulo("", "subtitulo")
        layout.addWidget(self.resumo)

        self.busca.textChanged.connect(self.atualizar)
        self.estornados.toggled.connect(self.atualizar)
        self.tabela.doubleClicked.connect(self._detalhe)
        self.bt_detalhe.clicked.connect(self._detalhe)
        self.bt_comprov.clicked.connect(self._comprovante)
        self.bt_estornar.clicked.connect(self._estornar)
        self.atualizar()

    def atualizar(self) -> None:
        self.itens = pagamentos.listar_transferencias(self.con, self.busca.text(),
                                                      incluir_estornadas=self.estornados.isChecked())
        self.tabela.setRowCount(0)
        for i, t in enumerate(self.itens):
            self.tabela.insertRow(i)
            cor = tema.VERMELHO if t.estornado else tema.VERDE
            for j, (v, direita) in enumerate((
                    (_data_br(t.data_pagamento), False), (t.colaborador_nome, False),
                    (dinheiro.formatar(t.valor_centavos), True), (str(t.qtd_recibos), True),
                    (formatar_chave(t.tipo_chave_usada, t.chave_pix_usada), False),
                    ("Estornado" if t.estornado else "Pago", False))):
                self.tabela.setItem(i, j, _item(v, direita, cor if j == 5 else None))
        self.tabela.resizeColumnsToContents()
        validos = [t for t in self.itens if not t.estornado]
        self.resumo.setText(f"{len(validos)} pagamento(s) · Total pago "
                            f"{dinheiro.formatar(sum(t.valor_centavos for t in validos))}")

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

    def _estornar(self) -> None:
        t = self._selecionado()
        if not t:
            return
        motivo, ok = QInputDialog.getText(
            self, "Estornar pagamento",
            f"Estornar o Pix de {dinheiro.formatar(t.valor_centavos)} para {t.colaborador_nome}?\n"
            "Os recibos voltam a ficar em aberto. Use só se o pagamento foi lançado errado.\n\nMotivo:")
        if not ok:
            return
        try:
            pagamentos.estornar(self.con, self.usuario, t.id, motivo)
        except (ValueError, PermissionError) as e:
            QMessageBox.warning(self, "Não foi possível estornar", str(e))
        self.atualizar()
