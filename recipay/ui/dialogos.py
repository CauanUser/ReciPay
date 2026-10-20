"""Telas de entrada: login com lista de usuários, cadastro de usuário e restauração de backup."""

import sqlite3
import time
from pathlib import Path

from PySide6.QtCore import QSize, Qt
from PySide6.QtGui import QAction, QIcon, QPainter, QPixmap
from PySide6.QtWidgets import (
    QButtonGroup,
    QDialog,
    QFileDialog,
    QFormLayout,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QListWidget,
    QListWidgetItem,
    QMessageBox,
    QPushButton,
    QRadioButton,
    QVBoxLayout,
    QWidget,
)

from recipay import config, contas, db
from recipay.auth import PERFIS, SENHA_MINIMA, Usuario, autenticar, criar_usuario_local
from recipay.ui import tema

TENTATIVAS_ANTES_DE_ESPERAR = 5
ESPERA_SEGUNDOS = 30

DESCRICAO_PERFIS = {
    "SUPERVISOR": "Cadastra colaboradores e recibos, envia a planilha ao gestor e recebe o retorno.",
    "GESTOR": "Importa os recibos, registra os pagamentos Pix e envia o retorno aos supervisores.",
}


def _cabecalho(layout: QVBoxLayout, titulo: str, subtitulo: str) -> None:
    linha = QHBoxLayout()
    icone = config.caminho_recurso("recipay.png")
    if icone.is_file():
        logo = QLabel()
        logo.setPixmap(QPixmap(str(icone)).scaled(
            40, 40, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation))
        linha.addWidget(logo)
    rotulo = QLabel(titulo)
    rotulo.setObjectName("titulo")
    linha.addWidget(rotulo, 1)
    layout.addLayout(linha)
    if subtitulo:
        sub = QLabel(subtitulo)
        sub.setObjectName("subtitulo")
        sub.setWordWrap(True)
        layout.addWidget(sub)
    layout.addSpacing(8)


def _icone_texto(texto: str) -> QIcon:
    pix = QPixmap(QSize(20, 20))
    pix.fill(Qt.GlobalColor.transparent)
    p = QPainter(pix)
    p.drawText(pix.rect(), Qt.AlignmentFlag.AlignCenter, texto)
    p.end()
    return QIcon(pix)


class CampoSenha(QLineEdit):
    """Campo de senha com botão para mostrar/ocultar o que foi digitado."""

    def __init__(self, dica: str = ""):
        super().__init__()
        self.setEchoMode(QLineEdit.EchoMode.Password)
        self.setPlaceholderText(dica)
        self.acao_mostrar = QAction(_icone_texto("👁"), "Mostrar a senha", self)
        self.acao_mostrar.setToolTip("Mostrar a senha")
        self.acao_mostrar.triggered.connect(self.alternar)
        self.addAction(self.acao_mostrar, QLineEdit.ActionPosition.TrailingPosition)

    def alternar(self) -> None:
        oculto = self.echoMode() == QLineEdit.EchoMode.Password
        self.setEchoMode(QLineEdit.EchoMode.Normal if oculto else QLineEdit.EchoMode.Password)
        self.acao_mostrar.setToolTip("Ocultar a senha" if oculto else "Mostrar a senha")


