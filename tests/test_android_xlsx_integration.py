"""Integração entre o exportador Windows real e o leitor de prévia Android."""

import os
import tempfile
import unittest
from datetime import date
from pathlib import Path

from PIL import Image

from android_poc.recipay_xlsx import ler_previa
from recipay import colaboradores, db, recibos, troca
from recipay.auth import criar_usuario_local


class TestAndroidXlsxIntegracaoReal(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.pasta = Path(self.temp.name)
        self.dados_originais = os.environ.get("RECIPAY_DADOS")
        os.environ["RECIPAY_DADOS"] = str(self.pasta / "dados_supervisor")
        self.addCleanup(self._restaurar_pasta_dados)

        self.con = db.conectar(":memory:")
        self.addCleanup(self.con.close)
        self.supervisor = criar_usuario_local(
            self.con, "Supervisora de Teste", "SUPERVISOR", "123456"
        )
        self.colaborador = colaboradores.criar(
            self.con,
            self.supervisor,
            {
                "nome": "Colaborador de Teste",
                "equipe": "Equipe A",
                "tipo_chave": "EMAIL",
                "chave_pix": "teste@example.com",
                "titular_pix": "Colaborador de Teste",
            },
        )
        self.comprovante = self.pasta / "comprovante.png"
        Image.new("RGB", (100, 120), "white").save(self.comprovante)
        self.xlsx = self.pasta / "exportacao_windows.xlsx"

    def _restaurar_pasta_dados(self):
        if self.dados_originais is None:
            os.environ.pop("RECIPAY_DADOS", None)
        else:
            os.environ["RECIPAY_DADOS"] = self.dados_originais

    def test_exportacao_real_do_windows_e_lida_pelo_prototipo_android(self):
        recibo = recibos.criar(
            self.con,
            self.supervisor,
            {
                "colaborador_id": self.colaborador.id,
                "valor_centavos": 5590,
                "descricao": "Despesa de teste",
                "data_despesa": date.today().isoformat(),
            },
            str(self.comprovante),
        )

        resultado = troca.exportar_recibos(
            self.con, self.supervisor, [recibo.id], self.xlsx
        )
        self.assertTrue(self.xlsx.is_file())
        self.assertEqual((resultado.quantidade, resultado.total_centavos), (1, 5590))

        previa = ler_previa(self.xlsx)
        self.assertEqual(previa.arquivo, self.xlsx.name)
        self.assertEqual(previa.supervisor, "Supervisora de Teste")
        self.assertEqual(previa.quantidade, 1)
        self.assertEqual(previa.total_centavos, 5590)
        self.assertEqual(previa.itens[0].referencia, recibo.id.replace("-", "")[:8].upper())
        self.assertEqual(previa.itens[0].colaborador, "Colaborador de Teste")
        self.assertTrue(previa.itens[0].comprovante_ok)


if __name__ == "__main__":
    unittest.main()
