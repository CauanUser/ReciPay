"""Ponto de entrada do programa."""

import sys

from PySide6.QtCore import QTimer
from PySide6.QtWidgets import QApplication, QDialog

from recipay import config, db
from recipay.auth import usuario_local
from recipay.ui.dialogos import DialogoConfiguracao, DialogoLogin
from recipay.ui.janela_principal import JanelaPrincipal
from recipay.ui.tema import ESTILO


def main() -> int:
    app = QApplication(sys.argv)
    app.setApplicationName("Recipay")
    app.setStyle("Fusion")
    app.setStyleSheet(ESTILO)
    # O programa só fecha quando o usuário fecha a janela principal ou cancela o login.
    app.setQuitOnLastWindowClosed(False)

    con = db.conectar(config.caminho_banco())

    usuario = usuario_local(con)
    if usuario is None:
        configuracao = DialogoConfiguracao(con)
        if configuracao.exec() != QDialog.DialogCode.Accepted:
            return 0
        usuario = configuracao.usuario

    estado: dict[str, JanelaPrincipal | None] = {"janela": None}

    def abrir_login() -> None:
        login = DialogoLogin(con, usuario.nome)
        if login.exec() != QDialog.DialogCode.Accepted:
            app.quit()
            return
        janela = JanelaPrincipal(con, login.usuario)
        janela.sair.connect(abrir_login)
        janela.fechada.connect(app.quit)
        estado["janela"] = janela
        janela.show()

    QTimer.singleShot(0, abrir_login)
    codigo = app.exec()
    con.close()
    return codigo
