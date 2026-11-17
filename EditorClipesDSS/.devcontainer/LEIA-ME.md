# EditorClipesDSS — terminal de desenvolvimento na nuvem

Esta pasta prepara FFmpeg, Git e Python no GitHub Codespaces. Ao criar um Codespace em um repositório que contenha estes arquivos, o ambiente executa `verificar.sh` e produz um vídeo de teste vertical de 2 segundos.

**Não publicar** `.env`, `local.properties`, `debug.keystore`, tokens, vídeos de clientes, caches, APKs ou backups. O projeto local ainda não está publicado/sincronizado. O arquivo `Dockerfile` usa uma imagem de desenvolvimento padrão sem GPU. Codespaces pode desligar por inatividade e tem cotas gratuitas; não é um renderizador permanente.

Para usar, é necessário vincular o código revisado a um repositório privado e criar o Codespace na conta GitHub. A conexão do app Android com o servidor remoto exige uma API autenticada, troca segura de arquivos, fila e retomada — não está habilitada por estes arquivos.
