# Chat da Kick integrado (versão de desenvolvimento)

## O que mudou
- Em **Lives > expandir** no modo paisagem, o vídeo fica maior à esquerda e o chat oficial da Kick ocupa a coluna direita.
- Botões anteriores (Voltar, Monitorar, Mudo, Marcar) continuam disponíveis no ícone de menu.
- O ícone de chat recolhe/expande a coluna direita.
- O botão **Chat** mostra `https://kick.com/popout/CANAL/chat`.
- O botão **Entrar** abre a página oficial `kick.com` num WebView dedicado; a própria Kick cuida do formulário de login. Se o login não funcionar no WebView, use **Abrir canal e conversar na Kick**, que abre o canal no navegador do dispositivo.

## Limitações importantes
- Segundo [a central de ajuda oficial da Kick](https://help.kick.com/pt-BR/articles/7978574-como-criar-um-chat-dock-da-kick-no-obs), o chat popout pode ser **somente leitura**: não prometer envio de mensagens por ele.
- A página da Kick pode recusar login dentro do WebView ou exigir verificações adicionais. Isso não é garantia de login integrado.
- Para publicar mensagens via um sistema nativo dentro do EditorClipesDSS, integrar OAuth/PKCE e permissões oficiais da Kick e testar os fluxos autorizados.
- O código **não** implementa OAuth nem captura senhas.
- Não substituir APK instalado até compilar e validar em dispositivo real.

## Build e verificação
O workflow `.github/workflows/editorclipes-android-build.yml` tenta compilar o APK de depuração da branch e armazena o resultado como artefato apenas se passar. O projeto exportado para este GitHub pode estar incompleto (wrapper JAR, chaves, bibliotecas nativas e recursos ausentes). Mesmo se compilar, precisa testar reprodução das lives e chat no celular antes de instalar.
