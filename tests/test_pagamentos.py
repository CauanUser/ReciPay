"""Testes do QR Code Pix e do registro de pagamentos."""

import os
import shutil
import tempfile
import unittest
from datetime import date, timedelta
from pathlib import Path

from PIL import Image

from recipay import brcode, colaboradores, db, pagamentos, recibos
from recipay.auth import criar_usuario_local


class TestBrCode(unittest.TestCase):
    def test_crc16_valor_de_referencia(self):
        # Valor de verificação conhecido do CRC-16/CCITT-FALSE.
        self.assertEqual(brcode.crc16("123456789"), "29B1")

    def test_payload(self):
        p = brcode.gerar_payload("joao@x.com", "João da Silva Ç", 12345)
        self.assertTrue(p.startswith("000201"))
        self.assertIn("0014br.gov.bcb.pix0110joao@x.com", p)
        self.assertIn("5406123.45", p)
        self.assertIn("5915JOAO DA SILVA C", p)
        self.assertEqual(p[-8:-4], "6304")
        self.assertEqual(p[-4:], brcode.crc16(p[:-4]))

    def test_valor_invalido_e_imagem(self):
        with self.assertRaises(ValueError):
            brcode.gerar_payload("a@b.com", "X", 0)
        png = brcode.imagem_png(brcode.gerar_payload("a@b.com", "X", 100))
        self.assertTrue(png.startswith(b"\x89PNG"))


