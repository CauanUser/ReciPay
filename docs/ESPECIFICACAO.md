# Recipay — Especificação (v0.3, rascunho)

Documento para orientar o desenvolvimento. Os itens marcados com **[DECIDIR]** ainda precisam de definição. Ver a seção 10.

## Decisões tomadas

| # | Decisão |
|---|---|
| D-01 | **Custo zero, sem servidor.** Os dados ficam em cada aparelho, e a troca entre supervisor e gestor é feita por arquivos. |
| D-02 | **Usuários: 3 supervisores e 1 gestor.** Premissa a confirmar: cada pessoa usa o próprio celular. |
| D-03 | **Um Pix pode quitar vários recibos do mesmo colaborador**, inclusive com valores parciais em cada recibo. |
| D-04 | **Os comprovantes vão dentro dos arquivos trocados.** O arquivo de recibos leva os comprovantes das despesas. Pela mesma lógica, o arquivo de retorno leva os comprovantes bancários. |
| D-05 | **Backup manual.** Um botão "Exportar backup" gera um arquivo com todos os dados e comprovantes, e esse arquivo pode ser restaurado depois. |

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
- **Login sem servidor (D-01)**: no primeiro uso, o aparelho é configurado com nome do usuário, perfil e PIN. Depois disso, o login pede o PIN e só libera o perfil gravado. Para trocar o perfil de um aparelho, é preciso reconfigurá-lo.
- Cada usuário tem um **ID (UUID)** que vai em todos os arquivos que ele gera. É assim que o gestor sabe de qual supervisor veio cada lote.
- Como não há servidor, o PIN protege o uso do app no aparelho, mas não impede que alguém instale o app em outro celular. A segurança dos arquivos trocados vem do hash e da prévia antes de importar (seção 8).

## 4. Fluxos

### 4.1 Cadastro de recibos (Supervisor)
1. Escolhe ou cadastra o colaborador.
2. Informa equipe (vem do colaborador e pode ser alterada), valor, descrição e data, e anexa o comprovante.
3. Para cadastro em lote, informa os dados comuns uma vez e depois uma linha por recibo, cada uma com seu comprovante.
4. O recibo fica com status `RASCUNHO` até ser exportado.

### 4.2 Exportação de recibos (Supervisor)
1. Seleciona os recibos e gera o arquivo do lote (ver seção 8). O arquivo leva junto os dados dos colaboradores envolvidos (nome, equipe, chave Pix, tipo e titular), porque o gestor precisa da chave para pagar.
2. Os recibos passam a `ENVIADO`. Após exportados, não podem mais ser editados. Correções só pela regra RN-12.
3. Compartilha o arquivo (WhatsApp, e-mail etc.) usando o compartilhamento do Android.

### 4.3 Importação de recibos (Gestor)
1. Abre o arquivo e vê uma **prévia**: supervisor de origem, quantidade, total, recibos novos e já existentes, e colaboradores novos ou com chave Pix diferente da que o gestor já tem.
2. Confirma. Os recibos novos entram na fila como `PENDENTE`. Recibos já importados são ignorados (RN-08).
3. Se a chave Pix de um colaborador mudou, a prévia mostra a chave antiga e a nova, e o gestor escolhe qual manter.

### 4.4 Pagamento (Gestor)
1. Filtra as pendências por supervisor, equipe ou colaborador.
2. Abre um colaborador e vê os recibos em aberto, com o saldo de cada um e o total, além da chave Pix e do titular.
3. Seleciona um ou mais recibos desse colaborador (D-03). O app sugere o valor total dos saldos, e o gestor pode alterar o valor.
4. Opcionalmente gera o QR Code Pix com o valor da transferência.
5. Faz **um** Pix no app do banco.
6. Registra a **transferência**: data, valor total, comprovante bancário e, opcionalmente, outra chave usada só nela (RN-10).
7. Se o valor for menor que a soma dos saldos, o app distribui automaticamente do recibo mais antigo para o mais novo, e o gestor pode ajustar antes de confirmar (RN-14).
8. O sistema recalcula os saldos. Cada recibo passa a `PARCIAL` ou `PAGO`.

### 4.5 Retorno (Gestor → Supervisor)
1. O gestor escolhe o supervisor e gera o PDF de retorno com as transferências ainda não exportadas para ele. Cada supervisor recebe apenas os pagamentos dos próprios recibos.
2. O supervisor importa e vê uma **prévia**: pagamentos novos, pagamentos já conhecidos e recibos que não são dele ou que ele não reconhece.
3. Ao confirmar, os pagamentos novos são aplicados e os saldos são atualizados. Duplicados são ignorados (RN-08).

## 5. Regras de negócio

