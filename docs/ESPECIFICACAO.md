# Recipay — Especificação (v0.5, rascunho)

Documento para orientar o desenvolvimento. Os itens marcados com **[DECIDIR]** ainda precisam de definição. Ver a seção 10.

## Decisões tomadas

| # | Decisão |
|---|---|
| D-01 | **Custo zero, sem servidor.** Os dados ficam em cada computador, e a troca entre supervisor e gestor é feita por arquivos. |
| D-02 | **Usuários: 3 supervisores e 1 gestor.** Premissa a confirmar: cada pessoa usa o próprio computador. |
| D-03 | **Um Pix pode quitar vários recibos do mesmo colaborador**, inclusive com valores parciais em cada recibo. |
| D-04 | **Os comprovantes vão dentro dos arquivos trocados.** O arquivo de recibos leva os comprovantes das despesas. Pela mesma lógica, o arquivo de retorno leva os comprovantes bancários. |
| D-05 | **Backup manual.** Um botão "Exportar backup" gera um arquivo com todos os dados e comprovantes, e esse arquivo pode ser restaurado depois. |
| D-06 | **Plataforma: programa para PC com Windows** (substitui o app Android). Distribuído como pasta portátil com `Recipay.exe`, que roda sem instalação nem permissão de administrador. |
| D-07 | **Os arquivos trocados são planilhas Excel (.xlsx)**, tanto o de recibos (supervisor → gestor) quanto o de retorno (gestor → supervisor). O PDF deixa de ser o formato de troca. |

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
- **Login sem servidor (D-01)**: no primeiro uso, o programa é configurado no computador com nome do usuário, perfil e senha. Depois disso, o login pede a senha e só libera o perfil gravado. Para trocar o perfil de um computador, é preciso reconfigurá-lo.
- Os dados ficam na pasta do usuário do Windows (`%LOCALAPPDATA%\Recipay`), separados por usuário do Windows.
- Cada usuário tem um **ID (UUID)** que vai em todos os arquivos que ele gera. É assim que o gestor sabe de qual supervisor veio cada lote.
- Como não há servidor, a senha protege o uso do programa naquele computador, mas não impede que alguém instale o programa em outro PC. A segurança dos arquivos trocados vem do hash e da prévia antes de importar (seção 8).

## 4. Fluxos

### 4.1 Cadastro de recibos (Supervisor)
1. Escolhe ou cadastra o colaborador.
2. Informa equipe (vem do colaborador e pode ser alterada), valor, descrição e data, e anexa o comprovante.
3. Para cadastro em lote, informa os dados comuns uma vez e depois uma linha por recibo, cada uma com seu comprovante.
4. O recibo fica com status `RASCUNHO` até ser exportado.

### 4.2 Exportação de recibos (Supervisor)
1. Seleciona os recibos e gera o arquivo do lote (ver seção 8). O arquivo leva junto os dados dos colaboradores envolvidos (nome, equipe, chave Pix, tipo e titular), porque o gestor precisa da chave para pagar.
2. Os recibos passam a `ENVIADO`. Após exportados, não podem mais ser editados. Correções só pela regra RN-12.
3. Escolhe onde salvar o arquivo e o envia ao gestor pelo meio que preferir (e-mail, Teams, WhatsApp Web, pendrive). Depois de salvar, o programa oferece abrir a pasta do arquivo.

### 4.3 Importação de recibos (Gestor)
1. Abre o arquivo e vê uma **prévia**: supervisor de origem, quantidade, total, recibos novos e já existentes, e colaboradores novos ou com chave Pix diferente da que o gestor já tem.
2. Confirma. Os recibos novos entram na fila como `PENDENTE`. Recibos já importados são ignorados (RN-08).
3. Se a chave Pix de um colaborador mudou, a prévia mostra a chave antiga e a nova, e o gestor escolhe qual manter.

