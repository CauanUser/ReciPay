"""Ponto de entrada do programa."""

import sys

from PySide6.QtCore import QTimer
from PySide6.QtGui import QIcon
from PySide6.QtWidgets import QApplication, QDialog

from recipay import config, contas
from recipay.ui.dialogos import DialogoLogin
from recipay.ui.janela_principal import JanelaPrincipal
from recipay.ui.tema import ESTILO


def main() -> int:
    app = QApplication(sys.argv)
    app.setApplicationName("Recipay")
    app.setStyle("Fusion")
    app.setStyleSheet(ESTILO)
    icone = config.caminho_recurso("recipay.png")
    if icone.is_file():
        app.setWindowIcon(QIcon(str(icone)))
    # O programa só fecha quando o usuário fecha a janela principal ou cancela o login.
    app.setQuitOnLastWindowClosed(False)

    contas.migrar_instalacao_antiga()
    estado: dict = {"janela": None, "con": None}

    def fechar_conexao() -> None:
        if estado["con"] is not None:
            try:
                estado["con"].close()
            except Exception:
                pass
            estado["con"] = None

    def abrir_login() -> None:
        fechar_conexao()
        login = DialogoLogin()
        if login.exec() != QDialog.DialogCode.Accepted:
            app.quit()
            return
        estado["con"] = login.con
        config.limpar_temporarios()
        janela = JanelaPrincipal(login.con, login.usuario)
        janela.sair.connect(abrir_login)
        janela.fechada.connect(app.quit)
        estado["janela"] = janela
        janela.show()

    QTimer.singleShot(0, abrir_login)
    codigo = app.exec()
    fechar_conexao()
    return codigo
