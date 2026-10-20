"""Tela de Backup (os dois perfis) e diálogo de senha."""

import sqlite3
from datetime import date
from pathlib import Path

from PySide6.QtCore import Qt
from PySide6.QtWidgets import (
    QApplication,
    QDialog,
    QFileDialog,
    QFormLayout,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QMessageBox,
    QPushButton,
    QVBoxLayout,
    QWidget,
)

from recipay import backup
from recipay.auth import PERFIS, Usuario
from recipay.ui import tema


def _rotulo(texto: str, nome: str = "") -> QLabel:
    r = QLabel(texto)
    if nome:
        r.setObjectName(nome)
    r.setWordWrap(True)
    return r


class DialogoSenhaBackup(QDialog):
    def __init__(self, titulo: str, confirmar: bool):
        super().__init__()
        self.setWindowTitle(titulo)
        self.setMinimumWidth(420)
        layout = QVBoxLayout(self)
        layout.setContentsMargins(24, 20, 24, 20)
        if confirmar:
            layout.addWidget(_rotulo(
                "Crie uma senha para proteger o backup. <b>Anote em lugar seguro</b>: sem ela não é "
                "possível restaurar, e ninguém consegue recuperá-la.", "subtitulo"))
        form = QFormLayout()
        self.senha = QLineEdit()
        self.senha.setEchoMode(QLineEdit.EchoMode.Password)
        form.addRow("Senha do backup", self.senha)
        self.repetir = QLineEdit()
        self.repetir.setEchoMode(QLineEdit.EchoMode.Password)
        if confirmar:
            form.addRow("Repita a senha", self.repetir)
        layout.addLayout(form)
        self.erro = _rotulo("", "erro")
        layout.addWidget(self.erro)
        botoes = QHBoxLayout()
        botoes.addStretch()
        cancelar = QPushButton("Cancelar")
        cancelar.setObjectName("secundario")
        cancelar.clicked.connect(self.reject)
        ok = QPushButton("Continuar")
        ok.clicked.connect(lambda: self._ok(confirmar))
        botoes.addWidget(cancelar)
        botoes.addWidget(ok)
        layout.addLayout(botoes)

    def _ok(self, confirmar: bool) -> None:
        if confirmar and len(self.senha.text()) < backup.SENHA_MINIMA:
            self.erro.setText(f"Use pelo menos {backup.SENHA_MINIMA} caracteres.")
            return
        if confirmar and self.senha.text() != self.repetir.text():
            self.erro.setText("As senhas não são iguais.")
            return
        self.accept()


def escolher_e_restaurar(pai: QWidget, con: sqlite3.Connection) -> bool:
    """Fluxo completo de restauração. Fecha a conexão e devolve True se restaurou."""
    caminho, _ = QFileDialog.getOpenFileName(
        pai, "Escolha o arquivo de backup", str(Path.home()), f"Backup do Recipay (*{backup.EXTENSAO})")
    if not caminho:
        return False
    dlg = DialogoSenhaBackup("Senha do backup", confirmar=False)
    if dlg.exec() != QDialog.DialogCode.Accepted:
        return False
    try:
        resumo = backup.conferir(caminho, dlg.senha.text())
    except (ValueError, OSError) as e:
        QMessageBox.warning(pai, "Não foi possível abrir o backup", str(e))
        return False
    resp = QMessageBox.question(
        pai, "Restaurar backup",
        f"Backup de {resumo['nome']} ({PERFIS.get(resumo['perfil'], resumo['perfil'])}): "
        f"{resumo['recibos']} recibo(s), {resumo['colaboradores']} colaborador(es), "
        f"{resumo['comprovantes']} comprovante(s).\n\n"
        "TODOS os dados atuais deste computador serão substituídos pelos do backup "
        "(os atuais ficam guardados numa pasta de segurança). O programa vai fechar ao final.\n\n"
        "Deseja continuar?")
    if resp != QMessageBox.StandardButton.Yes:
        return False
    con.close()
    try:
        backup.restaurar(caminho, dlg.senha.text())
    except (ValueError, OSError) as e:
        QMessageBox.critical(pai, "Erro ao restaurar",
                             f"{e}\n\nOs dados anteriores estão guardados na pasta de segurança.")
        QApplication.quit()
        return False
    QMessageBox.information(pai, "Backup restaurado", "✓ Dados restaurados. Abra o Recipay de novo.")
    return True


