"""Locais de armazenamento do Recipay."""

import os
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
