# LiveClip gratuito: Windows, Zorin e Ubuntu no Termux

Sem servidor contratado, assinatura de IA ou chave de API. Processamento e gravações ficam no dispositivo escolhido. Downloads iniciais usam internet e vários GB de armazenamento. Energia e internet são as suas. Esta é uma versão em validação, sem garantia de ausência de erros.

## Windows 10/11 de 64 bits

1. Instale Docker Desktop: https://www.docker.com/products/docker-desktop/ . O uso pessoal é gratuito conforme a licença do Docker. Consulte os requisitos atuais: https://docs.docker.com/desktop/setup/install/windows-install/ .
2. Use o backend WSL 2 e contêineres Linux. Se o Windows solicitar WSL, abra PowerShell como administrador e execute `wsl --install`. Reinicie depois da instalação. Virtualização precisa estar habilitada na BIOS/UEFI.
3. Extraia o ZIP. Entre em `LiveClip` e dê dois cliques em `ABRIR-NO-WINDOWS.bat`.
4. O iniciador abre Docker Desktop quando instalado no caminho padrão e aguarda até dois minutos. Se o Docker não iniciar, siga a mensagem apresentada.
5. Aguarde baixar os modelos e construir o serviço. O navegador abre em http://localhost:8080. A senha é exibida no terminal e fica no arquivo `.env`.
6. Se aparecer IA aguardando, confira `docker compose logs -f model-setup studio` no PowerShell aberto nessa pasta.
7. Para usar no celular conectado ao mesmo Wi-Fi, abra um dos endereços IPv4 mostrados. Escolha o IP da interface Wi-Fi/Ethernet, não do adaptador virtual Docker/WSL. Permita acesso no firewall somente na rede privada.

Fechar a janela do iniciador não encerra os contêineres. Mantenha Docker Desktop ativo e o PC sem suspensão. Dados persistem nos volumes Docker. Não use `docker compose down -v`: apaga os dados.

## Zorin

1. Extraia o ZIP e abra um terminal dentro de `LiveClip`.
2. Execute `bash instalar-zorin.sh`.
3. Digite a senha do Linux quando o sudo solicitar. Aguarde os downloads.
4. Abra http://localhost:8080. A senha e possíveis IPs do celular aparecem no terminal.

## Android: processamento dentro do Ubuntu no Termux

Este modo é experimental. Requer Android com ambiente Linux de 64 bits, espaço para dependências/modelos/gravações e memória suficiente. Um celular com 8 GB de RAM é uma hipótese inicial de uso, não um benchmark validado. Aparelhos com pouca RAM podem encerrar processos. Ubuntu instalado não significa que Android permitirá monitoramento contínuo por dez horas.

O instalador usa uv para criar Python 3.12 em `.venv312`, separado do Python do Ubuntu. Isso também atende Ubuntu com Python 3.14, sem substituir o interpretador do sistema. Fonte: https://docs.astral.sh/uv/guides/install-python/ .

O modo usa Python + FFmpeg + Whisper tiny + Ollama Qwen2.5 1.5B, sem Docker ou systemd. Os modelos são menores que no PC; a qualidade e a velocidade precisam de teste no aparelho.

1. Instale Termux a partir de https://f-droid.org/packages/com.termux/ ou das versões oficiais em https://github.com/termux/termux-app/releases . Não misture Termux e complementos de fontes diferentes.
2. Baixe o ZIP deste projeto no celular. Deixe em **Download**, com o nome `LiveClip-v0.1.0.zip`.
3. Abra Termux, cole este primeiro bloco e permita o acesso aos arquivos quando o Android solicitar:

```bash
termux-setup-storage
pkg update -y
pkg install -y unzip
```

4. Depois da permissão, cole:

```bash
mkdir -p "$HOME/liveclip-pacote"
unzip -o "$HOME/storage/downloads/LiveClip-v0.1.0.zip" -d "$HOME/liveclip-pacote"
bash "$HOME/liveclip-pacote/LiveClip/preparar-termux.sh"
```

O preparador reutiliza um Ubuntu chamado `ubuntu`, se conseguir acessá-lo; caso contrário instala Ubuntu. Ele detecta a sintaxe antiga/nova do proot-distro. Copia o código para `/root/LiveClip` dentro do Ubuntu, instala dependências e inicia em uma sessão tmux chamada `liveclip`. Configuração e vídeos existentes nessa pasta são preservados durante a cópia do pacote.

5. Quando aparecer **Painel**, abra http://127.0.0.1:8080 no navegador do próprio celular. Use a senha mostrada.
6. Nas configurações Android, permita bateria **Sem restrições** para Termux. O preparador solicita wake lock. Isso reduz interrupções, mas não impede que Android encerre processos por falta de memória.
7. Para sair da visualização do terminal mantendo a sessão tmux: pressione **Ctrl+B**, solte, depois **D**. Não force a parada do Termux. Não encerre a sessão do Ubuntu.
8. Para retornar, no Termux execute:

```bash
tmux attach -t liveclip
```

