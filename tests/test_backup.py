"""Testes do backup criptografado."""

import os
import shutil
import tempfile
import unittest
from datetime import date
from pathlib import Path

from PIL import Image

from recipay import backup, colaboradores, config, db, recibos
from recipay.auth import criar_usuario_local


class TestBackup(unittest.TestCase):
    def setUp(self):
        self.pasta = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.pasta, True)
        os.environ["RECIPAY_DADOS"] = str(self.pasta / "pc1")
        self.addCleanup(os.environ.pop, "RECIPAY_DADOS", None)
        self.con = db.conectar(config.caminho_banco())
        self.sup = criar_usuario_local(self.con, "Ana", "SUPERVISOR", "123456")
        c = colaboradores.criar(self.con, self.sup, {"nome": "João Silva", "equipe": "Norte",
                                "tipo_chave": "EMAIL", "chave_pix": "joao@x.com", "titular_pix": "João"})
        foto = self.pasta / "nota.png"
        Image.new("RGB", (40, 40), "red").save(foto)
        self.r = recibos.criar(self.con, self.sup, {"colaborador_id": c.id, "valor_centavos": 990,
                               "descricao": "Café", "data_despesa": date.today().isoformat()}, str(foto))
        self.arquivo = self.pasta / "teste.recipay-backup"

    def tearDown(self):
        try:
            self.con.close()
        except Exception:
            pass

    def test_lembrete(self):
        self.assertTrue(backup.precisa_lembrar(self.con))
        backup.exportar(self.con, self.arquivo, "senha-forte")
        self.assertFalse(backup.precisa_lembrar(self.con))
        self.assertIsNotNone(backup.ultimo_backup(self.con))

    def test_senha_curta(self):
        with self.assertRaises(ValueError):
            backup.exportar(self.con, self.arquivo, "123")

    def test_arquivo_cifrado_e_senha_errada(self):
        backup.exportar(self.con, self.arquivo, "senha-forte")
        bruto = self.arquivo.read_bytes()
        self.assertNotIn(b"joao@x.com", bruto)        # dados pessoais não ficam legíveis
        self.assertNotIn(b"SQLite format", bruto)
        with self.assertRaisesRegex(ValueError, "Senha incorreta"):
            backup.conferir(self.arquivo, "senha-errada")
        outro = self.pasta / "outro.recipay-backup"
        outro.write_bytes(b"qualquer coisa")
        with self.assertRaisesRegex(ValueError, "não é um backup"):
            backup.conferir(outro, "senha-forte")

    def test_restaurar_em_outro_computador(self):
        backup.exportar(self.con, self.arquivo, "senha-forte")
        self.assertEqual(backup.conferir(self.arquivo, "senha-forte")["recibos"], 1)

        # "PC novo": pasta vazia, configurado com outro usuário por engano
        os.environ["RECIPAY_DADOS"] = str(self.pasta / "pc2")
        con2 = db.conectar(config.caminho_banco())
        criar_usuario_local(con2, "Errado", "GESTOR", "123456")
        con2.close()

        resumo = backup.restaurar(self.arquivo, "senha-forte")
        self.assertEqual((resumo["nome"], resumo["perfil"]), ("Ana", "SUPERVISOR"))
        con3 = db.conectar(config.caminho_banco())
        self.addCleanup(con3.close)
        r = recibos.buscar(con3, self.r.id)
        self.assertEqual(r.valor_centavos, 990)
        from recipay import comprovantes
        self.assertTrue(comprovantes.caminho_absoluto(r.comprovante_arquivo).is_file())
        # Os dados que existiam antes ficam guardados
        guardas = list((self.pasta / "pc2").glob("antes_da_restauracao_*"))
        self.assertEqual(len(guardas), 1)
        self.assertTrue((guardas[0] / "recipay.db").is_file())


if __name__ == "__main__":
    unittest.main()
