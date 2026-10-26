# Recipay Android — protótipo de prévia

Este é um aplicativo Android nativo inicial, separado do aplicativo Windows. Ele não altera o banco SQLite nem o fluxo atual do Windows.

## O que funciona nesta etapa

- Selecionar um arquivo XLSX pelo seletor de documentos do Android.
- Abrir o pacote XLSX e localizar as abas `Recibos` e `_recipay`.
- Validar a marca e versão do formato, hash do JSON, referências e valores visíveis, IDs de colaboradores, total do lote e integridade dos comprovantes embutidos.
- Mostrar supervisor, identificador do lote, quantidade, total e lista de recibos validados.
- Rejeitar arquivos inválidos sem gravar dados.

## Limites importantes

- A tela é uma **prévia somente de leitura**. Não existe banco local Android, fila de pagamentos, registro de Pix, importação persistente nem exportação de retorno nesta etapa.
- O app não realiza transferências bancárias.
- A compatibilidade precisa ser comprovada em dispositivos/emuladores e com arquivos de teste gerados pelo exportador Windows. A compilação do APK, sozinha, não comprova todo o fluxo de negócio.
- Não usar dados financeiros reais em testes de desenvolvimento.

## Compilar

O workflow GitHub Actions `Android - build APK` compila o APK de depuração e guarda o artefato por 14 dias quando a compilação passa.

Localmente, com JDK 17, Android SDK e Gradle 8.9 instalados:

```bash
gradle -p android_app assembleDebug
```

O APK, quando a compilação termina com sucesso, fica em:

```
android_app/app/build/outputs/apk/debug/app-debug.apk
```