Se reiniciou o celular ou a sessão deixou de existir, execute novamente o preparador do passo 4. Não reinstale/apague o Ubuntu para tentar corrigir um erro.

### Se você já está dentro do Ubuntu

Com a pasta do projeto copiada para `/root/LiveClip`, execute:

```bash
cd /root/LiveClip
bash instalar-ubuntu-termux.sh
bash iniciar-ubuntu-termux.sh
```

Para reiniciar depois, basta `bash iniciar-ubuntu-termux.sh`. Prefira abrir o Ubuntu dentro de tmux no Termux, para manter o terminal vivo ao trocar de tela. Na execução nativa, fechar a página do navegador não encerra o servidor; encerrar o terminal supervisor encerra seus serviços.

### Diagnóstico nativo

```bash
cd /root/LiveClip
tail -n 80 logs-native/studio.log
tail -n 80 logs-native/ollama.log
tail -n 80 logs-native/model-download.log
```

Configuração e senha: `native.json`. Vídeos/banco: `data-native`. Não publique esses arquivos. Instalações Docker e nativa têm dados separados; não sincronizam automaticamente entre aparelhos. A transcrição baixa o modelo Whisper no primeiro trabalho.

## Painel comum

Entre com a senha, cole uma live pública e clique **Monitorar live**. Confira IA e processamento no diagnóstico. Os estados mostram captura, transcrição, seleção e edição. Use **Atualizar** para consultar novamente, **Cancelar tarefa** para interromper captura, análise e edição, **Reanalisar gravação** depois de uma análise interrompida e **Baixar MP4** para salvar um corte pronto.

As métricas mostram vídeo recebido e analisado. Seleção editorial pode retornar nenhum corte; não há promessa de viralização. Falhas na plataforma, ausência de vídeo e erros de análise precisam aparecer no painel. A versão ainda requer testes reais completos nas três plataformas e validação editorial positiva.

## Fontes da instalação

- Ollama Linux/ARM64 e execução direta `ollama serve`: https://github.com/ollama/ollama/blob/main/docs/linux.mdx
- proot-distro, comandos e restrições de execução: https://github.com/termux/proot-distro
- Docker Desktop Windows/licença pessoal: https://docs.docker.com/desktop/setup/install/windows-install/

## Atualizar preservando dados no Termux

1. Baixe o ZIP atualizado em Download. Se o Android acrescentar `(1)` ao nome, o comando abaixo escolhe o download mais recente que começa por LiveClip-v0.1.0.
2. Na sessão antiga, pressione Ctrl+C para encerrar o supervisor de forma organizada. Aguarde voltar ao prompt. Depois Ctrl+B e D para sair da visualização do tmux.
3. Abra uma nova sessão do Termux (fora do Ubuntu) e execute:

```bash
task_zip=$(ls -t "$HOME"/storage/downloads/LiveClip-v0.1.0*.zip 2>/dev/null | head -n 1)
if [ -n "$task_zip" ]; then
  mkdir -p "$HOME/liveclip-atualizado"
  unzip -o "$task_zip" -d "$HOME/liveclip-atualizado"
  tmux new-session -s liveclip-atualizado proot-distro login --bind "$HOME/liveclip-atualizado/LiveClip:/mnt/atualizacao" ubuntu -- bash -lc 'cp -R /mnt/atualizacao/. /root/LiveClip/ && cd /root/LiveClip && bash iniciar-ubuntu-termux.sh; exec bash'
else
  echo 'ZIP não encontrado na pasta Download.'
fi
```

A cópia atualiza o código, mantendo `native.json`, `data-native` e modelos. Esta atualização de câmera/narrativa não exige reinstalar dependências. Se o iniciador informar LiveClip já aberto, retorne à sessão antiga e finalize com Ctrl+C antes de tentar novamente. MP4s já prontos não são alterados; a alteração vale para novas renderizações e seleções. Reanalisar uma gravação existente pode manter cortes deduplicados já gerados; teste primeiro com uma sessão nova.

## Painel de atividade — atualização 05/10/2026

A versão `0.1.0-atividade` aparece no diagnóstico do painel. Cada transmissão mostra a etapa, o trecho em análise, o avanço do áudio transcrito, tempo na etapa, último sinal do subprocesso e histórico dos últimos eventos. O sinal indica que o subprocesso ainda existe; não comprova avanço nem uso de CPU. Seleção/revisão/edição não exibem porcentagens inventadas. No primeiro uso a transcrição pode baixar o modelo.

“Vídeo recebido” significa duração do material gravado, não horas transcorridas. “Fila” é a duração recebida menos o ponto já analisado. Pode ficar grande em vídeos anteriores transmitidos rapidamente ou quando o processamento é lento. Não existe estimativa confiável de término no celular.

Uma análise sem candidatos aprovados informa isso explicitamente. Erros mostram a etapa e verificações sugeridas; o traceback técnico fica nos logs. Após três falhas consecutivas na análise final, o sistema pausa a sessão, mantém os arquivos e libera o painel para outra live. Use **Continuar análise** para retomar uma gravação incompleta a partir do ponto salvo, mantendo contexto anterior. Uma gravação inteiramente analisada oferece **Reanalisar gravação**, que começa novamente. Após reiniciar o supervisor, atualize a página para carregar o novo painel.