### 4.4 Pagamento (Gestor)
1. Filtra as pendências por supervisor, equipe ou colaborador.
2. Abre um colaborador e vê os recibos em aberto, com o saldo de cada um e o total, além da chave Pix e do titular.
3. Seleciona um ou mais recibos desse colaborador (D-03). O programa sugere o valor total dos saldos, e o gestor pode alterar o valor.
4. Opcionalmente gera o QR Code Pix com o valor da transferência.
5. Faz **um** Pix no app do banco.
6. Registra a **transferência**: data, valor total, comprovante bancário e, opcionalmente, outra chave usada só nela (RN-10).
7. Se o valor for menor que a soma dos saldos, o programa distribui automaticamente do recibo mais antigo para o mais novo, e o gestor pode ajustar antes de confirmar (RN-14).
8. O sistema recalcula os saldos. Cada recibo passa a `PARCIAL` ou `PAGO`.

### 4.5 Retorno (Gestor → Supervisor)
1. O gestor escolhe o supervisor e gera a planilha de retorno com as transferências ainda não exportadas para ele. Cada supervisor recebe apenas os pagamentos dos próprios recibos.
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

`RASCUNHO` e `ENVIADO` existem no computador do supervisor. A partir de `PENDENTE`, o status vem do gestor.

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

Usuario       id (UUID), nome, perfil (SUPERVISOR | GESTOR), senha_hash
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

Comprovantes ficam como arquivos no pasta de dados do programa. O banco guarda só o caminho e o hash do arquivo.

## 8. Arquivos de importação e exportação (D-07: Excel)

Os arquivos trocados são planilhas `.xlsx` que abrem normalmente no Excel. Cada arquivo tem:

| Aba | Para quem | Conteúdo |
|---|---|---|
| **Recibos** (ou **Pagamentos**, no retorno) | Pessoas | Tabela legível: colaborador, equipe, data, descrição, valor, situação. Totais no fim. |
| **Colaboradores** | Pessoas | Nome, equipe, tipo e chave Pix, titular (só no arquivo de recibos). |
| **Comprovantes** | Pessoas | Miniatura de cada comprovante em foto, com a referência do recibo. Comprovantes em PDF aparecem como "PDF anexado". |
| **_recipay** (oculta) | Programa | Dados completos em JSON: `versao_formato`, `tipo` (RECIBOS ou RETORNO), `lote_id`, `gerado_por` (ID e nome), `gerado_em`, itens com UUIDs, e os comprovantes originais (foto reduzida ou PDF) em base64, cada um com seu hash SHA-256. |

Regras:

- **A importação usa só a aba oculta.** As abas visíveis são para conferência.
- **Planilha alterada:** se alguém mudar valores nas abas visíveis depois da exportação, a prévia da importação avisa a diferença e bloqueia o lote. O arquivo também tem um hash do conteúdo da aba oculta, para detectar edição.
- **Planilha de outra origem** (sem a aba `_recipay`) é recusada com uma mensagem clara. Planilhas de colaboradores continuam com a importação própria (seção 4.1 do cadastro).
- **Comprovantes (D-04):** fotos são reduzidas ao serem anexadas (lado maior com no máximo 1600 px, em JPEG). PDFs são mantidos como estão. Na importação, um comprovante com hash diferente do registrado bloqueia o item.
- **Tamanho:** se o arquivo passar de cerca de **20 MB** (limite comum de e-mail), o programa avisa e sugere dividir em mais de um lote.
- **Nome sugerido:** `recibos_<supervisor>_<data>.xlsx` e `retorno_<supervisor>_<data>.xlsx`. O nome do arquivo não importa para evitar duplicidade (RN-08).

### Backup (D-05)

- Em Configurações, o botão **"Exportar backup"** gera um único arquivo `.recipay-backup` com o banco de dados, os comprovantes e a configuração do usuário.
- O arquivo é **criptografado com uma senha de backup** escolhida pelo usuário, porque contém nomes e chaves Pix (LGPD). Sem a senha, não é possível restaurar.
- O usuário escolhe onde salvar o arquivo, por exemplo num pendrive, no OneDrive ou no Google Drive. Não use a mesma pasta dos dados do programa.
- **"Restaurar backup"** só está disponível na configuração inicial do programa num computador ou depois de uma confirmação explícita, porque substitui todos os dados.
- O programa mostra um lembrete se o último backup tiver mais de 7 dias.