class DialogoCadastro(QDialog):
    """Cadastra um usuário neste computador (cada um com seus próprios dados)."""

    def __init__(self, con: sqlite3.Connection, confirmar: bool = True):
        super().__init__()
        self.con = con
        self.confirmar_antes = confirmar
        self.usuario: Usuario | None = None
        self.setWindowTitle("Recipay — Cadastrar usuário")
        self.setMinimumWidth(480)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(28, 24, 28, 24)
        _cabecalho(layout, "Cadastrar usuário",
                   "Cada usuário tem os próprios dados e uma senha. O perfil não pode ser trocado depois.")

        form = QFormLayout()
        self.nome = QLineEdit()
        self.nome.setPlaceholderText("Seu nome completo")
        form.addRow("Nome", self.nome)
        layout.addLayout(form)

        layout.addWidget(QLabel("<b>Qual é a sua função?</b>"))
        self.grupo = QButtonGroup(self)
        self.opcoes: dict[str, QRadioButton] = {}
        for codigo, nome in PERFIS.items():
            opcao = QRadioButton(nome)
            opcao.setProperty("perfil", codigo)
            self.grupo.addButton(opcao)
            self.opcoes[codigo] = opcao
            layout.addWidget(opcao)
            explica = QLabel(DESCRICAO_PERFIS[codigo])
            explica.setObjectName("subtitulo")
            explica.setWordWrap(True)
            explica.setContentsMargins(24, 0, 0, 6)
            layout.addWidget(explica)

        form2 = QFormLayout()
        self.senha = CampoSenha(f"Mínimo de {SENHA_MINIMA} caracteres")
        self.confirmar = CampoSenha("Digite a senha de novo")
        form2.addRow("Senha", self.senha)
        form2.addRow("Confirmar senha", self.confirmar)
        layout.addLayout(form2)

        self.erro = QLabel("")
        self.erro.setObjectName("erro")
        self.erro.setWordWrap(True)
        layout.addWidget(self.erro)

        botoes = QHBoxLayout()
        botoes.addStretch()
        cancelar = QPushButton("Cancelar")
        cancelar.setObjectName("secundario")
        cancelar.clicked.connect(self.reject)
        botao = QPushButton("Cadastrar")
        botao.setDefault(True)
        botao.clicked.connect(self._salvar)
        self.confirmar.returnPressed.connect(self._salvar)
        botoes.addWidget(cancelar)
        botoes.addWidget(botao)
        layout.addLayout(botoes)
        self.nome.setFocus()

    def _salvar(self) -> None:
        escolhido = self.grupo.checkedButton()
        if not self.nome.text().strip():
            self.erro.setText("Informe o seu nome.")
            return
        if not escolhido:
            self.erro.setText("Escolha a sua função: Supervisor ou Gestor.")
            return
        if self.senha.text() != self.confirmar.text():
            self.erro.setText("As senhas não são iguais.")
            return
        perfil = escolhido.property("perfil")
        if self.confirmar_antes and QMessageBox.question(
                self, "Confirmar cadastro",
                f"Cadastrar {self.nome.text().strip()} como {PERFIS[perfil]}?\n\n"
                "O perfil não pode ser trocado depois.") != QMessageBox.StandardButton.Yes:
            return
        try:
            self.usuario = criar_usuario_local(self.con, self.nome.text(), perfil, self.senha.text())
        except ValueError as e:
            self.erro.setText(str(e))
            return
        self.accept()


# Nome antigo, mantido para compatibilidade.
DialogoConfiguracao = DialogoCadastro


