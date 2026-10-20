# Recipay

Programa para PC (Windows) para organizar recibos de despesas e controlar pagamentos via Pix dentro de uma empresa. Ele substitui planilhas dispersas e facilita o controle de valores pendentes, pagamentos realizados e comprovantes.

> O Recipay **não faz transferências bancárias**. O gestor faz o Pix no aplicativo do banco e registra o pagamento no Recipay.

## Perfis

- **Supervisor**: cadastra recibos e colaboradores, exporta recibos e importa o retorno dos pagamentos.
- **Gestor**: importa recibos, registra pagamentos Pix com comprovante e exporta o retorno.

## Usuários

Na tela de entrada, cada pessoa escolhe o próprio nome e digita a senha. O botão **Cadastrar novo usuário**
cria uma conta de Supervisor ou de Gestor. Cada conta tem os próprios dados, mesmo no mesmo computador.

## Recursos

- Categorias de despesa (alimentação, combustível, hospedagem, transporte…) em cada recibo.
- Leitura do QR Code do cupom fiscal (NFC-e/SAT) na foto: identifica o cupom, avisa se for lançado duas vezes e preenche data/valor quando o QR traz.
- Relatório mensal em Excel (por categoria, equipe e colaborador; lançado, pago e pendente) e gráficos no Início.
- Modo escuro e tamanho de letra ajustável (botão **Aparência**, no topo).

## Fluxo resumido

1. O supervisor cadastra os recibos (com comprovante obrigatório).
2. O supervisor exporta os recibos numa planilha Excel e envia ao gestor.
3. O gestor importa o arquivo. Os recibos entram na fila de pagamentos.
4. O gestor faz o Pix no banco e registra o pagamento no Recipay (aceita pagamento parcial).
5. O gestor exporta a planilha de retorno. O supervisor importa, confere a prévia e confirma.

## Princípios

- Simples, confiável e **custo R$ 0** (ferramentas e serviços gratuitos).
- Visual em branco e azul, com verde para sucesso.
- Roda como `.exe` portátil, sem instalação (Python + PySide6).
- Histórico preservado e nenhum pagamento contado duas vezes.

## Como rodar (desenvolvimento)

No PowerShell, dentro da pasta do projeto:

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -m recipay
```

Testes: `.\.venv\Scripts\python.exe -m unittest -v`

Os dados ficam em `%LOCALAPPDATA%\Recipay`. Para testar sem mexer nos dados reais, defina `$env:RECIPAY_DADOS = "C:\caminho\de\teste"` antes de rodar.

## Gerar o programa para os usuários (Recipay.exe)

No Windows, dentro da pasta do projeto, dê dois cliques em `gerar_exe.bat` (ou rode `.\gerar_exe.bat` no PowerShell).
O programa fica em `dist\Recipay\Recipay.exe`. Para instalar em outro PC, copie a pasta `dist\Recipay` inteira
(por exemplo, compactada em .zip) e crie um atalho para o `Recipay.exe`. Não precisa instalar Python.

## Situação

Versão 0.9 (beta): ciclo completo pronto — colaboradores, recibos, troca de planilhas, pagamentos com QR Code Pix, retorno, início e backup. Especificação em [`docs/ESPECIFICACAO.md`](docs/ESPECIFICACAO.md).
