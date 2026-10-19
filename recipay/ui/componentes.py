"""Componentes reutilizados nas telas."""

import sqlite3
import uuid
from pathlib import Path

from PySide6.QtCore import QDate, Qt, QUrl, Signal
from PySide6.QtGui import QDesktopServices, QGuiApplication, QKeySequence, QPixmap, QShortcut
from PySide6.QtWidgets import (
    QComboBox,
    QCompleter,
    QDateEdit,
    QFileDialog,
    QFrame,
    QHBoxLayout,
    QLabel,
    QMessageBox,
    QPushButton,
    QVBoxLayout,
    QWidget,
)

from recipay import colaboradores, comprovantes, config
from recipay.ui import tema


def abrir_arquivo(caminho: Path, pai: QWidget | None = None) -> None:
    """Abre o arquivo no programa padrão do Windows (visualizador de fotos ou de PDF)."""
    if not caminho.is_file():
        QMessageBox.warning(pai, "Arquivo não encontrado", f"O arquivo não existe mais:\n{caminho}")
        return
    QDesktopServices.openUrl(QUrl.fromLocalFile(str(caminho)))


def campo_data(valor: QDate | None = None) -> QDateEdit:
    campo = QDateEdit(valor or QDate.currentDate())
    campo.setCalendarPopup(True)
    campo.setDisplayFormat("dd/MM/yyyy")
    campo.setMaximumDate(QDate.currentDate())
    return campo


class ComboColaborador(QComboBox):
    """Lista de colaboradores ativos, com busca digitando parte do nome."""

    def __init__(self, con: sqlite3.Connection):
        super().__init__()
        self.con = con
        self.setEditable(True)
        self.setInsertPolicy(QComboBox.InsertPolicy.NoInsert)
        self.lineEdit().setPlaceholderText("Digite para buscar o colaborador…")
        self.recarregar()

    def recarregar(self) -> None:
        atual = self.colaborador_id()
        self.blockSignals(True)
        self.clear()
        for c in colaboradores.listar(self.con):
            self.addItem(f"{c.nome} — {c.equipe}", c.id)
        completer = QCompleter([self.itemText(i) for i in range(self.count())], self)
        completer.setCaseSensitivity(Qt.CaseSensitivity.CaseInsensitive)
        completer.setFilterMode(Qt.MatchFlag.MatchContains)
        self.setCompleter(completer)
        self.blockSignals(False)
        self.selecionar(atual)

    def colaborador_id(self) -> str | None:
        indice = self.findText(self.currentText())
        return self.itemData(indice) if indice >= 0 else None

    def selecionar(self, colaborador_id: str | None) -> None:
        indice = self.findData(colaborador_id) if colaborador_id else -1
        self.setCurrentIndex(indice)
        if indice < 0:
            self.setEditText("")


class CampoComprovante(QFrame):
    """Anexar comprovante: botão, arrastar e soltar, ou colar imagem (Ctrl+V)."""

    alterado = Signal()

    def __init__(self, compacto: bool = False):
        super().__init__()
        self.caminho: str | None = None
        self.compacto = compacto
        self.setAcceptDrops(True)
        self.setObjectName("campoComprovante")
        self.setStyleSheet(
            f"QFrame#campoComprovante {{ border: 1px dashed {tema.BORDA}; border-radius: 6px; }}"
        )

        layout = QHBoxLayout(self) if compacto else QVBoxLayout(self)
        layout.setContentsMargins(*((6, 2, 4, 2) if compacto else (8, 6, 8, 6)))
        self.miniatura = QLabel()
        self.miniatura.setFixedHeight(0 if compacto else 120)
        self.miniatura.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.rotulo = QLabel("Sem arquivo" if compacto
                             else "Arraste o arquivo aqui, use o botão ou cole uma imagem (Ctrl+V)")
        self.rotulo.setObjectName("subtitulo")
        self.rotulo.setWordWrap(not compacto)

        botoes = QHBoxLayout()
        self.bt_anexar = QPushButton("Anexar…")
        self.bt_anexar.setObjectName("secundario")
        self.bt_anexar.clicked.connect(self._escolher)
        if compacto:
            self.bt_anexar.setStyleSheet("padding: 2px 10px;")
        botoes.addWidget(self.bt_anexar)
        if not compacto:
            bt_colar = QPushButton("Colar imagem")
            bt_colar.setObjectName("secundario")
            bt_colar.clicked.connect(self.colar)
            botoes.addWidget(bt_colar)
            botoes.addStretch()

        if compacto:
            layout.addWidget(self.rotulo, 1)
            layout.addLayout(botoes)
        else:
            layout.addWidget(self.miniatura)
            layout.addWidget(self.rotulo)
            layout.addLayout(botoes)
            QShortcut(QKeySequence.StandardKey.Paste, self, self.colar,
                      context=Qt.ShortcutContext.WidgetWithChildrenShortcut)

    # ----- origem do arquivo -----
    def _escolher(self) -> None:
        caminho, _ = QFileDialog.getOpenFileName(
            self, "Escolha o comprovante", str(Path.home()), comprovantes.FILTRO_ARQUIVOS
        )
        if caminho:
            self.definir(caminho)

    def colar(self) -> None:
        dados = QGuiApplication.clipboard().mimeData()
        if dados.hasUrls() and dados.urls()[0].isLocalFile():
            self.definir(dados.urls()[0].toLocalFile())
            return
        if dados.hasImage():
            imagem = QGuiApplication.clipboard().image()
            pasta = config.pasta_dados() / "temp"
            pasta.mkdir(exist_ok=True)
            destino = pasta / f"colado_{uuid.uuid4().hex}.png"
            imagem.save(str(destino), "PNG")
            self.definir(str(destino))
            return
        QMessageBox.information(self, "Nada para colar",
                                "A área de transferência não tem uma imagem ou arquivo.")

    def dragEnterEvent(self, evento) -> None:
        if evento.mimeData().hasUrls():
            evento.acceptProposedAction()

    def dropEvent(self, evento) -> None:
        url = evento.mimeData().urls()[0]
        if url.isLocalFile():
            self.definir(url.toLocalFile())

    # ----- estado -----
    def definir(self, caminho: str | None) -> None:
        if caminho and Path(caminho).suffix.lower() not in comprovantes.EXTENSOES_ACEITAS:
            QMessageBox.warning(self, "Formato não aceito", "Use foto (JPG, PNG) ou PDF.")
            return
        self.caminho = caminho
        if not caminho:
            self.rotulo.setText("Sem arquivo" if self.compacto
                                else "Arraste o arquivo aqui, use o botão ou cole uma imagem (Ctrl+V)")
            self.miniatura.clear()
        else:
            nome = Path(caminho).name
            if nome.startswith("colado_"):
                nome = "Imagem colada"
            self.rotulo.setText(f"✓ {nome}")
            self.rotulo.setStyleSheet(f"color: {tema.VERDE}")
            if not self.compacto:
                if Path(caminho).suffix.lower() == ".pdf":
                    self.miniatura.setText("📄 PDF")
                else:
                    pix = QPixmap(caminho)
                    self.miniatura.setPixmap(
                        pix.scaledToHeight(118, Qt.TransformationMode.SmoothTransformation))
        if not caminho:
            self.rotulo.setStyleSheet("")
        self.bt_anexar.setText("Trocar…" if caminho else "Anexar…")
        self.alterado.emit()
