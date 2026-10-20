"""Leitura do QR Code do cupom fiscal (dados de teste, não são cupons reais)."""

import tempfile
import unittest
from datetime import date
from pathlib import Path

import qrcode

from recipay import nfce


def chave_teste(tp_emis: str = "1", modelo: str = "65", aamm: str = "2610", numero: str = "000012345") -> str:
    base = f"35{aamm}11222333000181{modelo}001{numero}{tp_emis}12345678"
    soma, peso = 0, 2
    for d in reversed(base):
        soma += int(d) * peso
        peso = 2 if peso == 9 else peso + 1
    dv = 11 - soma % 11
    return base + str(0 if dv >= 10 else dv)


class TestInterpretar(unittest.TestCase):
    def test_chave_e_dv(self):
        c = chave_teste()
        self.assertTrue(nfce.chave_valida(c))
        self.assertFalse(nfce.chave_valida(c[:-1] + str((int(c[-1]) + 1) % 10)))

    def test_nfce_online_so_tem_chave(self):
        c = chave_teste()
        cupom = nfce.interpretar(f"https://www.nfce.fazenda.sp.gov.br/qrcode?p={c}|2|1|1|ABCDEF0123")
        self.assertEqual((cupom.uf, cupom.ano, cupom.mes, cupom.numero), ("SP", 2026, 10, 12345))
        self.assertEqual(cupom.cnpj_formatado, "11.222.333/0001-81")
        self.assertIsNone(cupom.valor_centavos)
        self.assertIsNone(cupom.data)

    def test_nfce_offline_tem_dia_e_valor(self):
        c = chave_teste(tp_emis="9")
        cupom = nfce.interpretar(f"http://x/qrcode?p={c}|2|1|15|55.90|6162636465|1|HASH")
        self.assertEqual((cupom.data, cupom.valor_centavos), (date(2026, 10, 15), 5590))

    def test_nfce_versao_1(self):
        c = chave_teste()
        dh = "2026-10-03T12:30:00-03:00".encode().hex()
        cupom = nfce.interpretar(f"http://x/qrcode?chNFe={c}&nVersao=100&tpAmb=1&dhEmi={dh}&vNF=120.00")
        self.assertEqual((cupom.data, cupom.valor_centavos), (date(2026, 10, 3), 12000))

    def test_sat(self):
        c = chave_teste(modelo="59")
        cupom = nfce.interpretar(f"{c}|20261007143000|33.50|12345678909|assinatura")
        self.assertEqual((cupom.tipo, cupom.data, cupom.valor_centavos), ("CF-e SAT", date(2026, 10, 7), 3350))

    def test_qualquer_outro_qr(self):
        self.assertIsNone(nfce.interpretar("https://www.exemplo.com.br"))
        self.assertIsNone(nfce.interpretar("00020126580014br.gov.bcb.pix0136123"))


@unittest.skipUnless(nfce.disponivel(), "zxing-cpp não instalado")
class TestLerFoto(unittest.TestCase):
    def test_le_qr_da_foto(self):
        c = chave_teste(tp_emis="9")
        with tempfile.TemporaryDirectory() as tmp:
            foto = Path(tmp) / "cupom.png"
            img = qrcode.make(f"http://x/qrcode?p={c}|2|1|15|55.90|61|1|H").convert("RGB")
            img.resize((900, 900)).save(foto)
            cupom = nfce.ler_imagem(foto)
            self.assertEqual((cupom.chave, cupom.valor_centavos), (c, 5590))

    def test_foto_sem_qr(self):
        from PIL import Image
        with tempfile.TemporaryDirectory() as tmp:
            foto = Path(tmp) / "nada.png"
            Image.new("RGB", (300, 300), "white").save(foto)
            self.assertIsNone(nfce.ler_imagem(foto))


if __name__ == "__main__":
    unittest.main()
