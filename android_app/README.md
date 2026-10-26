# Recipay Android — área do Supervisor

Aplicativo Android nativo destinado somente ao Supervisor. O Gestor continuará usando o aplicativo Windows. A troca entre os dois lados continuará sendo feita por arquivos XLSX, sem exigir servidor pago.

## O que funciona nesta etapa

- Cadastro local de uma despesa com colaborador, valor, descrição e data.
- Comprovante em imagem obrigatório para salvar uma despesa.
- Cópia da imagem para o armazenamento privado do aplicativo.
- Persistência dos registros em SQLite no próprio celular.
- Lista das despesas cadastradas, carregada novamente do banco ao abrir a tela.

## Limites desta etapa

- Ainda não há cadastro completo de colaboradores/Pix.
- Ainda não há exportação XLSX no Android compatível com o contrato atual do Windows.
- Ainda não há importação do retorno do Gestor, atualização de pagamentos parciais ou sincronização automática.
- A imagem do comprovante é guardada localmente; backup/recuperação e criptografia dos anexos no Android ainda precisam ser implementados.
- O aplicativo não realiza transferências Pix.
- O build do CI comprova compilação, não testes em aparelho real. Validar com dados fictícios antes de usar.

## Próximas etapas

1. Completar cadastro de colaboradores e dados Pix necessários para o arquivo de troca.
2. Implementar exportação XLSX no formato exato do ReciPay Windows e testar a importação no Windows.
3. Implementar importação do retorno do Gestor, com prevenção de duplicidade e pagamentos parciais.
4. Adicionar testes de integração e validar o fluxo em aparelho Android.

## Compilar

O workflow GitHub Actions Android - build APK compila o APK de depuração e guarda o artefato por 14 dias quando a compilação passa.

Localmente, com JDK 17, Android SDK e Gradle 8.9 instalados:

    gradle -p android_app assembleDebug

O APK é gerado em android_app/app/build/outputs/apk/debug/app-debug.apk.
