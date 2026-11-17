# EditorClipesDSS — versão Android 3.0 (16/11/2026)

Código-fonte sincronizado do projeto atual no Termux em `~/editorclipes-maquina`.

## Organização
- `app/src`: código Android Kotlin, Java, recursos XML e código JNI C++
- `bridge`: serviços Python de edição, captura, fila, retomada, integração com PC e testes
- `.devcontainer`: preparação do ambiente Linux para Codespaces (FFmpeg, Python, Git)

## Importante
Este envio contém **arquivos de texto/código**, não um APK compilado. Por segurança e tamanho, não inclui vídeos, caches, logs, chaves de assinatura, `local.properties`, modelos de IA, bibliotecas `.so`, fontes `.ttf`, imagens binárias, nem arquivos de configuração com segredos. Alguns binários são necessários para recompilar o APK; providencie-os por fonte confiável/licenças e gere bibliotecas nativas no build apropriado. A configuração de Codespaces prepara ferramentas, mas ainda **não conecta automaticamente** o app Android ao terminal remoto.

Este conteúdo foi colocado em subpasta para preservar o LiveClip original. Execute testes antes de usar como versão de produção.