class TestPagamentos(unittest.TestCase):
    def setUp(self):
        self.pasta = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.pasta, True)
        os.environ["RECIPAY_DADOS"] = str(self.pasta / "dados")
        self.addCleanup(os.environ.pop, "RECIPAY_DADOS", None)

        # Monta o banco do "gestor" com recibos já importados (PENDENTE).
        self.con = db.conectar(":memory:")
        self.addCleanup(self.con.close)
        self.sup = criar_usuario_local(self.con, "Ana", "SUPERVISOR", "123456")
        self.colab = colaboradores.criar(self.con, self.sup, {
            "nome": "João Silva", "equipe": "Norte", "tipo_chave": "EMAIL",
            "chave_pix": "joao@x.com", "titular_pix": "João Silva"})
        self.outro = colaboradores.criar(self.con, self.sup, {
            "nome": "Maria Lima", "equipe": "Sul", "tipo_chave": "EMAIL",
            "chave_pix": "maria@x.com", "titular_pix": "Maria Lima"})
        hoje = date.today()
        self.r_antigo = self._recibo(self.colab, 10000, hoje - timedelta(days=5))
        self.r_novo = self._recibo(self.colab, 5000, hoje)
        self.r_outro = self._recibo(self.outro, 3000, hoje)
        self.con.execute("UPDATE recibo SET status = 'PENDENTE'")
        # O gestor é outro usuário no mesmo banco (para teste).
        self.con.execute("UPDATE usuario SET local = 0")
        self.gestor = criar_usuario_local(self.con, "Bruno", "GESTOR", "123456")

    def _recibo(self, colab, valor, data):
        foto = self.pasta / f"n{valor}.png"
        Image.new("RGB", (50, 50), "white").save(foto)
        return recibos.criar(self.con, self.sup, {
            "colaborador_id": colab.id, "valor_centavos": valor, "descricao": f"Despesa {valor}",
            "data_despesa": data.isoformat()}, str(foto))

    def _comprovante(self, nome="pix.pdf", conteudo=b"1"):
        caminho = self.pasta / nome
        caminho.write_bytes(b"%PDF-1.4\n" + conteudo + b"\n%%EOF\n")
        return str(caminho)

    def _situacoes(self):
        return {r.id: (r.status, r.saldo_centavos) for r in recibos.listar(self.con)}

    def test_distribuir_do_mais_antigo(self):
        self.assertEqual(pagamentos.distribuir([("a", 100), ("b", 50)], 120), {"a": 100, "b": 20})
        self.assertEqual(pagamentos.distribuir([("a", 100), ("b", 50)], 500), {"a": 100, "b": 50})

    def test_um_pix_para_varios_recibos_com_parcial(self):
        abertos = pagamentos.recibos_em_aberto(self.con, self.colab.id)
        self.assertEqual([r.id for r in abertos], [self.r_antigo.id, self.r_novo.id])
        aplic = pagamentos.distribuir([(r.id, r.saldo_centavos) for r in abertos], 12000)
        pagamentos.registrar(self.con, self.gestor, self.colab.id, aplic,
                             date.today().isoformat(), self._comprovante())
        s = self._situacoes()
        self.assertEqual(s[self.r_antigo.id], ("PAGO", 0))
        self.assertEqual(s[self.r_novo.id], ("PARCIAL", 3000))
        # Segundo Pix quita o restante
        pagamentos.registrar(self.con, self.gestor, self.colab.id, {self.r_novo.id: 3000},
                             date.today().isoformat(), self._comprovante("pix2.pdf", b"2"))
        self.assertEqual(self._situacoes()[self.r_novo.id], ("PAGO", 0))
        t = pagamentos.listar_transferencias(self.con)
        self.assertEqual(sorted(x.valor_centavos for x in t), [3000, 12000])
        self.assertEqual(t[0].chave_pix_usada, "joao@x.com")

    def test_validacoes(self):
        hoje = date.today().isoformat()
        casos = [
            ({self.r_antigo.id: 20000}, hoje, self._comprovante(), "maior que o saldo"),
            ({self.r_antigo.id: 100, self.r_outro.id: 100}, hoje, self._comprovante(), "mesmo colaborador"),
            ({self.r_antigo.id: 100}, (date.today() + timedelta(days=1)).isoformat(),
             self._comprovante(), "futuro"),
            ({self.r_antigo.id: 100}, hoje, None, "comprovante"),
            ({}, hoje, self._comprovante(), "pelo menos um"),
        ]
        for aplic, data, comp, msg in casos:
            with self.assertRaisesRegex(ValueError, msg):
                pagamentos.registrar(self.con, self.gestor, self.colab.id, aplic, data, comp)
        self.assertEqual(pagamentos.listar_transferencias(self.con), [])

    def test_mesmo_comprovante_duas_vezes(self):
        comp = self._comprovante()
        pagamentos.registrar(self.con, self.gestor, self.colab.id, {self.r_antigo.id: 1000},
                             date.today().isoformat(), comp)
        with self.assertRaisesRegex(ValueError, "já foi usado"):
            pagamentos.registrar(self.con, self.gestor, self.colab.id, {self.r_antigo.id: 1000},
                                 date.today().isoformat(), comp)

    def test_outra_chave_nao_altera_cadastro(self):
        pagamentos.registrar(
            self.con, self.gestor, self.colab.id, {self.r_antigo.id: 10000}, date.today().isoformat(),
            self._comprovante(),
            outra_chave={"tipo_chave": "TELEFONE", "chave_pix": "(11) 98888-7777", "titular_pix": "João S"})
        t = pagamentos.listar_transferencias(self.con)[0]
        self.assertEqual((t.tipo_chave_usada, t.chave_pix_usada), ("TELEFONE", "+5511988887777"))
        self.assertEqual(colaboradores.buscar(self.con, self.colab.id).chave_pix, "joao@x.com")

    def test_estorno_desfaz_tudo(self):
        aplic = {self.r_antigo.id: 10000, self.r_novo.id: 2000}
        tid = pagamentos.registrar(self.con, self.gestor, self.colab.id, aplic,
                                   date.today().isoformat(), self._comprovante())
        with self.assertRaisesRegex(ValueError, "motivo"):
            pagamentos.estornar(self.con, self.gestor, tid, "")
        pagamentos.estornar(self.con, self.gestor, tid, "Valor lançado errado")
        s = self._situacoes()
        self.assertEqual(s[self.r_antigo.id], ("PENDENTE", 10000))
        self.assertEqual(s[self.r_novo.id], ("PENDENTE", 5000))
        # Depois do estorno, o mesmo comprovante pode ser usado no lançamento correto.
        pagamentos.registrar(self.con, self.gestor, self.colab.id, {self.r_antigo.id: 10000},
                             date.today().isoformat(), self._comprovante())

    def test_lista_de_colaboradores_com_pendencia(self):
        lista = pagamentos.colaboradores_com_pendencia(self.con)
        self.assertEqual([(c.nome, c.qtd_recibos, c.saldo_centavos) for c in lista],
                         [("João Silva", 2, 15000), ("Maria Lima", 1, 3000)])
        pagamentos.registrar(self.con, self.gestor, self.outro.id, {self.r_outro.id: 3000},
                             date.today().isoformat(), self._comprovante())
        self.assertEqual([c.nome for c in pagamentos.colaboradores_com_pendencia(self.con)], ["João Silva"])

    def test_supervisor_nao_registra(self):
        with self.assertRaises(PermissionError):
            pagamentos.registrar(self.con, self.sup, self.colab.id, {self.r_antigo.id: 100},
                                 date.today().isoformat(), self._comprovante())


if __name__ == "__main__":
    unittest.main()
