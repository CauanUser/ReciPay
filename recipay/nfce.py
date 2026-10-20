"""Leitura do QR Code do cupom fiscal (NFC-e e CF-e SAT) a partir da foto do comprovante.

O que dá para tirar do QR Code, sem internet:
- Sempre: a chave de acesso (44 dígitos) → mês/ano de emissão, CNPJ da loja, número do cupom.
  A chave serve para avisar quando o MESMO cupom é lançado duas vezes.
- Valor e dia da compra: só aparecem no QR em alguns casos — cupom emitido em contingência
  (offline), modelo antigo de QR (versão 1) e cupom SAT (São Paulo). Nos demais, o valor
  fica só no site da Sefaz, e o programa pede para digitar.
"""

import re
from dataclasses import dataclass
from datetime import date, datetime
from pathlib import Path
from urllib.parse import parse_qs, urlparse

try:  # leitor de QR Code gratuito (zxing-cpp); se faltar, a função fica desligada
    import zxingcpp
except ImportError:  # pragma: no cover
    zxingcpp = None

from PIL import Image, ImageOps

UFS = {"11": "RO", "12": "AC", "13": "AM", "14": "RR", "15": "PA", "16": "AP", "17": "TO", "21": "MA",
       "22": "PI", "23": "CE", "24": "RN", "25": "PB", "26": "PE", "27": "AL", "28": "SE", "29": "BA",
       "31": "MG", "32": "ES", "33": "RJ", "35": "SP", "41": "PR", "42": "SC", "43": "RS", "50": "MS",
       "51": "MT", "52": "GO", "53": "DF"}


@dataclass
class Cupom:
    chave: str
    uf: str
    ano: int
    mes: int
    cnpj: str
    modelo: str
    numero: int
    valor_centavos: int | None = None
    data: date | None = None

    @property
    def tipo(self) -> str:
        return {"65": "NFC-e", "59": "CF-e SAT", "55": "NF-e"}.get(self.modelo, "Cupom fiscal")

    @property
    def cnpj_formatado(self) -> str:
        c = self.cnpj
        return f"{c[:2]}.{c[2:5]}.{c[5:8]}/{c[8:12]}-{c[12:]}"

    def resumo(self) -> str:
        quando = self.data.strftime("%d/%m/%Y") if self.data else f"{self.mes:02d}/{self.ano}"
        texto = f"{self.tipo} nº {self.numero} · CNPJ {self.cnpj_formatado} · {quando}"
        if self.valor_centavos is not None:
            reais, cent = divmod(self.valor_centavos, 100)
            texto += f" · R$ {reais:,}".replace(",", ".") + f",{cent:02d}"
        return texto


def chave_valida(chave: str) -> bool:
    """Dígito verificador da chave de acesso (módulo 11, pesos 2 a 9)."""
    if not re.fullmatch(r"\d{44}", chave or ""):
        return False
    soma, peso = 0, 2
    for d in reversed(chave[:43]):
        soma += int(d) * peso
        peso = 2 if peso == 9 else peso + 1
    dv = 11 - soma % 11
    return (0 if dv >= 10 else dv) == int(chave[43])


def _de_chave(chave: str) -> Cupom:
    return Cupom(chave=chave, uf=UFS.get(chave[:2], chave[:2]), ano=2000 + int(chave[2:4]),
                 mes=int(chave[4:6]), cnpj=chave[6:20], modelo=chave[20:22], numero=int(chave[25:34]))


def _valor(texto: str) -> int | None:
    try:
        v = round(float(texto.replace(",", ".")) * 100)
        return v if v > 0 else None
    except (ValueError, AttributeError):
        return None


def interpretar(texto: str) -> Cupom | None:
    """Interpreta o conteúdo do QR Code. None se não for um cupom fiscal."""
    texto = (texto or "").strip()

    # CF-e SAT (SP): chave|AAAAMMDDhhmmss|valor|documento|assinatura
    partes = texto.split("|")
    if len(partes) >= 3 and re.fullmatch(r"\d{44}", partes[0]) and re.fullmatch(r"\d{14}", partes[1]):
        cupom = _de_chave(partes[0])
        try:
            cupom.data = datetime.strptime(partes[1], "%Y%m%d%H%M%S").date()
        except ValueError:
            pass
        cupom.valor_centavos = _valor(partes[2])
        return cupom if chave_valida(cupom.chave) else None

    url = urlparse(texto)
    params = parse_qs(url.query)

    # NFC-e versão 2 e 3: ...?p=chave|versao|ambiente|...
    if "p" in params:
        campos = params["p"][0].split("|")
        chave = campos[0]
        if not chave_valida(chave):
            return None
        cupom = _de_chave(chave)
        offline = chave[34] == "9"  # tpEmis 9 = contingência offline: QR traz dia e valor
        if offline and len(campos) >= 5:
            try:
                cupom.data = date(cupom.ano, cupom.mes, int(campos[3]))
            except ValueError:
                pass
            cupom.valor_centavos = _valor(campos[4])
        return cupom

    # NFC-e versão 1: ...?chNFe=...&dhEmi=<hex>&vNF=...
    if "chNFe" in params:
        chave = params["chNFe"][0]
        if not chave_valida(chave):
            return None
        cupom = _de_chave(chave)
        if "vNF" in params:
            cupom.valor_centavos = _valor(params["vNF"][0])
        if "dhEmi" in params:
            try:
                cupom.data = date.fromisoformat(bytes.fromhex(params["dhEmi"][0]).decode()[:10])
            except ValueError:
                pass
        return cupom

    # Só a chave (44 dígitos) em algum lugar do texto
    achado = re.search(r"\d{44}", texto)
    if achado and chave_valida(achado.group()):
        return _de_chave(achado.group())
    return None


def disponivel() -> bool:
    return zxingcpp is not None


def ler_imagem(caminho: Path | str) -> Cupom | None:
    """Procura o QR Code do cupom na foto. None se não achar ou não for cupom fiscal."""
    if zxingcpp is None or Path(caminho).suffix.lower() == ".pdf":
        return None
    try:
        with Image.open(caminho) as img:
            img = ImageOps.exif_transpose(img).convert("L")
            tentativas = [img]
            if max(img.size) > 2000:  # fotos grandes: tenta também reduzida
                menor = img.copy()
                menor.thumbnail((1600, 1600))
                tentativas.append(menor)
            tentativas.append(ImageOps.autocontrast(img))
            for imagem in tentativas:
                for codigo in zxingcpp.read_barcodes(imagem):
                    cupom = interpretar(codigo.text)
                    if cupom:
                        return cupom
    except (OSError, ValueError):
        return None
    return None
