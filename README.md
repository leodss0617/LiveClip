# LiveClip — monitor e editor de lives

Implementação inicial com transcrição local, seleção semântica via Ollama, fila persistente, renderização vertical, legendas e painel em português. **Leia docs/RELATORIO-VALIDACAO.md antes de considerar esta versão pronta para produção.**

## Instalação por dispositivo

Veja **docs/INSTALACAO-GRATUITA.md**: Windows por duplo clique, Zorin por instalador e Ubuntu dentro do Termux sem Docker (experimental). O painel inclui diagnóstico da IA/processamento e botão Atualizar.

## O que roda onde
Um PC ou servidor Linux executa os modelos e grava a live. Celular e PC acessam o mesmo painel. Fechar o painel não encerra a captura. Esta versão não é APK. Também há um modo nativo experimental para processar no Ubuntu dentro do Termux; requer validação no aparelho e pode ser interrompido pelo Android.

Recomendação inicial de hardware, ainda sem benchmark de carga: 4 núcleos, 8 GB de RAM e 40 GB de disco livre, internet estável. Vídeos longos e CPU fraca podem acumular fila. Uma sessão por vez, limite de dez horas. O modelo de linguagem e o Whisper são baixados no primeiro uso. Não depende de chave de API paga; hospedagem e internet podem ter custo.

## Começar no Linux/Zorin sem serviço pago
Este modo roda no seu notebook, sem mensalidade ou API paga. O notebook precisa ficar ligado e sem suspensão. Energia e internet são as suas.

Para instalar Docker e iniciar em uma única execução, abra o terminal na pasta extraída e rode:

```bash
bash instalar-zorin.sh
```

O instalador usa o repositório oficial do Docker e identifica a base Ubuntu do Zorin. Derivados do Ubuntu não têm suporte oficial do Docker; a instalação no seu notebook ainda precisa ser confirmada. Se detectar pacotes conflitantes, para sem removê-los. A senha do Linux é solicitada pelo sudo; não aparece enquanto você digita.

