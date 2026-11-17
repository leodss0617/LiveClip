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

## Painel no navegador do celular (novo, experimental)

A pasta `cloud/mobile.html` acrescenta uma interface Web adaptada ao celular, servida pela rota `/` do servidor Python. Os endpoints permanecem autenticados com Bearer.

1. No Codespaces, execute a versao atualizada do servidor da branch de desenvolvimento.
2. Em **Ports**, confirme a porta **18755** e **Private**.
3. Use a opcao do VS Code **Open in Browser** para verificar a URL `https://NOME-18755.app.github.dev`.
4. No navegador do celular, entre na **mesma conta GitHub** e abra a URL privada. O GitHub pode solicitar login.
5. Na pagina do EditorClipesDSS, insira **apenas no proprio navegador** o conteudo de `~/.editorclipes-cloud/token`, gerado pelo servidor. Nao copie esse codigo para chats e nao salve em repositorios.
6. Selecione um MP4 de ate 100 MB, envie e aguarde a renderizacao.
7. Baixe o arquivo final. O ID do trabalho permite consulta posterior enquanto o Codespaces existir.

O painel guarda o codigo somente em memoria durante a vida da pagina, nao em localStorage. A interface do navegador depende da autenticação privada do GitHub e **nao exige token GitHub manual no JavaScript**. Nao mude a porta para Public.

O cliente Kotlin e esse painel do navegador sao alternativas experimentais distintas. O painel web pode funcionar no celular antes de um APK novo; isso **nao comprova** que o cliente Kotlin esteja operacional.
