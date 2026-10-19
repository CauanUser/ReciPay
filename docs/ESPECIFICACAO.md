# Recipay — Especificação (v0.1, rascunho)

Documento para orientar o desenvolvimento. Os itens marcados com **[DECIDIR]** ainda precisam de definição. Ver a seção 10.

---

## 1. Objetivo e escopo

Controlar recibos de despesas, pagamentos Pix, comprovantes e pendências entre supervisores e gestores, com custo zero.

**Dentro do escopo**
- Cadastro de colaboradores e recibos (com comprovante).
- Exportação e importação de arquivos entre supervisor e gestor.
- Registro de pagamentos Pix, inclusive parciais, com comprovante.
- Geração de QR Code Pix (BR Code estático) para facilitar o pagamento.
- Retorno dos pagamentos ao supervisor e histórico.

**Fora do escopo**
- Fazer transferências ou integrar com bancos.
- Aprovação prévia de recibos (importar já coloca na fila).
- Confirmar automaticamente que um Pix foi pago.

## 2. Glossário

| Termo | Significado |
|---|---|
| Recibo | Despesa de um colaborador a ser reembolsada. |
| Comprovante da despesa | Foto ou PDF da nota/cupom. Obrigatório no recibo. |
| Pagamento | Uma transferência Pix registrada pelo gestor, total ou parcial. |
| Comprovante bancário | Foto ou PDF do comprovante do Pix. Obrigatório no pagamento. |
| Saldo | Valor do recibo menos a soma dos pagamentos válidos. |
| Lote | Conjunto de recibos exportados juntos em um arquivo. |
| Retorno | Arquivo do gestor com os pagamentos realizados. |

## 3. Perfis e acesso

| Perfil | Pode |
|---|---|
| Supervisor | Cadastrar e editar colaboradores e recibos, exportar recibos, importar retorno, ver histórico. |
| Gestor | Importar recibos, ver pendências, registrar pagamentos, exportar retorno, ver pagamentos concluídos. |

- A tela inicial oferece a escolha do perfil e o login. **A escolha não concede permissões**: o sistema usa o perfil gravado na conta.
- **[DECIDIR]** Como funciona o login sem servidor (ver seção 10, itens 1 e 2).

## 4. Fluxos

### 4.1 Cadastro de recibos (Supervisor)
1. Escolhe ou cadastra o colaborador.
2. Informa equipe (vem do colaborador e pode ser alterada), valor, descrição e data, e anexa o comprovante.
3. Para cadastro em lote, informa os dados comuns uma vez e depois uma linha por recibo, cada uma com seu comprovante.
4. O recibo fica com status `RASCUNHO` até ser exportado.

### 4.2 Exportação de recibos (Supervisor)
1. Seleciona os recibos e gera o arquivo do lote (ver seção 8).
2. Os recibos passam a `ENVIADO`. Após exportados, não podem mais ser editados. Correções só pela regra RN-12.
3. Compartilha o arquivo (WhatsApp, e-mail etc.) usando o compartilhamento do Android.

### 4.3 Importação de recibos (Gestor)
1. Abre o arquivo e vê uma **prévia**: quantidade, total, recibos novos e já existentes.
2. Confirma. Os recibos novos entram na fila como `PENDENTE`. Recibos já importados são ignorados (RN-08).

### 4.4 Pagamento (Gestor)
1. Filtra as pendências por equipe ou colaborador.
2. Abre o recibo: vê o saldo, a chave Pix e o titular.
3. Opcionalmente gera o QR Code Pix com o valor (pode ser parcial).
4. Faz o Pix no app do banco.
5. Registra o pagamento: data, valor, comprovante bancário e, opcionalmente, outra chave usada só neste pagamento (RN-10).
6. O sistema recalcula o saldo. O status passa a `PARCIAL` ou `PAGO`.
7. Permite pagar vários recibos do mesmo colaborador com uma única transferência **[DECIDIR]** (seção 10, item 6).

### 4.5 Retorno (Gestor → Supervisor)
1. O gestor seleciona os pagamentos (por padrão, os que ainda não foram exportados) e gera o PDF de retorno.
2. O supervisor importa e vê uma **prévia**: pagamentos novos, pagamentos já conhecidos e recibos desconhecidos.
3. Ao confirmar, os pagamentos novos são aplicados e os saldos são atualizados. Duplicados são ignorados (RN-08).

## 5. Regras de negócio

| ID | Regra |
|---|---|
| RN-01 | Todo recibo exige comprovante da despesa. |
| RN-02 | Valor do recibo > 0, com duas casas decimais. Valores são guardados em **centavos (inteiro)**. |
| RN-03 | Pagamentos parciais são permitidos. |
| RN-04 | Saldo = valor do recibo − soma dos pagamentos não estornados. Sempre calculado, nunca digitado. |
| RN-05 | O recibo fica `PAGO` somente quando o saldo = 0. |
| RN-06 | Um pagamento não pode ser maior que o saldo atual. |
| RN-07 | Todo pagamento tem data, valor, comprovante bancário e o recibo relacionado. |
| RN-08 | Cada recibo e cada pagamento tem um **ID único (UUID)** criado na origem. A importação usa esse ID, e não o nome do arquivo, para não contar nada duas vezes. Reimportar o mesmo conteúdo, mesmo com outro nome, não altera nada. |
| RN-09 | Importar um recibo **não** significa que ele foi pago. |
| RN-10 | O gestor pode usar outra chave Pix em um pagamento. Ela fica gravada **só no pagamento** e não altera o cadastro do colaborador. |
| RN-11 | O QR Code não comprova pagamento. Só um pagamento registrado com comprovante bancário conta. |
| RN-12 | Nada é apagado. Correções viram novos registros (estorno de pagamento, cancelamento de recibo com motivo), e tudo fica no histórico com data, perfil e usuário. |
| RN-13 | Chave Pix e tipo são validados pelo formato (CPF, CNPJ, e-mail, telefone ou chave aleatória). |

