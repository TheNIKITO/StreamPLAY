# StreamPLAY para Android (celular e TV)

O mesmo StreamPLAY do PC, em Kotlin + Jetpack Compose, com o motor do VLC (libVLC). Um APK só serve para celular,
tablet e TV Android (aparece na tela inicial da TV e funciona com o controle remoto).

## Gerar o APK

Dê dois cliques em `gerar-apk.bat`. O arquivo sai em `android\dist\StreamPLAY.apk`.

Precisa do JDK 17 e do Android SDK (nesta máquina ficam em `%LOCALAPPDATA%\AndroidBuild` e
`%LOCALAPPDATA%\Android\Sdk`; o .bat já encontra os dois).

## Chave de assinatura (importante)

O APK é assinado com a chave em `%USERPROFILE%\.streamplay` (`streamplay.jks` + `assinatura.properties`).
Ela **não** vai para o GitHub. **Guarde uma cópia dessa pasta**: sem ela, os aparelhos não aceitam instalar
uma versão nova por cima da antiga (o cliente teria que desinstalar e entrar de novo).

## Instalar

- **Celular**: mande o `StreamPLAY.apk` (WhatsApp, Drive, cabo) e abra. O Android pede para permitir
  "instalar apps desconhecidos" na primeira vez.
- **TV Android / TV Box**: copie para um pendrive e abra com um gerenciador de arquivos, ou use o app
  "Downloader" com um link do APK. Também dá com `adb install StreamPLAY.apk`.

## Atualização automática

Igual ao PC: o app procura um arquivo `StreamPLAY.apk` na última release do GitHub. Se a versão da release for
maior que a do app (`versao` em `app/build.gradle.kts`), ele baixa e abre o instalador do Android.
Para lançar: suba a versão em `app/build.gradle.kts`, gere o APK e anexe `dist\StreamPLAY.apk` na release.

## Testes

`gradlew.bat :app:testDebugUnitTest` roda no PC os testes da leitura da lista (inclusive uma lista de
316 mil itens) e da regra de bloqueio da conta.
