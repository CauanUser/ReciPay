"""Testes de valores, comprovantes e cadastro de recibos."""

import os
import tempfile
import unittest
from datetime import date, timedelta
from pathlib import Path

from PIL import Image

from recipay import colaboradores, comprovantes, db, dinheiro, recibos
from recipay.auth import criar_usuario_local


class TestDinheiro(unittest.TestCase):
    def test_para_centavos(self):
        casos = {"1.234,56": 123456, "10": 1000, "10,5": 1050, "R$ 0,99": 99,
                 "10.50": 1050, "1.234": 123400, ",50": 50, "1.234.567,00": 123456700}
        for texto, esperado in casos.items():
            self.assertEqual(dinheiro.para_centavos(texto), esperado, texto)

    def test_invalidos(self):
        for texto in ("", "0", "0,00", "abc", "1,234", "-5", "10,5,5"):
            with self.assertRaises(ValueError, msg=texto):
                dinheiro.para_centavos(texto)

    def test_formatar(self):
        self.assertEqual(dinheiro.formatar(123456), "R$ 1.234,56")
        self.assertEqual(dinheiro.formatar(5), "R$ 0,05")
        self.assertEqual(dinheiro.formatar(100000000, simbolo=False), "1.000.000,00")


class BaseComPasta(unittest.TestCase):
    """Usa uma pasta de dados temporária (comprovantes são gravados em disco)."""

    def setUp(self):
        self.pasta = tempfile.TemporaryDirectory()
        self.addCleanup(self.pasta.cleanup)
        antigo = os.environ.get("RECIPAY_DADOS")
        os.environ["RECIPAY_DADOS"] = str(Path(self.pasta.name) / "dados")
        self.addCleanup(lambda: os.environ.pop("RECIPAY_DADOS") if antigo is None
                        else os.environ.__setitem__("RECIPAY_DADOS", antigo))

    def foto(self, nome="nota.png", tamanho=(3000, 2000)) -> str:
        caminho = Path(self.pasta.name) / nome
        Image.new("RGBA", tamanho, (200, 30, 30, 255)).save(caminho)
        return str(caminho)

    def pdf(self, nome="nota.pdf") -> str:
        caminho = Path(self.pasta.name) / nome
        caminho.write_bytes(b"%PDF-1.4\n% teste\n%%EOF\n")
        return str(caminho)


class TestComprovantes(BaseComPasta):
    def test_foto_reduzida_para_jpeg(self):
        relativo, hash_ = comprovantes.guardar(self.foto())
        destino = comprovantes.caminho_absoluto(relativo)
        self.assertTrue(relativo.endswith(".jpg"))
        with Image.open(destino) as img:
            self.assertEqual(max(img.size), comprovantes.LADO_MAXIMO)
        self.assertEqual(hash_, comprovantes.hash_arquivo(destino))

    def test_pdf_mantido(self):
        relativo, _ = comprovantes.guardar(self.pdf())
        self.assertTrue(relativo.endswith(".pdf"))

    def test_recusados(self):
        txt = Path(self.pasta.name) / "a.txt"
        txt.write_text("x")
        falso_pdf = Path(self.pasta.name) / "falso.pdf"
        falso_pdf.write_text("não é pdf")
        falsa_foto = Path(self.pasta.name) / "falsa.jpg"
        falsa_foto.write_text("não é foto")
        for caminho in (txt, falso_pdf, falsa_foto, Path(self.pasta.name) / "nao_existe.jpg"):
            with self.assertRaises(ValueError, msg=caminho.name):
                comprovantes.guardar(caminho)