### Status do recibo

```
RASCUNHO → ENVIADO → PENDENTE → PARCIAL → PAGO
                         ↘ CANCELADO (com motivo)
```

`RASCUNHO` e `ENVIADO` existem no aparelho do supervisor. A partir de `PENDENTE`, o status vem do gestor.

## 6. Telas

| Supervisor | Gestor |
|---|---|
| Início / Dashboard (pendente, parcial, pago, total em aberto) | Início / Dashboard (fila, pago no mês) |
| Lista de recibos (filtros por status, equipe, colaborador e período) | Importar arquivo (com prévia) |
| Cadastrar recibos (individual e lote) | Pagamentos pendentes (filtros) |
| Exportar recibos | Realizar pagamento (QR Code + registro) |
| Retorno e histórico (importar retorno com prévia) | Pagamentos concluídos (+ exportar retorno) |

Telas comuns: Login, Colaboradores (cadastro e importação de planilha), Detalhe do recibo (dados, comprovantes, pagamentos, histórico).

**Visual**: branco e azul como base, verde para sucesso, amarelo/laranja para parcial e vermelho só para erros.

## 7. Modelo de dados (inicial)

```
Colaborador   id (UUID), nome, equipe, chave_pix, tipo_chave, titular_pix,
              ativo, criado_em, atualizado_em

Recibo        id (UUID), colaborador_id, equipe, valor_centavos, descricao,
              data_despesa, comprovante_arquivo, status, lote_id,
              criado_por, criado_em

Lote          id (UUID), tipo (RECIBOS | RETORNO), gerado_por, gerado_em,
              hash_conteudo, qtd_itens, total_centavos

Pagamento     id (UUID), recibo_id, valor_centavos, data_pagamento,
              chave_pix_usada, tipo_chave_usada, titular_usado,
              comprovante_arquivo, estornado (bool), estorno_motivo,
              registrado_por, registrado_em, exportado_em_lote_id

Importacao    id, lote_id, arquivo_nome, hash_arquivo, importado_por,
              importado_em, novos, ignorados

Historico     id, entidade, entidade_id, acao, detalhes (JSON),
              usuario, perfil, data_hora
```

Comprovantes ficam como arquivos no armazenamento do app. O banco guarda só o caminho e o hash do arquivo.

## 8. Arquivos de importação e exportação

O PDF serve para leitura humana, mas **ler dados de um PDF é frágil**. Proposta:

- **Todo arquivo exportado leva os dados estruturados embutidos** (JSON anexado dentro do próprio PDF), além da parte visual.
- Na importação, o app lê o JSON embutido. Se ele não existir (PDF de outra origem), recusa com uma mensagem clara.
- O JSON tem `versao_formato`, `lote_id`, `gerado_em`, `gerado_por` e a lista de itens com seus UUIDs, mais um `hash` do conteúdo para detectar arquivo alterado.
- **Excel**: export opcional para conferência e planilhas. **[DECIDIR]** se o Excel também pode ser importado ou se fica só como relatório.
- **Comprovantes**: **[DECIDIR]** se as imagens vão dentro do arquivo (fica maior) ou ficam só no aparelho de origem.

### Planilha de colaboradores (importação)

Colunas: `nome`, `equipe`, `chave_pix`, `tipo_chave`, `titular_pix`. A importação mostra uma prévia com erros por linha antes de gravar.

## 9. Tecnologia (proposta)

| Item | Proposta | Custo |
|---|---|---|
| Linguagem/UI | Kotlin + Jetpack Compose (Material 3) | Grátis |
| Banco local | Room (SQLite) | Grátis |
| PDF | API nativa do Android (`PdfDocument`) **[DECIDIR]** como embutir o JSON | Grátis |
| Excel | Biblioteca leve de .xlsx **[DECIDIR]** | Grátis |
| QR Code Pix | BR Code estático (padrão EMV do Banco Central) + gerador de QR (ex.: ZXing) | Grátis |
| Build | Android Studio + GitHub | Grátis |

**Sugestão para a v1**: funcionar **sem servidor** (dados em cada aparelho, troca por arquivos). Isso já combina com o fluxo de exportar e importar e mantém o custo em R$ 0. A sincronização em nuvem fica para uma fase posterior, se for necessária.

## 10. Decisões em aberto

1. **Servidor ou não?** Sem servidor (v1 sugerida) ou com sincronização (ex.: planos gratuitos de Firebase ou Supabase, que têm limites).
2. **Login**: sem servidor, cada aparelho teria usuário + PIN local. Quantos supervisores e gestores existem? Um aparelho é usado por mais de uma pessoa?
3. Excel também importável ou só PDF?
4. Comprovantes vão dentro dos arquivos trocados?
5. Mais de um supervisor enviando para o mesmo gestor? Isso afeta os filtros e a identificação dos lotes.
6. Um Pix pode quitar vários recibos de uma vez?
7. Backup dos dados do aparelho (ex.: exportar backup manualmente).
8. **LGPD**: chaves Pix e nomes são dados pessoais. Definir quem acessa, por quanto tempo os dados ficam guardados e a proteção do aparelho.
9. **Repositório**: o projeto é da empresa, mas o repositório está na conta pessoal do GitHub. Confirmar com o coordenador se isso é aceito ou se deve ir para uma conta da empresa.

## 11. Próximos passos

1. Responder às decisões da seção 10.
2. Fazer os wireframes das telas principais.
3. Criar o projeto Android (estrutura, tema e banco Room).
4. Implementar na ordem: colaboradores → recibos → exportar/importar → pagamentos → retorno.
