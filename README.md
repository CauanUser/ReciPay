# Recipay

Programa para PC (Windows) para organizar recibos de despesas e controlar pagamentos via Pix dentro de uma empresa. Ele substitui planilhas dispersas e facilita o controle de valores pendentes, pagamentos realizados e comprovantes.

> O Recipay **não faz transferências bancárias**. O gestor faz o Pix no aplicativo do banco e registra o pagamento no Recipay.

## Perfis

- **Supervisor**: cadastra recibos e colaboradores, exporta recibos e importa o retorno dos pagamentos.
- **Gestor**: importa recibos, registra pagamentos Pix com comprovante e exporta o retorno.

## Fluxo resumido

1. O supervisor cadastra os recibos (com comprovante obrigatório).
2. O supervisor exporta os recibos (PDF/Excel) e envia ao gestor.
3. O gestor importa o arquivo. Os recibos entram na fila de pagamentos.
4. O gestor faz o Pix no banco e registra o pagamento no Recipay (aceita pagamento parcial).
5. O gestor exporta o PDF de retorno. O supervisor importa, confere a prévia e confirma.

## Princípios

- Simples, confiável e **custo R$ 0** (ferramentas e serviços gratuitos).
- Visual em branco e azul, com verde para sucesso.
- Roda como `.exe` portátil, sem instalação (Python + PySide6).
- Histórico preservado e nenhum pagamento contado duas vezes.

## Situação

Em planejamento. A especificação está em [`docs/ESPECIFICACAO.md`](docs/ESPECIFICACAO.md).
