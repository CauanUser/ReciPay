"""Locais de armazenamento do Recipay.

Cada usuário cadastrado no computador tem a própria pasta (a "conta"), com banco de dados
e comprovantes separados: <base>\\contas\\<id>\\. Assim, um supervisor e um gestor podem
usar o mesmo PC sem misturar os dados nem as permissões.
"""

import os
import sys
from pathlib import Path

_conta_ativa: Path | None = None


def pasta_base() -> Path:
    """Pasta do programa no usuário do Windows: %LOCALAPPDATA%\\Recipay.

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


def definir_conta(pasta: Path | None) -> None:
    """Escolhe a pasta da conta que está em uso (None = pasta base)."""
    global _conta_ativa
    _conta_ativa = Path(pasta) if pasta else None
    if _conta_ativa:
        _conta_ativa.mkdir(parents=True, exist_ok=True)


def pasta_dados() -> Path:
    """Pasta dos dados da conta em uso."""
    return _conta_ativa if _conta_ativa else pasta_base()


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
