"""Identidade visual: branco e azul, verde para sucesso."""

AZUL = "#1E5AA8"
AZUL_ESCURO = "#174A8B"
AZUL_CLARO = "#E8F0FB"
FUNDO_MENU = "#F4F7FC"
BORDA = "#D5DEEA"
TEXTO = "#1F2933"
TEXTO_SUAVE = "#5B6B7F"
VERDE = "#2E7D32"
LARANJA = "#E08A00"
VERMELHO = "#C62828"

ESTILO = f"""
QWidget {{
    font-family: "Segoe UI", Arial, sans-serif;
    font-size: 10pt;
    color: {TEXTO};
    background: #FFFFFF;
}}
QLabel#titulo {{
    font-size: 18pt;
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
    color: #FFFFFF;
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
    background: #FFFFFF;
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
    color: #FFFFFF;
}}
QFrame#cabecalho {{
    background: {AZUL};
}}
QFrame#cabecalho QLabel {{
    background: transparent;
    color: #FFFFFF;
}}
QFrame#cabecalho QPushButton {{
    background: transparent;
    border: 1px solid #FFFFFF;
}}
"""