class DialogoLogin(QDialog):
    """Escolha do usuário + senha. O perfil vem da conta, não de uma escolha na tela."""

    def __init__(self):
        super().__init__()
        self.conta: contas.Conta | None = None
        self.usuario: Usuario | None = None
        self.con: sqlite3.Connection | None = None
        self.itens: list[contas.Conta] = []
        self._falhas: dict[str, int] = {}
        self._bloqueio: dict[str, float] = {}
        self.setWindowTitle("Recipay — Entrar")
        self.setMinimumWidth(460)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(28, 24, 28, 24)
        _cabecalho(layout, "Recipay", "")
        self.quem = QLabel("<b>Quem está usando?</b>")
        layout.addWidget(self.quem)

        self.lista = QListWidget()
        self.lista.setIconSize(QSize(32, 32))
        self.lista.setMinimumHeight(150)
        self.lista.setStyleSheet(
            f"QListWidget {{ border: 1px solid {tema.BORDA}; border-radius: 6px; }}"
            f"QListWidget::item {{ padding: 8px; border-radius: 6px; }}"
            f"QListWidget::item:selected {{ background: {tema.AZUL_CLARO}; color: {tema.TEXTO}; }}")
        layout.addWidget(self.lista)

        self.vazio = QLabel("Nenhum usuário neste computador ainda.<br>"
                            "Clique em <b>Cadastrar novo usuário</b> para começar, "
                            "ou restaure um backup se estiver trocando de computador.")
        self.vazio.setObjectName("subtitulo")
        self.vazio.setWordWrap(True)
        layout.addWidget(self.vazio)

        self.area_senha = QWidget()
        s = QFormLayout(self.area_senha)
        s.setContentsMargins(0, 8, 0, 0)
        self.senha = CampoSenha("Sua senha")
        s.addRow("Senha", self.senha)
        layout.addWidget(self.area_senha)

        self.erro = QLabel("")
        self.erro.setObjectName("erro")
        self.erro.setWordWrap(True)
        layout.addWidget(self.erro)

        self.bt_entrar = QPushButton("Entrar")
        self.bt_entrar.setDefault(True)
        self.bt_entrar.clicked.connect(self._entrar)
        self.senha.returnPressed.connect(self._entrar)
        layout.addWidget(self.bt_entrar)

        extras = QHBoxLayout()
        self.bt_cadastrar = QPushButton("+ Cadastrar novo usuário")
        self.bt_cadastrar.clicked.connect(self._cadastrar)
        self.bt_restaurar = QPushButton("Restaurar de um backup…")
        self.bt_restaurar.setObjectName("secundario")
        self.bt_restaurar.setToolTip("Para trazer seus dados de outro computador.")
        self.bt_restaurar.clicked.connect(self._restaurar)
        extras.addWidget(self.bt_cadastrar)
        extras.addWidget(self.bt_restaurar)
        layout.addLayout(extras)

        self.lista.currentRowChanged.connect(self._trocou_usuario)
        self._recarregar(contas.ultima())

    def _trocou_usuario(self, _linha: int) -> None:
        self.erro.setStyleSheet("")
        self.erro.setText("")
        self.senha.clear()
        self.senha.setFocus()

    def _recarregar(self, selecionar_id: str | None = None) -> None:
        self.itens = contas.listar()
        self.lista.clear()
        icone = QIcon(str(config.caminho_recurso("recipay.png")))
        linha = 0
        for i, c in enumerate(self.itens):
            self.lista.addItem(QListWidgetItem(icone, f"{c.nome}\n{c.perfil_nome}"))
            if c.usuario_id == selecionar_id:
                linha = i
        tem = bool(self.itens)
        for w in (self.lista, self.quem, self.area_senha, self.bt_entrar):
            w.setVisible(tem)
        self.vazio.setVisible(not tem)
        # Sem usuários, "Cadastrar" vira o botão principal.
        self.bt_cadastrar.setObjectName("secundario" if tem else "")
        self.bt_cadastrar.style().unpolish(self.bt_cadastrar)
        self.bt_cadastrar.style().polish(self.bt_cadastrar)
        if tem:
            self.lista.setCurrentRow(linha)
            self.senha.setFocus()

    def _entrar(self) -> None:
        if self.lista.currentRow() < 0:
            self.erro.setText("Escolha o seu nome na lista.")
            return
        conta = self.itens[self.lista.currentRow()]
        restante = int(self._bloqueio.get(conta.usuario_id, 0) - time.monotonic())
        if restante > 0:
            self.erro.setText(f"Muitas tentativas. Aguarde {restante} segundos.")
            return
        if not self.senha.text():
            self.erro.setText("Digite a sua senha.")
            return
        config.definir_conta(conta.pasta)
        con = db.conectar(config.caminho_banco())
        try:
            usuario = autenticar(con, conta.perfil, self.senha.text())
        except ValueError as e:
            con.close()
            falhas = self._falhas.get(conta.usuario_id, 0) + 1
            if falhas >= TENTATIVAS_ANTES_DE_ESPERAR:
                falhas = 0
                self._bloqueio[conta.usuario_id] = time.monotonic() + ESPERA_SEGUNDOS
            self._falhas[conta.usuario_id] = falhas
            self.senha.clear()
            self.erro.setStyleSheet("")
            self.erro.setText(str(e))
            return
        self.conta, self.usuario, self.con = conta, usuario, con
        contas.lembrar(conta)
        self.accept()

    def _cadastrar(self) -> None:
        pasta = contas.nova_pasta()
        config.definir_conta(pasta)
        con = db.conectar(config.caminho_banco())
        dialogo = DialogoCadastro(con)
        aceito = dialogo.exec() == QDialog.DialogCode.Accepted
        con.close()
        if not aceito:
            contas.descartar_pasta_vazia(pasta)
            return
        self._recarregar(dialogo.usuario.id)
        self.erro.setStyleSheet(f"color: {tema.VERDE}")
        self.erro.setText(f"✓ {dialogo.usuario.nome} cadastrado. Digite a senha para entrar.")

    def _restaurar(self) -> None:
        from recipay import backup
        from recipay.ui.tela_backup import DialogoSenhaBackup  # evita import circular

        caminho, _ = QFileDialog.getOpenFileName(
            self, "Escolha o arquivo de backup", str(Path.home()), f"Backup do Recipay (*{backup.EXTENSAO})")
        if not caminho:
            return
        senha = DialogoSenhaBackup("Senha do backup", confirmar=False)
        if senha.exec() != QDialog.DialogCode.Accepted:
            return
        try:
            resumo = backup.conferir(caminho, senha.senha.text())
        except (ValueError, OSError) as e:
            QMessageBox.warning(self, "Não foi possível abrir o backup", str(e))
            return
        existente = next((c for c in self.itens if c.usuario_id == resumo["id"]), None)
        if existente:
            QMessageBox.information(
                self, "Usuário já existe",
                f"{existente.nome} já está neste computador. Para substituir os dados dele pelos do backup, "
                "entre com a senha dele e use o menu Backup > Restaurar.")
            return
        if QMessageBox.question(
                self, "Restaurar backup",
                f"Restaurar {resumo['nome']} ({PERFIS.get(resumo['perfil'], '')}) com {resumo['recibos']} "
                f"recibo(s) e {resumo['comprovantes']} comprovante(s) neste computador?"
        ) != QMessageBox.StandardButton.Yes:
            return
        pasta = contas.nova_pasta()
        config.definir_conta(pasta)
        try:
            backup.restaurar(caminho, senha.senha.text())
        except (ValueError, OSError) as e:
            contas.descartar_pasta_vazia(pasta)
            QMessageBox.warning(self, "Erro ao restaurar", str(e))
            return
        self._recarregar(resumo["id"])
        self.erro.setStyleSheet(f"color: {tema.VERDE}")
        self.erro.setText(f"✓ {resumo['nome']} restaurado. Entre com a senha que essa conta já usava.")
