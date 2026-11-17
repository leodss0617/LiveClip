# Serviço remoto do EditorClipesDSS — versão inicial

Este módulo executa **somente no Codespaces/Linux**, separado do aplicativo Android atual. É um serviço experimental para receber um MP4, enfileirar uma conversão vertical 720×1280 (com barras se necessário), acompanhar o estado e baixar o MP4. **Não faz ainda cortes inteligentes, IA, transcrição ou montagem da reação**, que são funções do editor principal.

## Executar no Codespaces
Depois de atualizar o repositório com `git pull --ff-only`, abra a pasta `EditorClipesDSS` e rode:

```bash
python3 cloud/service.py
```

O serviço escuta em **127.0.0.1:18755** (somente no Codespaces). Um token aleatório é gerado em `~/.editorclipes-cloud/token` e **não deve ser publicado, enviado no chat ou colocado no GitHub**. Todos os endpoints precisam de `Authorization: Bearer <TOKEN>`.

Rotas: `GET /health`, `POST /jobs` (corpo binário MP4, limite 100 MB), `GET /jobs`, `GET /jobs/<id>`, `GET /jobs/<id>/output`.

O VS Code pode oferecer encaminhar a porta 18755. **Mantenha a visibilidade Private.** Não use `Public` e não compartilhe URL/token. Mesmo com porta privada, é necessário configurar o aplicativo Android para autenticar, fazer upload e baixar o resultado. Isso ainda **não está implementado**.

```bash
python3 -m unittest discover -s cloud -p 'test_*.py' -v
```

Os trabalhos ficam em `~/.editorclipes-cloud`, local ao Codespaces. Depois de uma interrupção, itens com estado queued/running voltam à fila quando o servidor reinicia. Os arquivos podem se perder se o Codespaces for excluído; não é um backup persistente externo.

**Limites:** sem GPU; processamento CPU; máximo 30 minutos por vídeo; um vídeo por vez; uploads de até 100 MB por padrão; Codespaces tem cotas e suspensão automática. Não exponha o serviço à internet sem uma camada adicional de segurança.

## Painel para celular pelo navegador

A pagina `/` do servico (arquivo `cloud/mobile.html`) permite escolher MP4, enviar, acompanhar e baixar o resultado pelo navegador no celular. A pagina inicial e visivel a quem conseguir acessar a URL, mas **todas as APIs de videos continuam protegidas pelo codigo do servidor**. Mantenha a porta **18755 Private** no Codespaces.

Depois de atualizar e reiniciar o servidor, abra no celular a URL HTTPS da porta 18755, estando conectado a sua conta GitHub. No painel, informe **somente no dispositivo** o codigo privado guardado em `~/.editorclipes-cloud/token`. Nao publique nem envie esse codigo por chat. O painel nao salva o codigo no armazenamento do navegador e nao usa a credencial GitHub da conta no JavaScript. O GitHub controla o acesso à pagina pela autenticação da porta privada. Limite inicial: arquivos MP4 de ate 100 MB.