| ID | Regra |
|---|---|
| RN-01 | Todo recibo exige comprovante da despesa. |
| RN-02 | Valor do recibo > 0, com duas casas decimais. Valores são guardados em **centavos (inteiro)**. |
| RN-03 | Pagamentos parciais são permitidos. |
| RN-04 | Saldo = valor do recibo − soma dos pagamentos não estornados. Sempre calculado, nunca digitado. |
| RN-05 | O recibo fica `PAGO` somente quando o saldo = 0. |
| RN-06 | O valor aplicado a um recibo não pode ser maior que o saldo dele, e a transferência não pode ser maior que a soma dos saldos selecionados. |
| RN-07 | Toda transferência tem data, valor, comprovante bancário e os recibos relacionados, com o valor aplicado em cada um. |
| RN-08 | Cada recibo e cada pagamento tem um **ID único (UUID)** criado na origem. A importação usa esse ID, e não o nome do arquivo, para não contar nada duas vezes. Reimportar o mesmo conteúdo, mesmo com outro nome, não altera nada. |
| RN-09 | Importar um recibo **não** significa que ele foi pago. |
| RN-10 | O gestor pode usar outra chave Pix em um pagamento. Ela fica gravada **só no pagamento** e não altera o cadastro do colaborador. |
| RN-11 | O QR Code não comprova pagamento. Só um pagamento registrado com comprovante bancário conta. |
| RN-12 | Nada é apagado. Correções viram novos registros (estorno de pagamento, cancelamento de recibo com motivo), e tudo fica no histórico com data, perfil e usuário. |
| RN-13 | Chave Pix e tipo são validados pelo formato (CPF, CNPJ, e-mail, telefone ou chave aleatória). |
| RN-14 | Uma transferência só pode incluir recibos do **mesmo colaborador**, e a soma do que foi aplicado nos recibos deve ser exatamente igual ao valor da transferência. |
| RN-15 | O estorno é feito na transferência inteira: todas as aplicações dela são desfeitas juntas, e os saldos voltam. |

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

Recibo        id (UUID), colaborador_id, supervisor_id, equipe,
              valor_centavos, descricao, data_despesa, comprovante_arquivo,
              status, lote_id, criado_em

Lote          id (UUID), tipo (RECIBOS | RETORNO), gerado_por, gerado_em,
              hash_conteudo, qtd_itens, total_centavos

Usuario       id (UUID), nome, perfil (SUPERVISOR | GESTOR), pin_hash
              (o gestor guarda também os supervisores de quem recebeu arquivos)

Transferencia id (UUID), colaborador_id, valor_centavos, data_pagamento,
              chave_pix_usada, tipo_chave_usada, titular_usado,
              comprovante_arquivo, estornado (bool), estorno_motivo,
              registrado_por, registrado_em

Aplicacao     id (UUID), transferencia_id, recibo_id, valor_centavos
              (um Pix → várias aplicações, uma por recibo)

RetornoEnvio  transferencia_id, supervisor_id, lote_id
              (controla o que já foi exportado para cada supervisor)

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

**Decidido (D-01)**: a v1 funciona **sem servidor** (dados em cada aparelho, troca por arquivos). A sincronização em nuvem só será avaliada numa fase posterior, se for necessária e puder continuar gratuita.

## 10. Decisões em aberto

1. ~~Servidor ou não?~~ Decidido: D-01.
2. ~~Quantidade de usuários~~ Decidido: D-02. **Falta confirmar** que cada pessoa usa o próprio celular.
3. Excel também importável ou só PDF?
4. Comprovantes vão dentro dos arquivos trocados? Sem servidor, se não forem, o gestor só vê o comprovante da despesa no celular do supervisor.
5. ~~Vários supervisores~~ Coberto por D-02 (lotes e retornos identificados por supervisor).
6. ~~Um Pix para vários recibos~~ Decidido: D-03.
7. **Backup**: sem servidor, se o celular for perdido, os dados vão junto. Sugestão: botão "Exportar backup" que gera um arquivo para guardar no Google Drive ou e-mail.
8. **LGPD**: chaves Pix e nomes são dados pessoais. Definir quem acessa, por quanto tempo os dados ficam guardados e a proteção do aparelho.
9. **Repositório**: o projeto é da empresa, mas o repositório está na conta pessoal do GitHub. Confirmar com o coordenador se isso é aceito ou se deve ir para uma conta da empresa.
10. **Colaborador em mais de um supervisor**: se dois supervisores cadastrarem a mesma pessoa, o gestor verá dois registros. Sugestão: o gestor identifica pelo CPF ou pela chave Pix e pode juntar os dois.

## 11. Próximos passos

1. Responder às decisões da seção 10.
2. Fazer os wireframes das telas principais.
3. Criar o projeto Android (estrutura, tema e banco Room).
4. Implementar na ordem: colaboradores → recibos → exportar/importar → pagamentos → retorno.
