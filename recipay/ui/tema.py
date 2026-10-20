"""Identidade visual: branco e azul, verde para sucesso. Modo claro/escuro e tamanho de letra.

As cores ficam em variáveis deste módulo (tema.AZUL, tema.TEXTO…). As telas leem essas
variáveis ao serem criadas; ao trocar o tema, a janela principal é recriada.
"""

import json

from recipay import config

CLARO = {
    "AZUL": "#1E5AA8", "AZUL_ESCURO": "#174A8B", "AZUL_CLARO": "#E8F0FB",
    "FUNDO": "#FFFFFF", "SUPERFICIE": "#FFFFFF", "FUNDO_MENU": "#F4F7FC",
    "BORDA": "#D5DEEA", "TEXTO": "#1F2933", "TEXTO_SUAVE": "#5B6B7F", "TEXTO_BOTAO": "#FFFFFF",
    "VERDE": "#2E7D32", "LARANJA": "#B86E00", "VERMELHO": "#C62828",
    "AVISO_FUNDO": "#FFF4E0", "ERRO_FUNDO": "#FDECEA", "GRADE": "#E6EBF2",
}
ESCURO = {
    "AZUL": "#3F82DA", "AZUL_ESCURO": "#2F6BBD", "AZUL_CLARO": "#24344A",
    "FUNDO": "#1B1F26", "SUPERFICIE": "#262C36", "FUNDO_MENU": "#20252D",
    "BORDA": "#3A4350", "TEXTO": "#E6EAF0", "TEXTO_SUAVE": "#A3AEBD", "TEXTO_BOTAO": "#FFFFFF",
    "VERDE": "#5CC46A", "LARANJA": "#F0A23A", "VERMELHO": "#F07070",
    "AVISO_FUNDO": "#3A2E17", "ERRO_FUNDO": "#3D2224", "GRADE": "#323A46",
}
TAMANHOS = {"normal": 10, "grande": 12, "muito_grande": 14}
NOMES_TAMANHOS = {"normal": "Normal", "grande": "Grande", "muito_grande": "Muito grande"}

modo = "claro"
tamanho = "normal"
globals().update(CLARO)

_MODELO = """
QWidget {{
    font-family: "Segoe UI", Arial, sans-serif;
    font-size: {fonte}pt;
    color: {TEXTO};
    background: {FUNDO};
}}
QLabel#titulo {{
    font-size: {titulo}pt;
    font-weight: 600;
    color: {AZUL};
}}
QLabel#subtitulo {{
    color: {TEXTO_SUAVE};
}}
QLabel#erro {{
    color: {VERMELHO};
}}
QLabel#sucesso {{
    color: {VERDE};
}}
QLineEdit, QComboBox {{
    border: 1px solid {BORDA};
    border-radius: 6px;
    padding: 6px 8px;
    min-height: 20px;
}}
QLineEdit:focus, QComboBox:focus {{
    border: 1px solid {AZUL};
}}
QPushButton {{
    background: {AZUL};
    color: {TEXTO_BOTAO};
    border: none;
    border-radius: 6px;
    padding: 8px 16px;
    font-weight: 600;
}}
QPushButton:hover {{
    background: {AZUL_ESCURO};
}}
QPushButton:disabled {{
    background: {BORDA};
    color: {TEXTO_SUAVE};
}}
QPushButton#secundario {{
    background: {FUNDO};
    color: {AZUL};
    border: 1px solid {AZUL};
}}
QPushButton#secundario:hover {{
    background: {AZUL_CLARO};
}}
QListWidget#menu {{
    background: {FUNDO_MENU};
    border: none;
    border-right: 1px solid {BORDA};
    padding-top: 8px;
}}
QListWidget#menu::item {{
    background: transparent;
    padding: 10px 16px;
    border-radius: 6px;
    margin: 2px 8px;
}}
QListWidget#menu::item:hover {{
    background: {AZUL_CLARO};
}}
QListWidget#menu::item:selected {{
    background: {AZUL};
    color: {TEXTO_BOTAO};
}}
QTableView, QTableWidget {{
    border: 1px solid {BORDA};
    border-radius: 6px;
    gridline-color: {BORDA};
    alternate-background-color: {FUNDO_MENU};
    selection-background-color: {AZUL_CLARO};
    selection-color: {TEXTO};
}}
QTableView::item, QTableWidget::item {{
    padding: 4px 10px;
}}
QHeaderView::section {{
    background: {FUNDO_MENU};
    color: {TEXTO_SUAVE};
    font-weight: 600;
    border: none;
    border-bottom: 1px solid {BORDA};
    padding: 8px 10px;
}}
QCheckBox {{
    spacing: 6px;
}}
QFrame#cabecalho {{
    background: {AZUL};
}}
QFrame#cabecalho QLabel {{
    background: transparent;
    color: {TEXTO_BOTAO};
}}
QFrame#cabecalho QPushButton {{
    background: transparent;
    border: 1px solid {TEXTO_BOTAO};
}}
"""


