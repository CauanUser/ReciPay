# Plano de evolução multiplataforma do Recipay

## Estado da inspeção inicial

- Aplicação atual: desktop Windows em Python + PySide6.
- Persistência local: SQLite; os dados e comprovantes ficam no computador.
- Fluxo entre Supervisor e Gestor: arquivos Excel `.xlsx` com abas legíveis e uma aba técnica oculta `_recipay`, contendo IDs, dados estruturados e comprovantes.
- Recursos existentes identificados: cadastro de colaboradores e recibos, pagamentos parciais, QR Code Pix, importação/exportação, retorno, relatórios, backup criptografado e testes automatizados.
- Há testes em `tests/` para backup, fluxos básicos, cancelamentos, colaboradores, NFC-e, pagamentos, recibos, recuperação, relatórios e troca/retorno.
- A especificação atual assume Windows e ausência de servidor. Isso conflita com o novo objetivo de Windows + Android e precisa ser resolvido sem descartar o fluxo existente.

## Princípios obrigatórios

1. Não reescrever nem remover os módulos atuais sem necessidade comprovada.
2. Não alterar a branch `main` diretamente.
3. Não mesclar a branch de trabalho antes de executar os testes e revisar o diff.
4. Preservar os IDs estáveis dos recibos, pagamentos, lotes e comprovantes.
5. Importações repetidas precisam continuar idempotentes: reimportar o mesmo lote não pode duplicar recibos nem pagamentos.
6. Pagamentos parciais, saldos pendentes, estornos, histórico e backup devem continuar funcionando.
7. Não integrar transferências bancárias; o aplicativo apenas organiza os dados e registra Pix feitos no banco.
8. Evitar dependências pagas e serviços que exijam cobrança. Qualquer plano gratuito externo deve ser opcional e ter seus limites documentados.
9. Não sincronizar automaticamente bancos locais diferentes sem uma estratégia explícita de sincronização e resolução de conflitos.

## Caminho de implementação recomendado

### Etapa 1 — Baseline e contrato de dados

- Confirmar o estado atual dos módulos, esquema SQLite, migrações, formato dos arquivos de troca e testes.
- Executar a suíte completa em ambiente limpo e registrar o resultado real.
- Criar testes de regressão para importação repetida, pagamentos parciais, comprovantes, retorno e backup, caso haja lacunas.
- Documentar o formato de troca como contrato versionado, sem mudar o formato atual nesta etapa.

### Etapa 2 — Decisão técnica para Android

Avaliar, com uma prova de conceito pequena, a opção de menor risco entre:
- aplicativo Android complementar que importa/exporta o contrato atual;
- reaproveitamento de lógica Python compatível, isolando a interface desktop;
- migração gradual para uma interface multiplataforma.

A decisão deve considerar compatibilidade de arquivos, manutenção, segurança de dados pessoais, tamanho do aplicativo, capacidade de gerar APK e custo total. Não iniciar uma reescrita ampla antes dessa avaliação.

### Etapa 3 — Implementação incremental

- Implementar primeiro um fluxo vertical mínimo no Android: abrir/importar lote, visualizar prévia, validar IDs e hashes e bloquear duplicações.
- Em seguida, adicionar consulta de pendências, registro de pagamentos parciais e exportação de retorno compatível com o Windows.
- Só então evoluir para cadastro de recibos, fotos e demais telas.
- Manter o Windows funcionando durante todo o desenvolvimento.

### Etapa 4 — Validação e entrega

- Executar todos os testes Python existentes e os novos testes de contrato.
- Testar importação do Windows para Android e retorno do Android para Windows, inclusive o mesmo arquivo importado duas vezes.
- Validar dados com fotos/PDFs, acentos, valores monetários, saldos parciais, arquivo inválido e backup/restauração.
- Gerar APK de teste e documentar como instalar.
- Abrir Pull Request com resumo, riscos, evidências de teste e passos de validação. Não mesclar automaticamente.

## Limitação importante

O desenho atual é local e baseado em arquivos; portanto, Windows e Android não terão sincronização automática em tempo real por padrão. O fluxo de arquivos preserva a proposta sem servidor e reduz o risco de custo, mas exige exportar e importar lotes. Sincronização automática é uma decisão separada, com implicações de segurança, conflitos e possíveis limites de serviços gratuitos.