### Planilha de colaboradores (importação)

Colunas: `nome`, `equipe`, `chave_pix`, `tipo_chave`, `titular_pix`. A importação mostra uma prévia com erros por linha antes de gravar.

## 9. Tecnologia (D-06: PC com Windows)

| Item | Escolha | Custo |
|---|---|---|
| Linguagem | Python 3 | Grátis |
| Interface | PySide6 (Qt), com tema próprio em branco e azul | Grátis (LGPL) |
| Banco local | SQLite (já vem com o Python) | Grátis |
| Excel (troca de arquivos e planilha de colaboradores) | openpyxl | Grátis (MIT) |
| Imagens | Pillow (reduzir fotos) | Grátis |
| QR Code Pix | BR Code estático (padrão EMV do Banco Central) + biblioteca `qrcode` | Grátis |
| Criptografia do backup | `cryptography` | Grátis |
| Gerar o .exe | PyInstaller (pasta portátil, sem instalador) | Grátis |
| Editor | VS Code + GitHub | Grátis |

**Por que Python + PySide6:** todas as peças são gratuitas e maduras, e lidam bem com PDF, Excel e imagens. O resultado é um `.exe` portátil, que roda mesmo em PCs da empresa onde não é possível instalar programas. Os usuários finais **não precisam instalar Python**.

**Comprovantes no PC:** o usuário anexa o arquivo (JPG, PNG ou PDF) pelo botão "Anexar", **arrastando e soltando** o arquivo na tela ou **colando** uma imagem copiada (Ctrl+V), por exemplo uma captura de tela.

**Layout:** uma janela com menu lateral fixo (as telas da seção 6) e tabelas com filtro e ordenação. Pensado para mouse e teclado, com tamanho mínimo de 1280×720.

**Decidido (D-01)**: a v1 funciona **sem servidor** (dados em cada computador, troca por arquivos). A sincronização em nuvem só será avaliada numa fase posterior, se for necessária e puder continuar gratuita.

## 10. Decisões em aberto

1. ~~Servidor ou não?~~ Decidido: D-01.
2. ~~Quantidade de usuários~~ Decidido: D-02. **Falta confirmar** que cada pessoa usa o próprio computador.
3. ~~Formato dos arquivos~~ Decidido: D-07 (Excel).
4. ~~Comprovantes nos arquivos~~ Decidido: D-04.
5. ~~Vários supervisores~~ Coberto por D-02 (lotes e retornos identificados por supervisor).
6. ~~Um Pix para vários recibos~~ Decidido: D-03.
7. ~~Backup~~ Decidido: D-05.
8. **LGPD**: chaves Pix e nomes são dados pessoais. Definir quem acessa, por quanto tempo os dados ficam guardados e a proteção do computador.
9. **Repositório**: o projeto é da empresa, mas o repositório está na conta pessoal do GitHub. Confirmar com o coordenador se isso é aceito ou se deve ir para uma conta da empresa.
10. **Colaborador em mais de um supervisor**: se dois supervisores cadastrarem a mesma pessoa, o gestor verá dois registros. Sugestão: o gestor identifica pelo CPF ou pela chave Pix e pode juntar os dois.
11. **Pasta compartilhada:** se a empresa tiver uma pasta de rede ou do OneDrive em comum, ela pode substituir o envio de arquivos por e-mail. O supervisor salva o arquivo lá e o gestor importa de lá. Avaliar depois da v1.
12. **Política de TI:** confirmar que é permitido executar um `.exe` portátil nos PCs da empresa (alguns antivírus bloqueiam programas novos sem assinatura digital).

## 11. Próximos passos

1. Responder às decisões da seção 10.
2. Fazer os wireframes das telas principais.
3. Criar o projeto Python (estrutura, tema, banco SQLite e tela de login).
4. Implementar na ordem: colaboradores → recibos → exportar/importar → pagamentos → retorno.
