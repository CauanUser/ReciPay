"""QR Code Pix estático (BR Code, padrão EMV do Banco Central).

O QR Code só facilita digitar a chave e o valor no app do banco.
Ele NÃO comprova pagamento (RN-11).
"""

import io
import unicodedata

import qrcode
from qrcode.constants import ERROR_CORRECT_M


def _campo(id_: str, valor: str) -> str:
    if len(valor) > 99:
        raise ValueError(f"Campo {id_} do Pix muito longo.")
    return f"{id_}{len(valor):02d}{valor}"


def _texto_simples(texto: str, limite: int) -> str:
    """Remove acentos e caracteres especiais (alguns bancos recusam)."""
    t = unicodedata.normalize("NFKD", texto).encode("ascii", "ignore").decode()
    t = "".join(ch for ch in t if ch.isalnum() or ch == " ")
    return " ".join(t.upper().split())[:limite] or "RECEBEDOR"


def crc16(dados: str) -> str:
    """CRC16-CCITT (polinômio 0x1021, valor inicial 0xFFFF), exigido pelo BR Code."""
    crc = 0xFFFF
    for byte in dados.encode("utf-8"):
        crc ^= byte << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) if crc & 0x8000 else (crc << 1)
            crc &= 0xFFFF
    return f"{crc:04X}"


def gerar_payload(
    chave: str,
    nome_recebedor: str,
    valor_centavos: int,
    cidade: str = "BRASIL",
    descricao: str = "",
) -> str:
    """Monta o texto "Pix copia e cola" com chave, valor e nome do recebedor."""
    if valor_centavos <= 0:
        raise ValueError("Valor do Pix precisa ser maior que zero.")
    conta = _campo("00", "br.gov.bcb.pix") + _campo("01", chave)
    if descricao:
        livre = 99 - len(conta) - 4
        if livre > 0:
            conta += _campo("02", _texto_simples(descricao, livre))
    payload = (
        _campo("00", "01")
        + _campo("26", conta)
        + _campo("52", "0000")
        + _campo("53", "986")                                  # real (BRL)
        + _campo("54", f"{valor_centavos // 100}.{valor_centavos % 100:02d}")
        + _campo("58", "BR")
        + _campo("59", _texto_simples(nome_recebedor, 25))
        + _campo("60", _texto_simples(cidade, 15))
        + _campo("62", _campo("05", "***"))
        + "6304"
    )
    return payload + crc16(payload)


def imagem_png(payload: str, tamanho_caixa: int = 8) -> bytes:
    qr = qrcode.QRCode(error_correction=ERROR_CORRECT_M, box_size=tamanho_caixa, border=3)
    qr.add_data(payload)
    qr.make(fit=True)
    saida = io.BytesIO()
    qr.make_image(fill_color="black", back_color="white").save(saida, format="PNG")
    return saida.getvalue()
