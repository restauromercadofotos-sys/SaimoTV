# Sintonia TV

Aplicativo Android TV independente para assistir a canais ao vivo na Mi Box e em outros aparelhos Android TV. O identificador de instalação é `br.com.sintonia.tv`. A Sintonia TV usa seus próprios dados de configuração e favoritos.

## Assistir

Em **Ajustes → Minhas listas**, adicione um link ou arquivo M3U, ou informe servidor, usuário e senha da sua lista. Também é possível configurar um catálogo de canais por endereço em **Ajustes → Listas**. A Sintonia TV abre o ao vivo usando o catálogo padrão do APK TV Ao vivo. Deixe o endereço em branco e salve para desativá-lo.

- **OK** sobre o vídeo abre o guia. Use as setas para navegar por categorias, canais, dias e programas. O guia fecha após oito segundos sem atividade.
- **Cima/baixo** sobre o vídeo troca de canal. A faixa inferior mostra o que está no ar e o que vem a seguir quando a grade está disponível.
- **Segure OK** para abrir as opções do canal. A reprodução recupera falhas e testa fontes alternativas disponíveis.
- O player é a tela de abertura. Favoritos ficam no guia. Ajustes, eventos e rádios ficam nas opções do player.

## Desenvolvimento

Use JDK 21, Android SDK 37.2 e o wrapper do Gradle.

```sh
./gradlew testDebugUnitTest assembleDebug
```

O APK de depuração sai em `app/build/outputs/apk/debug/app-debug.apk`. Alguns testes de programação consultam feeds externos.

Para regenerar ícones e banners do launcher, execute `python3 scripts/gen_icons.py` com Pillow e DejaVu Sans instalados.