### Atualização curta no celular

Baixe o ZIP novo. No terminal onde o LiveClip está rodando, pressione **CTRL** e **C** e espere voltar ao prompt. Abra uma nova sessão do Termux, fora do Ubuntu, e execute:

```bash
termux-setup-storage
task_zip=$(ls -t "$HOME"/storage/downloads/LiveClip-v0.1.0*.zip 2>/dev/null | head -n 1)
if [ -n "$task_zip" ]; then
  mkdir -p "$HOME/liveclip-atualizado"
  unzip -o "$task_zip" -d "$HOME/liveclip-atualizado"
  tmux new-session -s "liveclip-$(date +%s)" proot-distro login --bind "$HOME/liveclip-atualizado/LiveClip:/mnt/atualizacao" ubuntu -- bash -lc 'cp -R /mnt/atualizacao/. /root/LiveClip/ && cd /root/LiveClip && bash iniciar-ubuntu-termux.sh; exec bash'
else
  echo 'Baixe o ZIP na pasta Download antes de continuar.'
fi
```

Autorize o acesso ao armazenamento se o Android solicitar. Esta cópia mantém a configuração, senha, modelos e gravações. Não reinstale nem apague o Ubuntu. Se aparecer **LiveClip já está aberto**, ainda há um supervisor antigo: retorne ao terminal dele e encerre com Ctrl+C.

## Atualização 0.1.1-revisao

**Cancelar tarefa** interrompe o trabalho; não continua analisando a gravação inteira. Espere **Cancelada** antes de iniciar outra live. Os cortes prontos e o vídeo recebido são preservados; **Continuar análise** permite retomar depois. Sessões antigas em finalização com pedido de parada são canceladas ao iniciar esta versão. **Baixar diagnóstico** fornece um JSON com estado, eventos e os últimos logs; a senha não é incluída.

Para instalar sem colar um bloco longo:

1. Baixe o ZIP atualizado em Download. No terminal do LiveClip, Ctrl+C e aguarde o prompt.
2. Execute `exit` para sair do Ubuntu. Confirme o prompt `~ $` do Termux.
3. Execute **um comando por vez**, apertando Enter após cada um:

```bash
unzip -o "$HOME/storage/downloads/LiveClip-v0.1.0.zip" -d "$HOME/liveclip-atualizado"
```

```bash
proot-distro login --bind "$HOME/liveclip-atualizado/LiveClip:/mnt/atualizacao" ubuntu
```

4. Agora no Ubuntu (`root@localhost`):

```bash
cp -R /mnt/atualizacao/. /root/LiveClip/
```

```bash
cd /root/LiveClip
```

```bash
bash iniciar-ubuntu-termux.sh
```

Atualize a página e confira **Painel: 0.1.1-revisao**. A cópia mantém senha, modelos e gravações. Esta atualização não exige reinstalação das dependências. Deixe o terminal Ubuntu aberto; para operação prolongada, use tmux no Termux conforme os passos anteriores.

## Comando único no Termux — 0.1.4-termux-facil

Baixe o ZIP atualizado na pasta Download substituindo o anterior. No Termux puro (prompt `~ $`, fora do Ubuntu), configure uma única vez:

```bash
unzip -p "$HOME/storage/downloads/LiveClip-v0.1.0.zip" LiveClip/liveclip-termux.sh > "$HOME/liveclip-termux.sh" && bash "$HOME/liveclip-termux.sh"
```

Depois, para iniciar ou atualizar, digite apenas:

```bash
liveclip
```

Para atualizar basta baixar um novo ZIP LiveClip*.zip no Download e executar `liveclip`. O arquivo mais recente é escolhido pela data de modificação. ZIP idêntico não reinstala nem reinicia a sessão gerenciada já aberta. Não procura atualizações pela internet: usa o ZIP que você baixou. É gratuito.

O comando fica no Termux, entra no Ubuntu automaticamente, mantém a execução em tmux e tenta abrir o painel. Pode ser necessário atualizar a página após a preparação dos modelos. Para sair do tmux mantendo o serviço: Ctrl+B, solte e pressione D. O Android pode encerrar processos em segundo plano; tmux não impede isso.

Vídeos, senha, modelos e ambiente Python são preservados. Backup dos arquivos substituídos em `/root/.liveclip-backups`. A cópia falhou? Os arquivos tocados são restaurados. Requisitos alterados ou instalação ausente acionam o instalador; na primeira vez são necessários internet e espaço. ZIP inválido/incompleto é recusado antes da troca.

O iniciador envia SIGTERM somente a supervisores `python -m liveclip.native` que usam a pasta instalada; espera até 90 segundos pela liberação da trava. Não força o encerramento se não conseguir confirmar a parada. Nesse caso mantém os arquivos antigos e orienta Ctrl+C na sessão anterior. Pode ser necessário conceder acesso ao armazenamento no Android e repetir o comando.
