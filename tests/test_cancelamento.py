"""Cancelar e corrigir recibos já enviados; contas de vários usuários no mesmo PC."""

import os
import shutil
import tempfile
import unittest
from datetime import date
from pathlib import Path

from PIL import Image

from recipay import colaboradores, config, contas, db, pagamentos, recibos, troca
from recipay.auth import autenticar, criar_usuario_local


class TestCancelarEnviado(unittest.TestCase):
    def setUp(self):
        self.pasta = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.pasta, True)
        self.addCleanup(os.environ.pop, "RECIPAY_DADOS", None)
        self.hoje = date.today().isoformat()
        self._pc("sup")
        self.cs = db.conectar(":memory:")
        self.addCleanup(self.cs.close)
        self.sup = criar_usuario_local(self.cs, "Ana", "SUPERVISOR", "123456")
        self.foto = self.pasta / "n.png"
        Image.new("RGB", (30, 30), "white").save(self.foto)
        self.c = colaboradores.criar(self.cs, self.sup, {"nome": "João Silva", "equipe": "N",
                                     "tipo_chave": "EMAIL", "chave_pix": "j@x.com", "titular_pix": "João"})
        self.r1 = self._recibo(1000, "Almoço")
        self.r2 = self._recibo(2000, "Táxi")
        self.arq = self.pasta / "a.xlsx"
        troca.exportar_recibos(self.cs, self.sup, [self.r1.id, self.r2.id], self.arq)
        self._pc("ges")
        self.cg = db.conectar(":memory:")
        self.addCleanup(self.cg.close)
        self.g = criar_usuario_local(self.cg, "Bruno", "GESTOR", "123456")
        troca.importar(self.cg, self.g, troca.ler_arquivo(self.cg, self.arq))

    def _pc(self, nome):
        os.environ["RECIPAY_DADOS"] = str(self.pasta / nome)

    def _recibo(self, valor, descr):
        return recibos.criar(self.cs, self.sup, {"colaborador_id": self.c.id, "valor_centavos": valor,
                             "descricao": descr, "data_despesa": self.hoje}, str(self.foto))

    def _status_gestor(self):
        return {r.id: r.status for r in recibos.listar(self.cg)}

    def test_cancelar_enviado_avisa_gestor(self):
        self._pc("sup")
        ok, aviso = recibos.pode_cancelar(recibos.buscar(self.cs, self.r1.id))
        self.assertTrue(ok)
        self.assertIn("gestor será avisado", aviso)
        recibos.cancelar(self.cs, self.sup, self.r1.id, "Lançado em duplicidade")
        self.assertEqual(len(recibos.cancelamentos_a_avisar(self.cs, self.sup.id)), 1)

        res = troca.exportar_recibos(self.cs, self.sup, [], self.pasta / "b.xlsx")  # só o aviso
        self.assertEqual((res.quantidade, res.cancelamentos), (0, 1))
        self.assertEqual(recibos.cancelamentos_a_avisar(self.cs, self.sup.id), [])

        self._pc("ges")
        previa = troca.ler_arquivo(self.cg, self.pasta / "b.xlsx")
        self.assertEqual([c.acao for c in previa.cancelamentos], ["CANCELAR"])
        res = troca.importar(self.cg, self.g, previa)
        self.assertEqual(res.cancelados, 1)
        self.assertEqual(self._status_gestor(), {self.r1.id: "CANCELADO", self.r2.id: "PENDENTE"})

    def test_cancelamento_de_recibo_ja_pago_nao_some(self):
        comp = self.pasta / "pix.pdf"
        comp.write_bytes(b"%PDF-1.4\nx\n%%EOF\n")
        pagamentos.registrar(self.cg, self.g, self.c.id, {self.r1.id: 1000}, self.hoje, str(comp))
        self._pc("sup")
        recibos.cancelar(self.cs, self.sup, self.r1.id, "Erro")
        troca.exportar_recibos(self.cs, self.sup, [], self.pasta / "b.xlsx")
        self._pc("ges")
        previa = troca.ler_arquivo(self.cg, self.pasta / "b.xlsx")
        self.assertEqual(previa.cancelamentos[0].acao, "JA_PAGO")
        self.assertTrue(any("já pagou" in a for a in previa.avisos))
        troca.importar(self.cg, self.g, previa)
        self.assertEqual(self._status_gestor()[self.r1.id], "PAGO")

    def test_corrigir_enviado_cria_novo_rascunho(self):
        self._pc("sup")
        novo = recibos.corrigir(self.cs, self.sup, self.r2.id, {
            "colaborador_id": self.c.id, "valor_centavos": 2500, "descricao": "Táxi (corrigido)",
            "data_despesa": self.hoje})
        self.assertEqual((novo.status, novo.valor_centavos), ("RASCUNHO", 2500))
        self.assertEqual(recibos.buscar(self.cs, self.r2.id).status, "CANCELADO")
        # Na próxima planilha vão o novo recibo e o aviso do cancelamento do antigo.
        res = troca.exportar_recibos(self.cs, self.sup, [novo.id], self.pasta / "c.xlsx")
        self.assertEqual((res.quantidade, res.cancelamentos), (1, 1))
        self._pc("ges")
        troca.importar(self.cg, self.g, troca.ler_arquivo(self.cg, self.pasta / "c.xlsx"))
        st = self._status_gestor()
        self.assertEqual((st[self.r2.id], st[novo.id]), ("CANCELADO", "PENDENTE"))

    def test_corrigir_rascunho_edita_direto(self):
        self._pc("sup")
        r3 = self._recibo(500, "Café")
        r = recibos.corrigir(self.cs, self.sup, r3.id, {"colaborador_id": self.c.id, "valor_centavos": 700,
                             "descricao": "Café", "data_despesa": self.hoje})
        self.assertEqual((r.id, r.valor_centavos), (r3.id, 700))

    def test_pago_nao_pode_cancelar(self):
        self._pc("sup")
        self.cs.execute("UPDATE recibo SET status = 'PAGO' WHERE id = ?", (self.r1.id,))
        ok, motivo = recibos.pode_cancelar(recibos.buscar(self.cs, self.r1.id))
        self.assertFalse(ok)
        self.assertIn("gestor já registrou pagamento", motivo)
        with self.assertRaises(ValueError):
            recibos.cancelar(self.cs, self.sup, self.r1.id, "teste")


