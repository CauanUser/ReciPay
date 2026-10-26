# Spike Android

## Estado
A aplicação atual é Windows em Python/PySide6, com SQLite e troca de arquivos XLSX. A interface PySide6 não pode ser simplesmente executada no Android.

## Direção proposta
Criar um aplicativo Android complementar, sem alterar o Windows. Começar pelo fluxo do Gestor: importar XLSX, mostrar prévia, detectar IDs já importados, consultar pendências e exportar retorno compatível.

O arquivo de troca atual contém abas legíveis e uma aba técnica oculta `_recipay` com dados estruturados e comprovantes codificados. O protótipo deve preservar esse contrato; abrir XLSX não basta para provar compatibilidade.

## Opções
- Kivy/Python: pode reutilizar parte da lógica, mas exige substituir PySide6 e validar dependências nativas.
- Kotlin nativo: interface Android própria e isolamento melhor, mas exige reproduzir e testar regras de negócio e formato XLSX.
- Reescrita multiplataforma completa: risco e escopo altos para esta etapa.

Recomendação provisória: protótipo Android complementar e incremental. Kotlin é candidata, mas a escolha final depende de abrir, validar e exportar um arquivo real compatível.

## Critérios de aceitação
- [ ] Importar arquivo XLSX exportado pelo Windows e mostrar prévia antes de gravar.
- [ ] Reimportar o mesmo lote sem duplicar recibos ou pagamentos.
- [ ] Preservar IDs, comprovantes, valores em centavos, pagamentos parciais, saldo e histórico.
- [ ] Registrar pagamento somente com confirmação explícita e comprovante bancário.
- [ ] Exportar retorno que o Windows importe sem duplicidade.
- [ ] Rejeitar arquivo inválido sem deixar o banco parcialmente atualizado.
- [ ] Compilar APK de depuração em CI.
- [ ] Executar a suíte Windows existente sem regressões.

## Próxima execução
1. Documentar serialização e validação exatas em `recipay/troca.py`.
2. Preparar arquivos de teste sem dados pessoais reais.
3. Implementar leitor Android mínimo com prévia, sem registrar pagamentos ainda.
4. Validar ida e volta Windows → Android → Windows.

Não fazer nesta etapa: reescrita completa, migração do banco, nuvem, sincronização automática ou integração bancária.
