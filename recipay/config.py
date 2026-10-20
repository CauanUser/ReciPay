"""Locais de armazenamento do Recipay."""

import os
import sys
from pathlib import Path


def pasta_dados() -> Path:
    """Pasta de dados do usuário do Windows: %LOCALAPPDATA%\\Recipay.

    A variável RECIPAY_DADOS permite usar outra pasta (útil para testes).
    """
    personalizada = os.environ.get("RECIPAY_DADOS")
    if personalizada:
        base = Path(personalizada)
    elif os.environ.get("LOCALAPPDATA"):
        base = Path(os.environ["LOCALAPPDATA"]) / "Recipay"
    else:
        base = Path.home() / ".recipay"
    base.mkdir(parents=True, exist_ok=True)
    return base


def caminho_banco() -> Path:
    return pasta_dados() / "recipay.db"


def pasta_comprovantes() -> Path:
    pasta = pasta_dados() / "comprovantes"
    pasta.mkdir(parents=True, exist_ok=True)
    return pasta


def limpar_temporarios() -> None:
    """Apaga imagens coladas que ficaram da sessão anterior (já copiadas para comprovantes)."""
    pasta = pasta_dados() / "temp"
    if pasta.is_dir():
        for arquivo in pasta.iterdir():
            try:
                arquivo.unlink()
            except OSError:
                pass


def caminho_recurso(nome: str) -> Path:
    """Arquivos que acompanham o programa (ícone). Funciona no .exe e no código-fonte."""
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent.parent))
    return base / "assets" / nome