class TestContas(unittest.TestCase):
    def setUp(self):
        self.pasta = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.pasta, True)
        os.environ["RECIPAY_DADOS"] = str(self.pasta)
        self.addCleanup(os.environ.pop, "RECIPAY_DADOS", None)
        self.addCleanup(config.definir_conta, None)

    def _criar(self, nome, perfil):
        pasta = contas.nova_pasta()
        config.definir_conta(pasta)
        con = db.conectar(config.caminho_banco())
        u = criar_usuario_local(con, nome, perfil, "123456")
        con.close()
        return u

    def test_varios_usuarios_com_dados_separados(self):
        self._criar("Ana", "SUPERVISOR")
        self._criar("Bruno", "GESTOR")
        lista = contas.listar()
        self.assertEqual([(c.nome, c.perfil) for c in lista], [("Ana", "SUPERVISOR"), ("Bruno", "GESTOR")])
        self.assertNotEqual(lista[0].pasta, lista[1].pasta)
        config.definir_conta(lista[1].pasta)
        con = db.conectar(config.caminho_banco())
        self.addCleanup(con.close)
        self.assertEqual(autenticar(con, "GESTOR", "123456").nome, "Bruno")
        self.assertEqual(con.execute("SELECT COUNT(*) FROM usuario").fetchone()[0], 1)

    def test_cadastro_cancelado_nao_deixa_lixo(self):
        pasta = contas.nova_pasta()
        config.definir_conta(pasta)
        db.conectar(config.caminho_banco()).close()
        contas.descartar_pasta_vazia(pasta)
        self.assertFalse(pasta.exists())
        self.assertEqual(contas.listar(), [])

    def test_migra_instalacao_antiga_e_lembra_ultima(self):
        config.definir_conta(None)
        con = db.conectar(config.caminho_banco())  # banco antigo direto na pasta base
        criar_usuario_local(con, "Ana", "SUPERVISOR", "123456")
        con.close()
        contas.migrar_instalacao_antiga()
        self.assertFalse((self.pasta / "recipay.db").exists())
        lista = contas.listar()
        self.assertEqual([c.nome for c in lista], ["Ana"])
        contas.lembrar(lista[0])
        self.assertEqual(contas.ultima(), lista[0].usuario_id)


if __name__ == "__main__":
    unittest.main()
