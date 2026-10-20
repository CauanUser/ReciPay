"""Janela principal: cabeçalho, menu lateral e área das telas."""

import sqlite3

from PySide6.QtCore import Signal
from PySide6.QtGui import QActionGroup
from PySide6.QtWidgets import (
    QApplication,
    QFrame,
    QHBoxLayout,
    QLabel,
    QListWidget,
    QMainWindow,
    QMenu,
    QPushButton,
    QStackedWidget,
    QVBoxLayout,
    QWidget,
)

from recipay import __version__
from recipay.auth import Usuario
from recipay.ui import tema
from recipay.ui.tela_backup import TelaBackup
from recipay.ui.tela_colaboradores import TelaColaboradores
from recipay.ui.tela_inicio import TelaInicio
from recipay.ui.tela_pagamento import TelaPagamentosConcluidos, TelaRealizarPagamento
from recipay.ui.tela_recibos import TelaCadastrarRecibos, TelaRecibos
from recipay.ui.tela_relatorios import TelaRelatorios
from recipay.ui.tela_retorno import TelaRetornoHistorico
from recipay.ui.tela_troca import TelaExportarRecibos, TelaImportarArquivo, TelaPagamentosPendentes

# Telas já construídas. As demais aparecem como "em construção".
TELAS_PRONTAS = {
    "Início": TelaInicio,
    "Backup": TelaBackup,
    "Relatórios": TelaRelatorios,
    "Colaboradores": TelaColaboradores,
    "Recibos": TelaRecibos,
    "Cadastrar recibos": TelaCadastrarRecibos,
    "Exportar recibos": TelaExportarRecibos,
    "Importar arquivo": TelaImportarArquivo,
    "Pagamentos pendentes": TelaPagamentosPendentes,
    "Realizar pagamento": TelaRealizarPagamento,
    "Pagamentos concluídos": TelaPagamentosConcluidos,
    "Retorno e histórico": TelaRetornoHistorico,
}

# Telas de cada perfil (seção 6 da especificação).
TELAS = {
    "SUPERVISOR": [
        "Início",
        "Recibos",
        "Cadastrar recibos",
        "Colaboradores",
        "Exportar recibos",
        "Retorno e histórico",
        "Relatórios",
        "Backup",
    ],
    "GESTOR": [
        "Início",
        "Importar arquivo",
        "Pagamentos pendentes",
        "Realizar pagamento",
        "Pagamentos concluídos",
        "Colaboradores",
        "Relatórios",
        "Backup",
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
    recriar = Signal(str)  # aparência mudou: recriar a janela na mesma tela
    fechada = Signal()  # janela fechada pelo "X": encerra o programa

    def __init__(self, con: sqlite3.Connection, usuario: Usuario):
        super().__init__()
        self.con = con
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
        self.bt_aparencia = QPushButton("Aparência")
        self.bt_aparencia.setToolTip("Modo escuro e tamanho da letra")
        self.bt_aparencia.setMenu(self._menu_aparencia())
        linha.addWidget(self.bt_aparencia)
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
            fabrica = TELAS_PRONTAS.get(titulo)
            self.telas.addWidget(fabrica(con, usuario) if fabrica else TelaEmConstrucao(titulo))
        self.menu.currentRowChanged.connect(self._abrir_tela)
        self._ligar_telas()
        self.menu.setCurrentRow(0)
        corpo.addWidget(self.menu)
        corpo.addWidget(self.telas, 1)
        raiz.addLayout(corpo, 1)

        self.setCentralWidget(central)

    def _tela(self, titulo: str):
        titulos = TELAS[self.usuario.perfil]
        return (titulos.index(titulo), self.telas.widget(titulos.index(titulo))) if titulo in titulos else (-1, None)

    def ir_para(self, titulo: str) -> None:
        indice, _ = self._tela(titulo)
        if indice >= 0:
            self.menu.setCurrentRow(indice)

    def _ligar_telas(self) -> None:
        """Atalhos entre telas (Início e fila de pendentes → pagamento)."""
        for i in range(self.telas.count()):
            tela = self.telas.widget(i)
            if hasattr(tela, "ir_para"):
                tela.ir_para = self.ir_para
        _, pendentes = self._tela("Pagamentos pendentes")
        indice, pagamento = self._tela("Realizar pagamento")
        if pendentes and pagamento:
            def ir(colaborador_id: str) -> None:
                self.menu.setCurrentRow(indice)
                pagamento.selecionar_colaborador(colaborador_id)
            pendentes.ir_para_pagamento = ir

    def _abrir_tela(self, indice: int) -> None:
        self.telas.setCurrentIndex(indice)
        tela = self.telas.widget(indice)
        if hasattr(tela, "atualizar"):
            tela.atualizar()  # mostra dados novos cadastrados em outras telas

    def _menu_aparencia(self) -> QMenu:
        menu = QMenu(self)
        grupo_modo = QActionGroup(menu)
        for codigo, nome in (("claro", "Modo claro"), ("escuro", "Modo escuro")):
            acao = menu.addAction(nome)
            acao.setCheckable(True)
            acao.setChecked(tema.modo == codigo)
            grupo_modo.addAction(acao)
            acao.triggered.connect(lambda _=False, c=codigo: self._mudar_aparencia(modo=c))
        menu.addSeparator()
        grupo_letra = QActionGroup(menu)
        for codigo, nome in tema.NOMES_TAMANHOS.items():
            acao = menu.addAction(f"Letra {nome.lower()}")
            acao.setCheckable(True)
            acao.setChecked(tema.tamanho == codigo)
            grupo_letra.addAction(acao)
            acao.triggered.connect(lambda _=False, c=codigo: self._mudar_aparencia(tamanho=c))
        return menu

    def _mudar_aparencia(self, modo: str | None = None, tamanho: str | None = None) -> None:
        tema.aplicar(QApplication.instance(), modo, tamanho)
        self.recriar.emit(TELAS[self.usuario.perfil][self.menu.currentRow()])

    def fechar_sem_sair(self) -> None:
        """Fecha esta janela sem encerrar o programa (usado ao recriar)."""
        self._saindo = True
        self.close()

    def _sair(self) -> None:
        self._saindo = True
        self.close()
        self.sair.emit()

    def closeEvent(self, evento) -> None:
        if not self._saindo:
            self.fechada.emit()
        super().closeEvent(evento)
