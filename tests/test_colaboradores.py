"""Testes de chave Pix, cadastro de colaboradores e importação de planilha."""

import json
import tempfile
import unittest
from pathlib import Path

from openpyxl import Workbook

from recipay import colaboradores, db, planilha_colaboradores
from recipay.auth import criar_usuario_local
from recipay.pix import cnpj_valido, cpf_valido, detectar_tipo, formatar_chave, normalizar_chave

# Documentos gerados apenas para teste (dígitos verificadores válidos, não são de pessoas reais).
CPF_OK = "52998224725"
CNPJ_OK = "11222333000181"


class TestPix(unittest.TestCase):
    def test_cpf(self):
        self.assertTrue(cpf_valido("529.982.247-25"))
        self.assertFalse(cpf_valido("529.982.247-26"))
        self.assertFalse(cpf_valido("111.111.111-11"))

    def test_cnpj(self):
        self.assertTrue(cnpj_valido("11.222.333/0001-81"))
        self.assertFalse(cnpj_valido("11.222.333/0001-82"))

    def test_normalizar(self):
        self.assertEqual(normalizar_chave("CPF", "529.982.247-25"), CPF_OK)
        self.assertEqual(normalizar_chave("EMAIL", " Maria@Exemplo.COM "), "maria@exemplo.com")
        self.assertEqual(normalizar_chave("TELEFONE", "(11) 99999-8888"), "+5511999998888")
        self.assertEqual(normalizar_chave("TELEFONE", "+55 11 99999-8888"), "+5511999998888")
        self.assertEqual(
            normalizar_chave("ALEATORIA", "123E4567-E89B-42D3-A456-426614174000"),
            "123e4567-e89b-42d3-a456-426614174000",
        )
        for tipo, chave in (("CPF", "123"), ("EMAIL", "sem-arroba"), ("TELEFONE", "123"),
                            ("ALEATORIA", "abc")):
            with self.assertRaises(ValueError):
                normalizar_chave(tipo, chave)

    def test_cpf_que_perdeu_zero_no_excel(self):
        # 012.345.678-90 vira 1234567890 no Excel; o zero à esquerda é recuperado.
        self.assertEqual(normalizar_chave("CPF", "1234567890"), "01234567890")

    def test_detectar_e_formatar(self):
        self.assertEqual(detectar_tipo("a@b.com"), "EMAIL")
        self.assertEqual(detectar_tipo(CPF_OK), "CPF")
        self.assertEqual(detectar_tipo(CNPJ_OK), "CNPJ")
        self.assertEqual(detectar_tipo("+5511999998888"), "TELEFONE")
        self.assertIsNone(detectar_tipo("11999998888"))  # ambíguo
        self.assertEqual(formatar_chave("CPF", CPF_OK), "529.982.247-25")
        self.assertEqual(formatar_chave("TELEFONE", "+5511999998888"), "+55 (11) 99999-8888")