class TestRecibos(BaseComPasta):
    def setUp(self):
        super().setUp()
        self.con = db.conectar(":memory:")
        self.addCleanup(self.con.close)
        self.sup = criar_usuario_local(self.con, "Ana", "SUPERVISOR", "123456")
        self.colab = colaboradores.criar(self.con, self.sup, {
            "nome": "João Silva", "equipe": "Norte", "tipo_chave": "EMAIL",
            "chave_pix": "joao@x.com", "titular_pix": "João Silva"})

    def dados(self, **extra):
        base = {"colaborador_id": self.colab.id, "valor_centavos": 5590,
                "descricao": "Almoço com cliente", "data_despesa": date.today().isoformat()}
        return {**base, **extra}

    def test_criar(self):
        r = recibos.criar(self.con, self.sup, self.dados(), self.foto())
        self.assertEqual(r.status, "RASCUNHO")
        self.assertEqual(r.equipe, "Norte")  # veio do colaborador
        self.assertEqual(r.saldo_centavos, 5590)
        self.assertTrue(comprovantes.caminho_absoluto(r.comprovante_arquivo).is_file())

    def test_comprovante_obrigatorio(self):
        with self.assertRaisesRegex(ValueError, "comprovante"):
            recibos.criar(self.con, self.sup, self.dados(), None)

    def test_validacoes(self):
        amanha = (date.today() + timedelta(days=1)).isoformat()
        for extra, msg in (({"data_despesa": amanha}, "futuro"),
                           ({"valor_centavos": 0}, "valor"),
                           ({"descricao": ""}, "Descreva"),
                           ({"colaborador_id": "x"}, "colaborador")):
            with self.assertRaisesRegex(ValueError, msg):
                recibos.criar(self.con, self.sup, self.dados(**extra), self.foto())

    def test_lote_tudo_ou_nada(self):
        itens = [(self.dados(), self.foto("a.png")), (self.dados(valor_centavos=0), self.foto("b.png"))]
        with self.assertRaisesRegex(ValueError, "Linha 2"):
            recibos.criar_lote(self.con, self.sup, itens)
        self.assertEqual(recibos.listar(self.con), [])
        itens[1] = (self.dados(valor_centavos=1000), self.pdf())
        self.assertEqual(len(recibos.criar_lote(self.con, self.sup, itens)), 2)

    def test_editar_e_historico(self):
        r = recibos.criar(self.con, self.sup, self.dados(), self.foto())
        r2 = recibos.atualizar(self.con, self.sup, r.id, self.dados(valor_centavos=6000), self.pdf())
        self.assertEqual(r2.valor_centavos, 6000)
        self.assertTrue(r2.comprovante_arquivo.endswith(".pdf"))
        acoes = [h["acao"] for h in recibos.historico(self.con, r.id)]
        self.assertEqual(acoes, ["CRIADO", "ALTERADO"])

    def test_exportado_nao_edita(self):
        r = recibos.criar(self.con, self.sup, self.dados(), self.foto())
        self.con.execute("UPDATE recibo SET status = 'ENVIADO' WHERE id = ?", (r.id,))
        with self.assertRaisesRegex(ValueError, "exportado"):
            recibos.atualizar(self.con, self.sup, r.id, self.dados())
        with self.assertRaises(ValueError):
            recibos.cancelar(self.con, self.sup, r.id, "erro")

    def test_cancelar_exige_motivo(self):
        r = recibos.criar(self.con, self.sup, self.dados(), self.foto())
        with self.assertRaisesRegex(ValueError, "motivo"):
            recibos.cancelar(self.con, self.sup, r.id, "")
        recibos.cancelar(self.con, self.sup, r.id, "Lançado em duplicidade")
        self.assertEqual(recibos.buscar(self.con, r.id).status, "CANCELADO")

    def test_gestor_nao_cadastra(self):
        con = db.conectar(":memory:")
        self.addCleanup(con.close)
        gestor = criar_usuario_local(con, "Bruno", "GESTOR", "123456")
        with self.assertRaises(PermissionError):
            recibos.criar(con, gestor, self.dados(), self.foto())

    def test_filtros(self):
        recibos.criar(self.con, self.sup, self.dados(descricao="Combustível"), self.foto())
        recibos.criar(self.con, self.sup, self.dados(), self.foto("b.png"))
        self.assertEqual(len(recibos.listar(self.con, busca="combust")), 1)
        self.assertEqual(len(recibos.listar(self.con, status="RASCUNHO")), 2)
        self.assertEqual(len(recibos.listar(self.con, equipe="Sul")), 0)


if __name__ == "__main__":
    unittest.main()