def _arquivo_preferencias():
    return config.pasta_base() / "preferencias.json"


def carregar_preferencias() -> None:
    global modo, tamanho
    try:
        dados = json.loads(_arquivo_preferencias().read_text(encoding="utf-8"))
        modo = dados.get("modo") if dados.get("modo") in ("claro", "escuro") else "claro"
        tamanho = dados.get("tamanho") if dados.get("tamanho") in TAMANHOS else "normal"
    except (OSError, ValueError):
        pass


def salvar_preferencias() -> None:
    try:
        _arquivo_preferencias().write_text(json.dumps({"modo": modo, "tamanho": tamanho}), encoding="utf-8")
    except OSError:
        pass


def estilo() -> str:
    """Folha de estilo do programa com as cores e a letra atuais."""
    globals().update(ESCURO if modo == "escuro" else CLARO)
    fonte = TAMANHOS[tamanho]
    return _MODELO.format(fonte=fonte, titulo=round(fonte * 1.8), **(ESCURO if modo == "escuro" else CLARO))


def paleta():
    """Paleta do Qt (calendários, menus, barras de rolagem) combinando com o tema."""
    from PySide6.QtGui import QColor, QPalette
    c = ESCURO if modo == "escuro" else CLARO
    p = QPalette()
    for papel, cor in (
        (QPalette.ColorRole.Window, c["FUNDO"]), (QPalette.ColorRole.WindowText, c["TEXTO"]),
        (QPalette.ColorRole.Base, c["SUPERFICIE"]), (QPalette.ColorRole.AlternateBase, c["FUNDO_MENU"]),
        (QPalette.ColorRole.Text, c["TEXTO"]), (QPalette.ColorRole.Button, c["SUPERFICIE"]),
        (QPalette.ColorRole.ButtonText, c["TEXTO"]), (QPalette.ColorRole.Highlight, c["AZUL"]),
        (QPalette.ColorRole.HighlightedText, c["TEXTO_BOTAO"]), (QPalette.ColorRole.ToolTipBase, c["SUPERFICIE"]),
        (QPalette.ColorRole.ToolTipText, c["TEXTO"]), (QPalette.ColorRole.PlaceholderText, c["TEXTO_SUAVE"]),
        (QPalette.ColorRole.Link, c["AZUL"]),
    ):
        p.setColor(papel, QColor(cor))
    return p


def aplicar(app, novo_modo: str | None = None, novo_tamanho: str | None = None) -> None:
    global modo, tamanho
    if novo_modo:
        modo = novo_modo
    if novo_tamanho:
        tamanho = novo_tamanho
    folha = estilo()
    app.setPalette(paleta())
    app.setStyleSheet(folha)
    salvar_preferencias()


ESTILO = estilo()  # compatibilidade