Se já tiver Docker, siga abaixo:
1. Confira o [Docker com Compose](https://docs.docker.com/engine/install/ubuntu/).
2. Extraia este pacote em uma pasta.
3. Abra o terminal nessa pasta e cole:

```bash
bash iniciar.sh
```

4. Acompanhe o primeiro carregamento:

```bash
sudo docker compose logs -f model-setup studio
```

5. Abra `http://localhost:8080` no PC. A senha foi gerada automaticamente e está em `.env`, depois de `LIVECLIP_PASSWORD=`.
6. Entre e cole o link de uma live pública do YouTube, Kick ou Twitch. Clique **Monitorar live**.
7. Confira o estado **Monitorando** e o tempo de vídeo recebido. A IA analisa em janelas; o primeiro corte leva vários minutos e depende de haver um momento completo adequado.
8. Use **Assistir** e **Baixar MP4** quando um corte estiver pronto.

Para descobrir o endereço do PC no Zorin:

```bash
hostname -I
```

No celular conectado ao mesmo Wi-Fi, abra `http://ENDERECO-DO-PC:8080`. Acesso pela rede local usa HTTP: não exponha a porta 8080 na internet. Para acesso remoto use HTTPS abaixo.

## Começar no Windows
1. Instale e abra o [Docker Desktop](https://docs.docker.com/get-started/get-docker/).
2. Extraia o pacote. Abra PowerShell nessa pasta.
3. Execute:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\iniciar.ps1
```

4. Abra `http://localhost:8080` e use a senha de `.env`. Para encontrar o IP local, execute `ipconfig` e veja **Endereço IPv4**. No celular, use esse IP seguido de `:8080`. O firewall precisa permitir a conexão na rede privada.

## Nuvem com HTTPS
Requer um servidor Linux com Docker, domínio apontado para seu IP e portas 80/443 acessíveis. Não há servidor contratado ou implantado automaticamente por este pacote.

Copie a pasta para esse servidor e execute:

```bash
bash iniciar.sh --nuvem seu-dominio.com
```

O Caddy configura HTTPS, o painel passa a usar cookies seguros e a porta 8080 fica limitada ao servidor. Acesse o mesmo endereço HTTPS no celular ou PC. Não publique `.env` ou volumes de dados.

## Uso e diagnóstico
- **Aguardando live/reconectando:** a plataforma não entregou vídeo ou a conexão caiu; o sistema tenta novamente, até o prazo da sessão.
- **Transcrevendo:** Whisper trabalhando; no primeiro uso ele também baixa o modelo.
- **Selecionando:** o modelo avalia contexto e completude. Não usa picos de volume como substituto de entendimento da fala.
- **Análise precisa de atenção:** verifique os modelos e `docker compose logs studio`; a captura pode continuar até o limite de disco. Depois de parar, **Reanalisar gravação** usa o vídeo já recebido.
- **Sem cortes:** pode não haver momento adequado, a janela pode ainda não ter terminado ou um serviço pode estar indisponível. Veja os estados reais no painel.
- **Liberar gravação:** exclui vídeo original de uma sessão finalizada e preserva os cortes prontos. Sem o original não é possível reanalisar essa sessão.
- **Sem espaço:** pare sessões, libere gravações ou exclua cortes. `MAX_DATA_GB` e `MIN_FREE_GB` em `.env` controlam os limites; aplique com `docker compose up -d`.

Layouts: detectando um rosto pequeno na lateral, o editor tenta separar rosto em cima e área restante embaixo. Sem evidência de reação, preserva a cena inteira com fundo desfocado. O detector Haar é limitado em perfil, rosto pequeno e cenas complexas; não equivale a compreensão visual completa.

## Parar e retomar o servidor
```bash
docker compose stop
docker compose start
```

Os dados ficam nos volumes persistentes. Não execute `docker compose down -v` se quiser preservá-los. Rodar mais de um processo da API com o mesmo volume não é suportado.

## Testes de desenvolvimento
```bash
python -m pip install -r requirements-dev.txt
python -m pytest -q
```

Os testes de FFmpeg requerem o FFmpeg instalado. Testes reais com plataformas dependem da live estar ativa e acessível a partir do IP do servidor. Integrações podem mudar e devem ser revalidadas. O score representa avaliação editorial; não é promessa de viralização.

## Revisão 0.1.1-revisao

O painel oferece **Cancelar tarefa** durante captura, análise, edição e finalização, preservando gravação e cortes já prontos. Interrupção e publicação são verificadas no servidor; trabalhos cancelados não devem gerar novos cortes. **Continuar análise** retoma explicitamente gravações incompletas. **Baixar diagnóstico** disponibiliza estado e logs da sessão após autenticação.

Confira docs/RELATORIO-VALIDACAO.md para falhas corrigidas, 54 testes e limitações. Este pacote continua precisando de validação editorial/operacional no hardware real; não há garantia de viralização ou de perfeição.

### Atualização 0.1.2-gravacoes
Aceita gravações públicas Kick no formato `/canal/videos/UUID` e Twitch `/videos/número`. O terminal e o painel mostram `0.1.2-gravacoes` após reiniciar. Se aparece outra versão, a instância antiga continua aberta. Encerre o iniciador anterior com Ctrl+C antes de iniciar novamente. Dados e senha são preservados ao copiar os arquivos sobre a instalação. O ZIP mantém o nome LiveClip-v0.1.0.zip por compatibilidade com os comandos de atualização.

### 0.1.3-15horas
Lives e gravações: até 15 horas por novo link no YouTube, Kick e Twitch. Atualize os arquivos e reinicie o serviço. Sessões antigas mantêm a expiração definida na criação.

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

### 0.1.5-exclusao-ia
Botão Excluir tarefa para tarefas encerradas, preservando os cortes prontos. A seleção mostra partes recebidas do modelo e erros específicos, reaproveita a transcrição em novas tentativas e pausa após três falhas consecutivas. Baixe o ZIP e execute liveclip no Termux para atualizar. Confira a versão no painel. Se a seleção ainda falhar, use Baixar diagnóstico e envie o arquivo JSON antes de excluir a tarefa.

### 0.1.6-limite-ia
Seleção/revisão têm prazo externo de cinco minutos sem novas partes e quinze minutos por etapa. Diagnóstico inclui estado do Ollama, memória, log do modelo e falas transcritas. Atualize com liveclip. Depois de interromper uma tentativa, use Continuar análise após verificar o motivo; a gravação e o cache de transcrição são preservados.


## 0.1.11: comando no computador e música completa

Na pasta extraída, instale o comando uma vez:

Linux/Zorin: `bash instalar-comando-linux.sh`

Windows PowerShell: `powershell -NoProfile -ExecutionPolicy Bypass -File .\instalar-comando-windows.ps1`

Abra outro terminal e digite `liveclip`. Mantenha a pasta instalada no mesmo lugar.
O iniciador aplica o ZIP LiveClip mais recente da pasta Downloads e inicia o sistema,
preservando dados, configurações e modelos. Windows pode exigir WSL 2 e reinício
na primeira instalação do Docker; execução Windows não foi verificada neste ambiente Linux.

YAMNet incluído detecta música/canto no áudio separado da IA textual. Uma apresentação
só vira corte automático quando há evidência de começo e fim dentro do trecho.
Cortes que atravessam música são ampliados até o evento completo, com margem de três
segundos. O histórico aumenta enquanto a música continua. Eventos musicais podem
chegar a dez minutos; entradas mantêm limite de quinze horas. Eventos maiores ou sem
limites conhecidos são descartados, não truncados. Pausas longas, fundo musical e
músicas em sequência podem confundir o classificador; não há garantia de identificar
cada música inteira. “Momento musical” é uma seleção acústica, não promessa de viralização.
A IA também admite reações/piadas com preparação e desfecho em duas falas, mantendo
revisão independente do contexto inicial e final. Falhas da IA pausam análise para
repetição, sem marcar trecho incompleto como analisado.


## 0.1.12 — avaliação a cada 20 minutos e memória local

Leia **LEIA-PRIMEIRO.txt** dentro deste ZIP para os passos completos de instalação,
início e atualização em Windows, Linux/Zorin e Termux/Ubuntu.

Blocos de 1200 segundos recebidos são liberados para análise em jobs menores com
contexto; a finalização libera a sobra. Nenhum corte é obrigatório. Interesse,
contexto e desfecho precisam de nota pelo menos 7/10. Música acústica precisa passar
na avaliação editorial. Nota técnica separada verifica aspectos da edição; é uma
estimativa, não garantia visual ou de viralização.

A base SQLite local guarda até 1000 experiências. As seleções recebem estatísticas
e até 8 exemplos avaliados por você, com nota e observação; não reutilizam notas
próprias como confirmação de qualidade. O painel permite avaliar cortes prontos e
apagar memória. Isso é recuperação de experiências e preferências, não treinamento
de pesos nem melhora contínua garantida. Atualizações preservam essa base.
# GitHub Codespaces

Veja [USAR-NO-GITHUB.md](USAR-NO-GITHUB.md) para abrir uma máquina de 2 núcleos e executar `liveclip`. A preparação baixa modelos; o painel é privado. A execução remota ainda não foi validada.

