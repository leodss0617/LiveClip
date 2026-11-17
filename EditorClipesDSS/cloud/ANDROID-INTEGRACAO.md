# Integração Android — GitHub Cloud (em desenvolvimento)

A branch `feature/editorclipes-cloud-android` adiciona um cliente REST em `app/src/main/java/com/example/cloud/CloudVideoClient.kt` e uma seção **GitHub Cloud (experimental)** em **Configurações**.

## Funções preparadas
- Verificar `GET /health`
- Selecionar MP4 pelo seletor Android e enviar `POST /jobs` em streaming (até 100 MB)
- Consultar `GET /jobs/{id}` e acompanhar status
- Baixar `GET /jobs/{id}/output` e registrar na galeria
- Recuperar o último identificador de trabalho após reiniciar o aplicativo
- Exigir HTTPS, sem salvar o token do serviço na preferência local

## Bloqueio intencional: autenticação GitHub
Portas marcadas como **Private** no Codespaces exigem autorização adicional **do GitHub** para acessar o URL `*.app.github.dev` fora da própria máquina. A documentação oficial usa o cabeçalho `X-Github-Token`. Esse token do GitHub tem permissões sobre recursos da conta/repositório e **não deve ser embutido no APK, salvo em texto simples ou compartilhado em chats**.

O token Bearer do serviço, gerado em `~/.editorclipes-cloud/token`, **não substitui** a autenticação do GitHub. O cliente Android ainda **não** implementa a autorização GitHub para porta privada. Até existir um fluxo aprovado de autenticação, a conexão externa com o Codespaces não estará pronta; não altere a visibilidade para Public para tentar fazê-la funcionar.

## Limitações atuais
- O backend executa conversão de vídeo para tela 9:16; ainda não inclui detecção inteligente de streamer nem todas as etapas de edição do app
- Aplicativo Android ainda não compilado/testado nessa branch
- Os trabalhos e vídeos não são armazenados em nuvem permanente e podem se perder se o Codespaces for excluído
- Não usar em produção nem substituir o APK instalado até concluir testes de compilação, fluxo de autenticação e envio/download fim a fim