class TestColaboradores(unittest.TestCase):
    def setUp(self):
        self.con = db.conectar(":memory:")
        self.addCleanup(self.con.close)
        self.sup = criar_usuario_local(self.con, "Ana", "SUPERVISOR", "123456")

    def dados(self, **extra):
        base = {"nome": "João  da Silva", "equipe": "Norte", "tipo_chave": "CPF",
                "chave_pix": "529.982.247-25", "titular_pix": "João da Silva"}
        return {**base, **extra}

    def test_criar_e_listar(self):
        c = colaboradores.criar(self.con, self.sup, self.dados())
        self.assertEqual(c.nome, "João da Silva")  # espaços extras removidos
        self.assertEqual(c.chave_pix, CPF_OK)
        self.assertEqual([x.id for x in colaboradores.listar(self.con, busca="joão")], [c.id])
        self.assertEqual(colaboradores.equipes(self.con), ["Norte"])

    def test_chave_duplicada(self):
        colaboradores.criar(self.con, self.sup, self.dados())
        with self.assertRaisesRegex(ValueError, "já está cadastrada"):
            colaboradores.criar(self.con, self.sup, self.dados(nome="Outra Pessoa"))

    def test_gestor_nao_cadastra(self):
        con = db.conectar(":memory:")
        self.addCleanup(con.close)
        gestor = criar_usuario_local(con, "Bruno", "GESTOR", "123456")
        with self.assertRaises(PermissionError):
            colaboradores.criar(con, gestor, self.dados())

    def test_alterar_registra_historico(self):
        c = colaboradores.criar(self.con, self.sup, self.dados())
        colaboradores.atualizar(self.con, self.sup, c.id, self.dados(equipe="Sul"))
        linha = self.con.execute(
            "SELECT detalhes FROM historico WHERE entidade_id = ? AND acao = 'ALTERADO'", (c.id,)
        ).fetchone()
        self.assertEqual(json.loads(linha[0]), {"equipe": {"antes": "Norte", "depois": "Sul"}})

    def test_desativar_nao_apaga(self):
        c = colaboradores.criar(self.con, self.sup, self.dados())
        colaboradores.definir_ativo(self.con, self.sup, c.id, False)
        self.assertEqual(colaboradores.listar(self.con), [])
        self.assertEqual(len(colaboradores.listar(self.con, incluir_inativos=True)), 1)
        # Com o antigo inativo, a mesma chave pode ser usada por um novo cadastro.
        colaboradores.criar(self.con, self.sup, self.dados(nome="João Novo"))


class TestPlanilha(unittest.TestCase):
    def setUp(self):
        self.con = db.conectar(":memory:")
        self.addCleanup(self.con.close)
        self.sup = criar_usuario_local(self.con, "Ana", "SUPERVISOR", "123456")
        self.pasta = tempfile.TemporaryDirectory()

    def tearDown(self):
        self.pasta.cleanup()

    def planilha(self, linhas, cabecalho=("Nome", "Equipe", "Chave PIX", "Tipo", "Titular")):
        wb = Workbook()
        ws = wb.active
        ws.append(list(cabecalho))
        for l in linhas:
            ws.append(list(l))
        caminho = Path(self.pasta.name) / "colab.xlsx"
        wb.save(caminho)
        return caminho

    def test_previa_e_gravacao(self):
        caminho = self.planilha([
            ("Maria Souza", "Norte", "maria@x.com", "E-mail", "Maria Souza"),
            ("Pedro Lima", "Sul", float(CPF_OK), "", ""),          # tipo detectado, titular = nome
            ("Carla Dias", "Sul", "123", "CPF", "Carla"),           # CPF inválido
            ("Rita Alves", "Norte", "MARIA@x.com", "email", "Rita"), # chave repetida
            (None, None, None, None, None),                          # linha em branco
            ("Zé", "Norte", "ze@x.com", "Fax", "Zé"),                 # tipo desconhecido
        ])
        previa = planilha_colaboradores.ler(self.con, caminho)
        self.assertEqual([l.numero for l in previa], [2, 3, 4, 5, 7])
        self.assertEqual([l.valida for l in previa], [True, True, False, False, False])
        self.assertIn("Titular vazio", " ".join(previa[1].avisos))
        self.assertIn("linha 2", previa[3].erros[0])

        self.assertEqual(planilha_colaboradores.gravar(self.con, self.sup, previa), 2)
        self.assertEqual(len(colaboradores.listar(self.con)), 2)

        # Importar de novo: as chaves já existem, nada é gravado em dobro.
        previa2 = planilha_colaboradores.ler(self.con, caminho)
        self.assertEqual(planilha_colaboradores.gravar(self.con, self.sup, previa2), 0)

    def test_coluna_obrigatoria_faltando(self):
        caminho = self.planilha([("Maria", "Norte")], cabecalho=("Nome", "Equipe"))
        with self.assertRaisesRegex(ValueError, "chave_pix"):
            planilha_colaboradores.ler(self.con, caminho)

    def test_modelo_pode_ser_importado(self):
        caminho = Path(self.pasta.name) / "modelo.xlsx"
        planilha_colaboradores.salvar_modelo(caminho)
        previa = planilha_colaboradores.ler(self.con, caminho)
        self.assertEqual(len(previa), 1)
        self.assertTrue(previa[0].valida)


if __name__ == "__main__":
    unittest.main()
