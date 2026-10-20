"""Matrícula e recuperação de senha com o código Cosampa@<matrícula>."""

import os
import shutil
import tempfile
import unittest
from pathlib import Path

from recipay import config, contas, db
from recipay.auth import (
    autenticar, codigo_recuperacao, criar_usuario_local, definir_matricula, matricula_local, redefinir_senha,
)


class TestRecuperacao(unittest.TestCase):
    def setUp(self):
        self.con = db.conectar(":memory:")
        self.addCleanup(self.con.close)

    def test_codigo_e_redefinicao(self):
        criar_usuario_local(self.con, "Ana", "SUPERVISOR", "senha1", matricula=" 12345 ")
        self.assertEqual(matricula_local(self.con), "12345")
        self.assertEqual(codigo_recuperacao("12345"), "Cosampa@12345")
        redefinir_senha(self.con, "Cosampa@12345", "nova-senha")
        self.assertEqual(autenticar(self.con, "SUPERVISOR", "nova-senha").nome, "Ana")
        with self.assertRaises(ValueError):
            autenticar(self.con, "SUPERVISOR", "senha1")

    def test_aceita_variacoes_de_digitacao(self):
        criar_usuario_local(self.con, "Ana", "SUPERVISOR", "senha1", matricula="ab12")
        redefinir_senha(self.con, " cosampa@ AB12 ", "nova-senha")  # minúsculas e espaços

    def test_codigo_errado_e_senha_curta(self):
        criar_usuario_local(self.con, "Ana", "SUPERVISOR", "senha1", matricula="12345")
        with self.assertRaisesRegex(ValueError, "incorreto"):
            redefinir_senha(self.con, "Cosampa@99999", "nova-senha")
        with self.assertRaisesRegex(ValueError, "pelo menos"):
            redefinir_senha(self.con, "Cosampa@12345", "123")

    def test_matricula_invalida(self):
        for m in ("", "a", "12 34", "1234@"):
            with self.assertRaises(ValueError, msg=m):
                criar_usuario_local(db.conectar(":memory:"), "Ana", "SUPERVISOR", "senha1", matricula=m)

    def test_usuario_antigo_sem_matricula(self):
        criar_usuario_local(self.con, "Ana", "SUPERVISOR", "senha1")
        with self.assertRaisesRegex(ValueError, "sem matrícula"):
            redefinir_senha(self.con, "Cosampa@1", "nova-senha")
        definir_matricula(self.con, "777")
        redefinir_senha(self.con, "Cosampa@777", "nova-senha")

    def test_matricula_aparece_na_lista_de_contas(self):
        pasta = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, pasta, True)
        os.environ["RECIPAY_DADOS"] = str(pasta)
        self.addCleanup(os.environ.pop, "RECIPAY_DADOS", None)
        self.addCleanup(config.definir_conta, None)
        config.definir_conta(contas.nova_pasta())
        con = db.conectar(config.caminho_banco())
        criar_usuario_local(con, "Ana", "SUPERVISOR", "senha1", matricula="4321")
        con.close()
        self.assertEqual([c.matricula for c in contas.listar()], ["4321"])


if __name__ == "__main__":
    unittest.main()
