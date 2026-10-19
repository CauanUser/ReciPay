"""Janela principal: cabeçalho, menu lateral e área das telas."""

from PySide6.QtCore import Signal
from PySide6.QtWidgets import (
    QFrame,
    QHBoxLayout,
    QLabel,
    QListWidget,
    QMainWindow,
    QPushButton,
    QStackedWidget,
    QVBoxLayout,
    QWidget,
)

from recipay import __version__
from recipay.auth import Usuario

# Telas de cada perfil (seção 6 da especificação).
TELAS = {
    "SUPERVISOR": [
        "Início",
        "Recibos",
        "Cadastrar recibos",
        "Colaboradores",
        "Exportar recibos",
        "Retorno e histórico",
    ],
    "GESTOR": [
        "Início",
        "Importar arquivo",
        "Pagamentos pendentes",
        "Realizar pagamento",
        "Pagamentos concluídos",
        "Colaboradores",
    ],
}


class TelaEmConstrucao(QWidget):
    def __init__(self, titulo: str):
        super().__init__()
        layout = QVBoxLayout(self)
        layout.setContentsMargins(32, 24, 32, 24)
        rotulo = QLabel(titulo)
        rotulo.setObjectName("titulo")
        layout.addWidget(rotulo)
        aviso = QLabel("Esta tela ainda será construída.")
        aviso.setObjectName("subtitulo")
        layout.addWidget(aviso)
        layout.addStretch()


class JanelaPrincipal(QMainWindow):
    sair = Signal()  # botão "Sair": volta para o login
    fechada = Signal()  # janela fechada pelo "X": encerra o programa

    def __init__(self, usuario: Usuario):
        super().__init__()
        self.usuario = usuario
        self._saindo = False
        self.setWindowTitle(f"Recipay {__version__} — {usuario.perfil_nome}")
        self.setMinimumSize(1280, 720)

        central = QWidget()
        raiz = QVBoxLayout(central)
        raiz.setContentsMargins(0, 0, 0, 0)
        raiz.setSpacing(0)

        # Cabeçalho azul com usuário e botão Sair.
        cabecalho = QFrame()
        cabecalho.setObjectName("cabecalho")
        linha = QHBoxLayout(cabecalho)
        linha.setContentsMargins(20, 10, 20, 10)
        marca = QLabel("<b style='font-size:14pt'>Recipay</b>")
        linha.addWidget(marca)
        linha.addStretch()
        linha.addWidget(QLabel(f"{usuario.nome} · {usuario.perfil_nome}"))
        botao_sair = QPushButton("Sair")
        botao_sair.clicked.connect(self._sair)
        linha.addWidget(botao_sair)
        raiz.addWidget(cabecalho)

        # Menu lateral + telas.
        corpo = QHBoxLayout()
        corpo.setContentsMargins(0, 0, 0, 0)
        corpo.setSpacing(0)
        self.menu = QListWidget()
        self.menu.setObjectName("menu")
        self.menu.setFixedWidth(230)
        self.telas = QStackedWidget()
        for titulo in TELAS[usuario.perfil]:
            self.menu.addItem(titulo)
            self.telas.addWidget(TelaEmConstrucao(titulo))
        self.menu.currentRowChanged.connect(self.telas.setCurrentIndex)
        self.menu.setCurrentRow(0)
        corpo.addWidget(self.menu)
        corpo.addWidget(self.telas, 1)
        raiz.addLayout(corpo, 1)

        self.setCentralWidget(central)

    def _sair(self) -> None:
        self._saindo = True
        self.close()
        self.sair.emit()

    def closeEvent(self, evento) -> None:
        if not self._saindo:
            self.fechada.emit()
        super().closeEvent(evento)
