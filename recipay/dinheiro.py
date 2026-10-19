"""Valores em reais, sempre guardados em centavos (inteiro) — RN-02."""

import re


def para_centavos(texto: str) -> int:
    """Converte '1.234,56', '1234,5', 'R$ 10' ou '10.50' em centavos.

    Levanta ValueError se o texto não for um valor válido maior que zero.
    """
    t = (texto or "").strip().replace("R$", "").replace(" ", "")
    if not t:
        raise ValueError("Informe o valor.")
    if not re.fullmatch(r"[\d.,]+", t):
        raise ValueError("Valor inválido. Use o formato 1.234,56.")

    if "," in t:
        # Formato brasileiro: ponto é separador de milhar, vírgula é decimal.
        inteiro, _, decimal = t.rpartition(",")
        inteiro = inteiro.replace(".", "")
    elif t.count(".") == 1 and len(t.split(".")[1]) <= 2:
        inteiro, decimal = t.split(".")  # '10.50' digitado com ponto decimal
    else:
        inteiro, decimal = t.replace(".", ""), ""

    if not inteiro.isdigit() and inteiro != "":
        raise ValueError("Valor inválido. Use o formato 1.234,56.")
    if len(decimal) > 2 or (decimal and not decimal.isdigit()):
        raise ValueError("Use no máximo 2 casas decimais.")

    centavos = int(inteiro or "0") * 100 + int((decimal + "00")[:2])
    if centavos <= 0:
        raise ValueError("O valor precisa ser maior que zero.")
    if centavos > 100_000_000_00:
        raise ValueError("Valor muito alto. Confira o número digitado.")
    return centavos


def formatar(centavos: int, simbolo: bool = True) -> str:
    """123456 -> 'R$ 1.234,56'."""
    sinal = "-" if centavos < 0 else ""
    reais, cent = divmod(abs(centavos), 100)
    texto = f"{reais:,}".replace(",", ".") + f",{cent:02d}"
    return f"{sinal}R$ {texto}" if simbolo else f"{sinal}{texto}"
