"""Testes do banco e do login. Rodar com: python -m unittest"""

import sqlite3
import unittest

from recipay import db
from recipay.auth import autenticar, conferir_senha, criar_usuario_local, gerar_hash, usuario_local


class TestBanco(unittest.TestCase):
    def setUp(self):
        self.con = db.conectar(":memory:")
        self.addCleanup(self.con.close)

    def test_schema_criado(self):
        versao = self.con.execute("PRAGMA user_version").fetchone()[0]
        self.assertEqual(versao, db.VERSAO_SCHEMA)

    def test_reabrir_nao_recria(self):
        db._migrar(self.con)  # não deve dar erro de tabela já existente

    def test_saldo_com_pagamento_parcial_e_estorno(self):
        sup = criar_usuario_local(self.con, "Ana", "SUPERVISOR", "123456")
        c = self.con
        c.execute(
            "INSERT INTO colaborador VALUES ('col', ?, 'João', 'Equipe A', 'joao@x.com',"
            " 'EMAIL', 'João', 1, ?, ?)", (sup.id, db.agora(), db.agora()),
        )
        for rid, valor in (("r1", 10000), ("r2", 5000)):
            c.execute(
                "INSERT INTO recibo (id, colaborador_id, supervisor_id, equipe, valor_centavos,"
                " descricao, data_despesa, comprovante_arquivo, comprovante_hash, status, criado_em)"
                " VALUES (?, 'col', ?, 'Equipe A', ?, 'Almoço', '2026-10-01', 'a.jpg', 'h', 'PENDENTE', ?)",
                (rid, sup.id, valor, db.agora()),
            )
        # Um Pix de R$ 120,00 quitando r1 (100,00) e parte de r2 (20,00).
        c.execute(
            "INSERT INTO transferencia (id, colaborador_id, valor_centavos, data_pagamento,"
            " chave_pix_usada, tipo_chave_usada, titular_usado, comprovante_arquivo,"
            " comprovante_hash, registrado_por, registrado_em)"
            " VALUES ('t1', 'col', 12000, '2026-10-02', 'joao@x.com', 'EMAIL', 'João', 'p.pdf', 'h', ?, ?)",
            (sup.id, db.agora()),
        )
        c.execute("INSERT INTO aplicacao VALUES ('a1', 't1', 'r1', 10000)")
        c.execute("INSERT INTO aplicacao VALUES ('a2', 't1', 'r2', 2000)")

        saldos = dict(c.execute("SELECT recibo_id, saldo_centavos FROM recibo_saldo").fetchall())
        self.assertEqual(saldos, {"r1": 0, "r2": 3000})

        c.execute("UPDATE transferencia SET estornado = 1 WHERE id = 't1'")
        saldos = dict(c.execute("SELECT recibo_id, saldo_centavos FROM recibo_saldo").fetchall())
        self.assertEqual(saldos, {"r1": 10000, "r2": 5000})

    def test_valor_zero_recusado(self):
        sup = criar_usuario_local(self.con, "Ana", "SUPERVISOR", "123456")
        self.con.execute(
            "INSERT INTO colaborador VALUES ('col', ?, 'João', 'A', 'x', 'EMAIL', 'João', 1, ?, ?)",
            (sup.id, db.agora(), db.agora()),
        )
        with self.assertRaises(sqlite3.IntegrityError):
            self.con.execute(
                "INSERT INTO recibo (id, colaborador_id, supervisor_id, equipe, valor_centavos,"
                " descricao, data_despesa, comprovante_arquivo, comprovante_hash, criado_em)"
                " VALUES ('r', 'col', ?, 'A', 0, 'x', '2026-10-01', 'a', 'h', ?)",
                (sup.id, db.agora()),
            )


class TestLogin(unittest.TestCase):
    def setUp(self):
        self.con = db.conectar(":memory:")
        self.addCleanup(self.con.close)

    def test_hash_de_senha(self):
        h = gerar_hash("segredo1")
        self.assertTrue(conferir_senha("segredo1", h))
        self.assertFalse(conferir_senha("errada", h))
        self.assertNotIn("segredo1", h)

    def test_configuracao_e_login(self):
        self.assertIsNone(usuario_local(self.con))
        criar_usuario_local(self.con, "Bruno", "GESTOR", "abcdef")
        self.assertEqual(usuario_local(self.con).perfil, "GESTOR")
        self.assertEqual(autenticar(self.con, "GESTOR", "abcdef").nome, "Bruno")

    def test_escolher_outro_perfil_nao_da_acesso(self):
        criar_usuario_local(self.con, "Bruno", "GESTOR", "abcdef")
        with self.assertRaisesRegex(ValueError, "Gestor"):
            autenticar(self.con, "SUPERVISOR", "abcdef")

    def test_senha_errada(self):
        criar_usuario_local(self.con, "Bruno", "GESTOR", "abcdef")
        with self.assertRaisesRegex(ValueError, "Senha incorreta"):
            autenticar(self.con, "GESTOR", "errada")

    def test_senha_curta_e_segundo_usuario(self):
        with self.assertRaises(ValueError):
            criar_usuario_local(self.con, "Bruno", "GESTOR", "123")
        criar_usuario_local(self.con, "Bruno", "GESTOR", "abcdef")
        with self.assertRaisesRegex(ValueError, "já está configurado"):
            criar_usuario_local(self.con, "Outro", "SUPERVISOR", "abcdef")


if __name__ == "__main__":
    unittest.main()
