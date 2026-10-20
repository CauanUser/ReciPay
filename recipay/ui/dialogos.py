"""Telas de primeira configuração e de login."""

import sqlite3
import time

from PySide6.QtCore import Qt
from PySide6.QtWidgets import (
    QComboBox,
    QDialog,
    QFormLayout,
    QLabel,
    QLineEdit,
    QPushButton,
    QVBoxLayout,
)

from recipay.auth import PERFIS, SENHA_MINIMA, Usuario, autenticar, criar_usuario_local

TENTATIVAS_ANTES_DE_ESPERAR = 5
ESPERA_SEGUNDOS = 30


def _combo_perfil() -> QComboBox:
    combo = QComboBox()
    for codigo, nome in PERFIS.items():
        combo.addItem(nome, codigo)
    return combo


def _cabecalho(layout: QVBoxLayout, titulo: str, subtitulo: str) -> None:
    rotulo = QLabel(titulo)
    rotulo.setObjectName("titulo")
    layout.addWidget(rotulo)
    sub = QLabel(subtitulo)
    sub.setObjectName("subtitulo")
    sub.setWordWrap(True)
    layout.addWidget(sub)
    layout.addSpacing(8)


class DialogoConfiguracao(QDialog):
    """Primeiro uso: define quem usa este computador e com qual perfil."""

    def __init__(self, con: sqlite3.Connection):
        super().__init__()
        self.con = con
        self.usuario: Usuario | None = None
        self.restaurado = False
        self.setWindowTitle("Recipay — Configuração inicial")
        self.setMinimumWidth(420)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(28, 24, 28, 24)
        _cabecalho(
            layout,
            "Bem-vindo ao Recipay",
            "Configure este computador. O perfil escolhido aqui fica gravado "
            "e define o que este usuário pode fazer.",
        )

        form = QFormLayout()
        self.nome = QLineEdit()
        self.nome.setPlaceholderText("Seu nome completo")
        self.perfil = _combo_perfil()
        self.senha = QLineEdit()
        self.senha.setEchoMode(QLineEdit.EchoMode.Password)
        self.senha.setPlaceholderText(f"Mínimo de {SENHA_MINIMA} caracteres")
        self.confirmar = QLineEdit()
        self.confirmar.setEchoMode(QLineEdit.EchoMode.Password)
        form.addRow("Nome", self.nome)
        form.addRow("Perfil", self.perfil)
        form.addRow("Senha", self.senha)
        form.addRow("Confirmar senha", self.confirmar)
        layout.addLayout(form)

        self.erro = QLabel("")
        self.erro.setObjectName("erro")
        self.erro.setWordWrap(True)
        layout.addWidget(self.erro)

        botao = QPushButton("Concluir configuração")
        botao.clicked.connect(self._salvar)
        self.confirmar.returnPressed.connect(self._salvar)
        layout.addWidget(botao)

        restaurar = QPushButton("Trocou de computador? Restaurar de um backup…")
        restaurar.setObjectName("secundario")
        restaurar.clicked.connect(self._restaurar)
        layout.addWidget(restaurar)

    def _restaurar(self) -> None:
        from recipay.ui.tela_backup import escolher_e_restaurar  # evita import circular
        if escolher_e_restaurar(self, self.con):
            self.restaurado = True
            self.reject()

    def _salvar(self) -> None:
        if self.senha.text() != self.confirmar.text():
            self.erro.setText("As senhas não são iguais.")
            return
        try:
            self.usuario = criar_usuario_local(
                self.con, self.nome.text(), self.perfil.currentData(), self.senha.text()
            )
        except ValueError as e:
            self.erro.setText(str(e))
            return
        self.accept()


class DialogoLogin(QDialog):
    """Login: escolha do perfil + senha. A escolha não concede permissão."""

    def __init__(self, con: sqlite3.Connection, nome_usuario: str):
        super().__init__()
        self.con = con
        self.usuario: Usuario | None = None
        self._falhas = 0
        self._bloqueado_ate = 0.0
        self.setWindowTitle("Recipay — Entrar")
        self.setMinimumWidth(380)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(28, 24, 28, 24)
        _cabecalho(layout, "Recipay", f"Olá, {nome_usuario}. Escolha o perfil e digite sua senha.")

        form = QFormLayout()
        self.perfil = _combo_perfil()
        self.senha = QLineEdit()
        self.senha.setEchoMode(QLineEdit.EchoMode.Password)
        form.addRow("Perfil", self.perfil)
        form.addRow("Senha", self.senha)
        layout.addLayout(form)

        self.erro = QLabel("")
        self.erro.setObjectName("erro")
        self.erro.setWordWrap(True)
        layout.addWidget(self.erro)

        botao = QPushButton("Entrar")
        botao.clicked.connect(self._entrar)
        self.senha.returnPressed.connect(self._entrar)
        layout.addWidget(botao)
        self.senha.setFocus(Qt.FocusReason.OtherFocusReason)

    def _entrar(self) -> None:
        restante = int(self._bloqueado_ate - time.monotonic())
        if restante > 0:
            self.erro.setText(f"Muitas tentativas. Aguarde {restante} segundos.")
            return
        try:
            self.usuario = autenticar(self.con, self.perfil.currentData(), self.senha.text())
        except ValueError as e:
            self._falhas += 1
            if self._falhas >= TENTATIVAS_ANTES_DE_ESPERAR:
                self._falhas = 0
                self._bloqueado_ate = time.monotonic() + ESPERA_SEGUNDOS
            self.senha.clear()
            self.erro.setText(str(e))
            return
        self.accept()
