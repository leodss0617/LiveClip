# LiveClip 0.1.10 — inicialização e sessão do Termux

Data: 07/10/2026.

## Problema observado
O print mostra o serviço aguardando o teste da IA antes de iniciar o painel. O teste curto usava contexto de 8192 tokens e limite de resposta de 768 tokens, iguais aos da análise de vídeos. A inicialização não exibia o tempo de espera. Quando o serviço encerrava, o iniciador substituía o shell, permitindo que a janela do tmux desaparecesse.

## Correções e testes desta atualização
- Teste JSON inicial: contexto de 1024 e resposta de até 32 tokens. O modelo Qwen3 4B e os limites de contexto/resposta da análise dos vídeos permanecem iguais.
- Aviso no terminal a cada 15 segundos durante o teste, com tempo decorrido e limite de 300 segundos. Este aviso não representa avanço da IA.
- Se o iniciador sair, a sessão permanece em um shell com código de saída, localização dos logs e comando para nova tentativa. A próxima execução de liveclip detecta essa sessão de falha e recria o serviço.
- Testes verificam o corpo real da requisição HTTP, execução do shell com falha de código 7, permanência da sessão, nova tentativa e preservação do instalador automático. Foram vistos os testes falharem antes das correções.
- Suíte completa: 105 testes passaram, com aviso de descontinuação no adaptador Starlette/httpx. Sintaxe Bash e compilação Python verificadas.

## Limites desta correção
Não foi medido o tempo de inicialização nem o uso de memória no celular do usuário. Não houve novo teste de inferência com o modelo Qwen3 4B nesta revisão. O teste inicial ainda precede o painel e pode levar até 5 minutos; a redução de contexto não garante rapidez em todo aparelho.
Não há evidência suficiente no print para atribuir o encerramento a falta de memória ou ao Android. Esta correção mantém a sessão quando o serviço sai, mas não impede que o Android encerre o Termux inteiro. O modelo maior ainda exige recursos para analisar os vídeos.

Para diagnóstico, em uma nova sessão do Termux: proot-distro login ubuntu -- bash -lc 'cd /root/LiveClip && free -h && tail -n 40 logs-native/model-test.log logs-native/ollama.log'

---

# LiveClip 0.1.9 — revisão de finalização e cancelamento

Data: 07/10/2026. Software gratuito, execução local. Nenhuma máquina em nuvem contratada ou implantada.

## Correções desta revisão

- Captura terminada sem vídeo informa erro, em vez de entrar em finalização e aparentar sucesso.
- Tarefas antigas em finalização com duração zero também passam para erro e liberam a fila.
- Análise sem histórias aprovadas explica o resultado; não promete cortes inexistentes.
- Falhas de edição aparecem no estado final e no resumo, com quantidade de cortes prontos e com erro.
- Recuperação, edição e cancelamento percorrem todos os cortes no banco, independentemente do limite de 500 itens exibidos no painel. Reproduzido com 501 cortes: um pendente antigo era ignorado pelo cancelamento. Os 500 cortes prontos são preservados.
- Encerramento do serviço durante edição preserva a tarefa em finalização e o corte na fila, para retomar ao reiniciar. Antes podia marcar a tarefa como concluída.

Arquivos alterados: liveclip/capture.py, worker.py, store.py, static/app.js, version.py; tests/test_completion.py, test_cancellation.py, test_vod_urls.py; este relatório.

## Evidências

Foram escritos testes de regressão e verificados os resultados de falha antes das correções. A suíte completa e os controles do navegador foram executados novamente depois das mudanças.

- Suíte final: 105 testes, resultado registrado ao empacotar. Há um aviso de descontinuação Starlette/httpx no adaptador de testes.
- MP4 real com FFmpeg: vertical 1080×1920, com áudio e sem áudio. Não equivale a validar detecção de câmera em toda live.
- Testes de interrupção incluem subprocessos reais, preservação de cortes prontos, exclusão de tarefas paradas, limites de 15 horas, validação de URLs e fronteiras editoriais.
- Chromium: login, progresso, cancelamento, exclusão, diagnóstico, download e telas 1280×900 e 390×844 passaram, sem erros JavaScript ou rolagem horizontal indevida.
- A prévia H.264 no Chromium de teste retornou DEMUXER_ERROR_NO_SUPPORTED_STREAMS; o tratamento de erro e o download passaram. Reprodução em celular físico não foi comprovada.
- Python compilado, sintaxe JavaScript/Bash e análise Ruff de erros críticos passaram. A configuração abrangente de estilo do ambiente encontrou 58 apontamentos, principalmente preexistentes; não foi executada uma reformatação geral do projeto.
- Revisão independente identificou a interrupção durante edição; a correção recebeu teste de regressão.
- Nova tentativa do link https://youtu.be/ZPdX3n4-ixc: yt-dlp encerrou com código 1, sem arquivo de vídeo, por CERTIFICATE_VERIFY_FAILED neste ambiente. Nenhuma geração de clipes desse vídeo foi comprovada nesta revisão. Certificados não foram desativados.

