# Contrato técnico dos arquivos XLSX do Recipay

Este documento registra o formato implementado em `recipay/troca.py` e reutilizado por `recipay/retorno.py`. A versão Windows atual é a referência de compatibilidade.

## Estrutura

O arquivo usa estas abas na exportação de recibos:

- `Recibos`: dados legíveis e valor exibido em reais.
- `Colaboradores`: dados de identificação e chave Pix necessários ao fluxo.
- `Comprovantes`: comprovantes associados aos itens.
- `_recipay`: aba técnica marcada como `veryHidden`; não deve ser removida nem regravada por ferramentas que destruam seus dados.

A aba técnica contém uma linha de cabeçalho com a marca `RECIPAY` e a versão do formato. As linhas seguintes guardam valores fragmentados em três colunas: chave, índice do fragmento e texto. O leitor ordena os fragmentos pelo índice e os concatena.

Chaves técnicas usadas pelo leitor atual:

- `dados`: JSON serializado.
- `hash`: SHA-256 do texto JSON exato em UTF-8.
- `comp:<sha256>`: comprovante em Base64, identificado pelo hash do conteúdo.

O JSON é exportado em UTF-8, com acentos preservados, chaves ordenadas e separadores compactos. A integridade do JSON é verificada antes de processá-lo. Cada comprovante é decodificado e seu SHA-256 comparado com o hash declarado no item.

## Campos principais do lote de recibos

- `versao_formato`, `tipo`, `lote_id`, `gerado_por`, `gerado_em`, `total_centavos`
- `colaboradores`: registros com IDs estáveis e informações necessárias ao pagamento.
- `recibos`: ID, referência curta, colaborador, valor inteiro em centavos, descrição, data, categoria, chave NFC-e quando existente e metadados do comprovante.
- `cancelamentos`: avisos de cancelamento que precisam chegar ao Gestor.

O campo `tipo` diferencia arquivos de recibos de outros tipos de troca. O leitor deve recusar um arquivo sem a aba técnica, sem marca, sem JSON, com versão futura, com hash incorreto ou com comprovante ausente/danificado.

## Validação das abas legíveis

O leitor Windows compara as referências e valores da aba visível com os dados internos. Se os valores exibidos não coincidirem exatamente com o valor em centavos registrado, a importação é bloqueada. O Android deve preservar essa proteção, não confiar somente no JSON oculto e não gravar antes de validar o arquivo inteiro.

## Regras de compatibilidade para Android

1. Não modificar o JSON antes de validar o hash: o hash corresponde aos bytes UTF-8 do texto serializado original.
2. Não usar nome de arquivo ou nome do colaborador como identidade. Usar IDs estáveis e o identificador do lote.
3. Validar todos os recibos, anexos e valores antes de abrir a transação de gravação.
4. Importação repetida deve ignorar IDs já existentes, sem duplicar registros.
5. Preservar valores monetários como inteiros em centavos; não usar ponto flutuante como representação interna.
6. Manter a versão e os nomes de campos existentes. Mudanças futuras exigem versionamento e testes de compatibilidade.
7. Gerar arquivos de retorno que o leitor Windows existente aceite. A compatibilidade só está comprovada após teste real de ida e volta.

## Limites conhecidos desta documentação

Este é um resumo do contrato observado no código; não substitui uma suíte de fixtures reais. O spike Android deve criar arquivos de teste usando o exportador atual e cobrir arquivos de recibos e retorno, acentos, anexos, conteúdo alterado, arquivo inválido e reimportação idempotente.
