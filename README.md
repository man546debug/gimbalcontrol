# Smooth Q4 Remote

Aplicativo Android em Kotlin dedicado ao controle manual de pan/tilt do Zhiyun Smooth Q4 por Bluetooth LE.

## O que está incluído

- Busca e conexão BLE com dispositivos próximos.
- Joystick virtual com velocidade ajustável; ao soltar, envia comandos neutros.
- Tela dedicada ao joystick, com o controle de sensibilidade sempre visível.
- GitHub Actions para compilar e disponibilizar o APK de depuração como artefato.

## Compatibilidade do controle

Este é um protótipo. A análise estática do APK ZY Play 2.13.7 encontrou UUIDs BLE usados pelo ecossistema Zhiyun, mas não revelou o formato completo dos comandos. O codificador de movimento incluído usa um formato BTE publicado para outro modelo de gimbal Zhiyun como hipótese. Ainda não foi validado em um Smooth Q4 físico; portanto, a conexão BLE pode funcionar mesmo que o joystick não mova o gimbal ou os eixos/velocidade precisem de ajuste.

Teste com espaço livre ao redor do gimbal e com o telefone firmemente preso. Feche o ZY Play antes de conectar este aplicativo. Não use o movimento experimental perto de pessoas ou objetos frágeis. Para tornar o controle confiável, o próximo passo é observar/validar os pacotes enviados pelo ZY Play ao Smooth Q4 e ajustar `SmoothQ4Protocol.kt` com testes e uma prova no aparelho.

O app não implementa rastreamento automático de objetos nem controla a câmera do telefone nesta versão. Ele serve somente como controle Bluetooth do gimbal.

## Gerar o APK pelo GitHub

1. Envie o conteúdo desta pasta para um repositório GitHub.
2. Abra a aba **Actions** e habilite os workflows, se o GitHub solicitar.
3. Faça um `push`, abra a execução **Build Android APK** e aguarde o job ficar verde.
4. Baixe o artefato `smoothq4-remote-debug` no fim da página da execução. O APK dentro dele é `app-debug.apk`.
5. Instale no Android e conceda a permissão de Bluetooth solicitada.

A mesma automação roda em cada `push` e pull request. O workflow usa Gradle 8.9, JDK 17 e Android SDK; não é necessário enviar o APK original do ZY Play para compilar este projeto.

## Abrir no Android Studio

Abra a pasta do projeto no Android Studio com JDK 17. O Android Studio sincroniza os plugins e dependências definidos nos arquivos Gradle. Para compilar localmente, execute `gradle testDebugUnitTest assembleDebug` (ou configure o Gradle do Android Studio para importar o projeto). O APK de depuração ficará em `app/build/outputs/apk/debug/app-debug.apk`.

## Estrutura principal

- `app/src/main/java/br/com/manfredini/smoothq4remote/MainActivity.kt`: câmera, foto, zoom, interface e ciclo do joystick.
- `app/src/main/java/br/com/manfredini/smoothq4remote/SmoothQ4BleClient.kt`: busca, conexão GATT e notificações.
- `app/src/main/java/br/com/manfredini/smoothq4remote/SmoothQ4Protocol.kt`: UUIDs e codificador experimental dos comandos.
- `.github/workflows/build-apk.yml`: compilação e upload do artefato no GitHub Actions.
