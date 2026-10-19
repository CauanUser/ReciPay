"""Validação e normalização de chaves Pix (RN-13)."""

import re

TIPOS_CHAVE = {
    "CPF": "CPF",
    "CNPJ": "CNPJ",
    "EMAIL": "E-mail",
    "TELEFONE": "Telefone",
    "ALEATORIA": "Chave aleatória",
}

# Nomes aceitos na planilha para cada tipo (sem acento e em maiúsculas).
_SINONIMOS = {
    "CPF": "CPF",
    "CNPJ": "CNPJ",
    "EMAIL": "EMAIL",
    "E-MAIL": "EMAIL",
    "TELEFONE": "TELEFONE",
    "CELULAR": "TELEFONE",
    "FONE": "TELEFONE",
    "ALEATORIA": "ALEATORIA",
    "CHAVE ALEATORIA": "ALEATORIA",
    "EVP": "ALEATORIA",
}

_RE_EMAIL = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
_RE_ALEATORIA = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")


def _digitos(texto: str) -> str:
    return re.sub(r"\D", "", texto)


def cpf_valido(cpf: str) -> bool:
    d = _digitos(cpf)
    if len(d) != 11 or d == d[0] * 11:
        return False
    for tamanho in (9, 10):
        soma = sum(int(d[i]) * (tamanho + 1 - i) for i in range(tamanho))
        digito = (soma * 10) % 11 % 10
        if digito != int(d[tamanho]):
            return False
    return True


def cnpj_valido(cnpj: str) -> bool:
    d = _digitos(cnpj)
    if len(d) != 14 or d == d[0] * 14:
        return False
    pesos1 = [5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2]
    pesos2 = [6] + pesos1
    for pesos, pos in ((pesos1, 12), (pesos2, 13)):
        soma = sum(int(d[i]) * pesos[i] for i in range(pos))
        resto = soma % 11
        digito = 0 if resto < 2 else 11 - resto
        if digito != int(d[pos]):
            return False
    return True


def normalizar_tipo(texto: str) -> str | None:
    """Converte 'E-mail', 'Aleatória' etc. no código interno. None se não reconhecer."""
    t = (texto or "").strip().upper()
    t = t.translate(str.maketrans("ÁÀÃÂÉÊÍÓÔÕÚÇ", "AAAAEEIOOOUC"))
    return _SINONIMOS.get(t)


def normalizar_chave(tipo: str, chave: str) -> str:
    """Valida a chave e devolve no formato padrão. Levanta ValueError se inválida."""
    chave = (chave or "").strip()
    if not chave:
        raise ValueError("Informe a chave Pix.")

    if tipo == "CPF":
        d = _digitos(chave).zfill(11) if chave.isdigit() else _digitos(chave)
        if not cpf_valido(d):
            raise ValueError("CPF inválido.")
        return d

    if tipo == "CNPJ":
        d = _digitos(chave).zfill(14) if chave.isdigit() else _digitos(chave)
        if not cnpj_valido(d):
            raise ValueError("CNPJ inválido.")
        return d

    if tipo == "EMAIL":
        email = chave.lower()
        if len(email) > 77 or not _RE_EMAIL.match(email):
            raise ValueError("E-mail inválido.")
        return email

    if tipo == "TELEFONE":
        d = _digitos(chave)
        if len(d) in (12, 13) and d.startswith("55"):
            d = d[2:]
        # DDD (2 dígitos, sem zero) + número de 8 ou 9 dígitos.
        if len(d) not in (10, 11) or d[0] == "0":
            raise ValueError("Telefone inválido. Use DDD + número, ex.: (11) 99999-8888.")
        return "+55" + d

    if tipo == "ALEATORIA":
        aleatoria = chave.lower()
        if not _RE_ALEATORIA.match(aleatoria):
            raise ValueError("Chave aleatória inválida (formato: 8-4-4-4-12 caracteres).")
        return aleatoria

    raise ValueError("Tipo de chave inválido.")


def detectar_tipo(chave: str) -> str | None:
    """Tenta descobrir o tipo da chave. None quando é ambíguo ou desconhecido."""
    chave = (chave or "").strip()
    if not chave:
        return None
    if "@" in chave:
        return "EMAIL"
    if _RE_ALEATORIA.match(chave.lower()):
        return "ALEATORIA"
    if chave.startswith("+"):
        return "TELEFONE"
    d = _digitos(chave)
    if len(d) == 14 and cnpj_valido(d):
        return "CNPJ"
    if len(d) == 11 and cpf_valido(d):
        return "CPF"  # 11 dígitos que formam um CPF válido: assume CPF
    return None


def formatar_chave(tipo: str, chave: str) -> str:
    """Formato para exibir na tela."""
    if tipo == "CPF" and len(chave) == 11:
        return f"{chave[:3]}.{chave[3:6]}.{chave[6:9]}-{chave[9:]}"
    if tipo == "CNPJ" and len(chave) == 14:
        return f"{chave[:2]}.{chave[2:5]}.{chave[5:8]}/{chave[8:12]}-{chave[12:]}"
    if tipo == "TELEFONE" and chave.startswith("+55"):
        d = chave[3:]
        return f"+55 ({d[:2]}) {d[2:-4]}-{d[-4:]}"
    return chave