## Limitações que permanecem

- Não houve execução em Windows físico ou Android/Termux físico, nem sessão contínua de 15 horas. O limite foi testado em cenários controlados.
- O modelo Qwen3 4B da versão 0.1.8 foi preservado. O teste anterior da IA real continua documentado em TESTE-IA-REAL.json; não foi reexecutado nesta revisão. Modelos maiores podem consumir muita memória e demorar no celular.
- Detector de rosto, enquadramento automático, múltiplas pessoas, legendas e seleção de histórias dependem do vídeo. A revisão não comprova que a câmera do vídeo enviado será detectada corretamente.
- Bloqueios, autenticação, restrições regionais e mudanças em YouTube/Kick/Twitch podem impedir captura. Não há garantia de aceitar qualquer link.
- O primeiro download de dependências/modelos ainda pode falhar e requer acesso à internet. O pacote não contém os modelos.
- Nenhum teste oferece garantia de zero erros ou viralização. Esta é uma atualização com correções específicas verificadas, não uma declaração de conclusão de todos os requisitos.

## Atualização no Termux

Baixe o ZIP novo na pasta Download. Se aparecer root@localhost, execute exit para voltar ao Termux. Execute liveclip. O iniciador procura o pacote, atualiza o código e preserva configurações e dados. Confira a versão 0.1.9-finalizacao-cancelamento no painel.

Caso o comando liveclip ainda não esteja instalado, use o procedimento de bootstrap no README, fora do Ubuntu. Para Windows, extraia o pacote e use ABRIR-NO-WINDOWS.bat conforme o README.


## Validação 0.1.11 — 7 de outubro de 2026

129 testes passaram em Linux; um aviso de depreciação Starlette/httpx.
Verificados classificador YAMNet real em silêncio e melodia sintética, evento musical
completo, música em andamento sem exportação parcial, fala sobre música, reação em
duas falas, histórico adaptativo e saída do job com música sem história falada.
Comando Linux e atualização local têm testes de instalação, caminhos com espaços,
preservação de dados e rejeição de ZIP malicioso. Python compilado, sintaxe JS e Bash
verificadas. Revisão independente encontrou três problemas corrigidos: encerramento
por fala sobre música, checkpoint após falha de seleção e Python antigo no Windows.

Não executados: Windows real, Android/Termux real e o fluxo completo de corte do link
YouTube solicitado (acesso bloqueado neste ambiente nos testes anteriores). A melodia
sintética prova funcionamento da classificação, não qualidade editorial em toda live.
Os resultados e limites não representam garantia de 100% ou de viralização.


## Validação 0.1.12 — avaliações periódicas e memória local

147 testes passaram em Linux; um aviso de depreciação Starlette/httpx.
Verificados blocos de 1200 segundos, retomada de bloco parcial, sobra final,
revisão final mesmo no limite exato, estado preservado durante backoff de falha,
notas editoriais 0–10, rejeição abaixo de 7 e sem desfecho, memória limitada e
persistente, exemplos humanos no prompt, feedback autenticado e limpeza da base.
Avaliação técnica exige legendas que cruzem o corte; formato/duração/áudio inválidos
reprovam a edição. Experiência técnica também é registrada na base local.

Playwright em Chromium: controles de notas, feedback, limpeza de memória, cancelamento,
exclusão, diagnóstico e download passaram. Layout desktop 1280x900 e celular emulado
390x844 sem rolagem horizontal; nenhum erro JavaScript. A prévia H264 não reproduziu no
Chromium headless deste ambiente (codec indisponível); aviso alternativo e download
foram verificados. Resultado bruto: TESTE-PAINEL-0.1.12.json.

Compilação Python, sintaxe JavaScript/Bash e verificação Ruff passaram. Revisão
independente encontrou a borda final não reavaliada e legenda fora do corte contada
como presente; testes demonstraram as falhas antes das correções e passaram depois.

Limites: não executado em Windows/Android físicos. Avaliações semânticas foram
validadas com respostas controladas, não com uma nova sessão real do modelo 4B nem
uma live completa acessível. Classificador de áudio foi executado em fixtures reais;
isso não demonstra interesse editorial. A memória recupera experiências e feedback;
não treina pesos nem garante que cada nova live melhore o resultado.

Guia de instalação, ativação e atualização incluído na raiz: LEIA-PRIMEIRO.txt.