class TelaBackup(QWidget):
    def __init__(self, con: sqlite3.Connection, usuario: Usuario):
        super().__init__()
        self.con, self.usuario = con, usuario
        layout = QVBoxLayout(self)
        layout.setContentsMargins(32, 24, 32, 24)
        layout.addWidget(_rotulo("Backup", "titulo"))
        layout.addWidget(_rotulo(
            "Os dados do Recipay ficam só neste computador. Se ele quebrar ou for trocado, o backup é a "
            "única forma de recuperar recibos, pagamentos e comprovantes. Guarde o arquivo fora deste PC "
            "(OneDrive, Google Drive, pendrive).", "subtitulo"))
        self.situacao = _rotulo("")
        layout.addWidget(self.situacao)

        bt_exportar = QPushButton("Fazer backup agora…")
        bt_exportar.clicked.connect(self._exportar)
        layout.addWidget(bt_exportar)
        layout.addSpacing(24)
        layout.addWidget(_rotulo("<b>Restaurar</b>: use só ao trocar de computador ou se os dados se "
                                 "perderam. Substitui tudo o que existe neste PC.", "subtitulo"))
        bt_restaurar = QPushButton("Restaurar de um backup…")
        bt_restaurar.setObjectName("secundario")
        bt_restaurar.clicked.connect(self._restaurar)
        layout.addWidget(bt_restaurar)
        layout.addStretch()
        for b in (bt_exportar, bt_restaurar):
            b.setMaximumWidth(260)
        self.atualizar()

    def atualizar(self) -> None:
        ultimo = backup.ultimo_backup(self.con)
        if ultimo is None:
            texto, cor = "Nenhum backup feito ainda neste computador.", tema.LARANJA
        else:
            dias = (date.today() - ultimo.date()).days
            texto = f"Último backup: {ultimo:%d/%m/%Y %H:%M}" + (f" (há {dias} dia(s))" if dias else " (hoje)")
            cor = tema.LARANJA if dias >= backup.DIAS_LEMBRETE else tema.VERDE
        self.situacao.setText(f"<b style='color:{cor}'>{texto}</b>")

    def _exportar(self) -> None:
        dlg = DialogoSenhaBackup("Senha do backup", confirmar=True)
        if dlg.exec() != QDialog.DialogCode.Accepted:
            return
        nome = f"recipay_{self.usuario.perfil.lower()}_{date.today().isoformat()}{backup.EXTENSAO}"
        caminho, _ = QFileDialog.getSaveFileName(
            self, "Salvar backup", str(Path.home() / "Documents" / nome), f"Backup do Recipay (*{backup.EXTENSAO})")
        if not caminho:
            return
        if not caminho.endswith(backup.EXTENSAO):
            caminho += backup.EXTENSAO
        QApplication.setOverrideCursor(Qt.CursorShape.WaitCursor)
        try:
            tamanho = backup.exportar(self.con, caminho, dlg.senha.text())
        except (ValueError, OSError) as e:
            QApplication.restoreOverrideCursor()
            QMessageBox.warning(self, "Não foi possível fazer o backup", str(e))
            return
        QApplication.restoreOverrideCursor()
        QMessageBox.information(self, "Backup feito",
                                f"✓ Backup salvo ({tamanho / 1024 / 1024:.1f} MB):\n{caminho}\n\n"
                                "Copie este arquivo para fora deste computador.")
        self.atualizar()

    def _restaurar(self) -> None:
        if escolher_e_restaurar(self, self.con):
            QApplication.quit()
