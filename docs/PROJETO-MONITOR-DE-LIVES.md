# Monitor de lives — proposta técnica

Estado: especificação para revisão; sistema ainda não implementado nem validado.

## Objetivo
Colar um link público de live do YouTube, Kick ou Twitch e receber cortes verticais editados, legendados e disponíveis para download pelo celular e pelo PC. A análise deve escolher momentos interessantes com contexto, sem prometer viralização.

## Arquitetura escolhida
Painel responsivo em português e serviço independente de processamento em Linux. O serviço pode executar em um PC ligado ou servidor Linux permanente. O celular acessa o mesmo painel: não precisa permanecer com a tela aberta. Isso não representa processamento independente dentro do Android.

O painel pode ser hospedado no Sites. Captura, modelos de transcrição, rastreamento e FFmpeg exigem um serviço externo ao runtime do painel. Não há servidor permanente provisionado nesta conversa. O ambiente temporário de desenvolvimento não será apresentado como hospedagem permanente.

Alternativas consideradas: processamento inteiramente no Android aumenta limitações de execução em segundo plano e aquecimento; processamento local somente no PC impede uso independente quando ele estiver desligado. Recomenda-se serviço permanente com painel comum aos dois dispositivos.

## Fluxo funcional
1. Validar plataforma e URL; aceitar somente hosts públicos das plataformas suportadas. Bloquear destinos privados, URLs arbitrárias e redirecionamentos para redes internas.
2. Resolver a live com adaptadores de captura. Diferenciar aguardando transmissão, capturando, reconectando, encerrada e falha. Nunca simular captura bem-sucedida.
3. Gravar segmentos com horários e manter buffer em disco. Limitar inicialmente cada sessão a dez horas; iniciar com uma live simultânea para limitar recursos.
4. Transcrever áudio em janelas sobrepostas, mantendo marcações de tempo. Analisar fala, reações e eventos visuais. Evitar selecionar somente o início da transmissão.
5. Classificar candidatos por gancho, clareza, reação e completude. Esperar o fechamento da fala ou história antes de concluir. Deduplicar candidatos sobrepostos. Gerar cortes de até cinco minutos; favorecer duração menor quando o contexto permitir.
6. Detectar rosto e conteúdo. Em uma reação, colocar rosto em cima e conteúdo embaixo, sem esticar imagens. Suavizar movimentos do enquadramento. Com várias pessoas, manter os participantes relevantes visíveis. Sem rosto detectável, usar enquadramento integral adequado e registrar essa decisão.
7. Renderizar MP4 vertical 1080 × 1920, H.264 e áudio AAC, com legendas sincronizadas e margens para interfaces sociais. Validar duração, áudio, orientação e legibilidade antes de marcar pronto.
8. Disponibilizar prévia e download. Captura e edição continuam sem o painel aberto. Parar monitoramento encerra a captura sem apagar os cortes concluídos.

## Componentes
- API em Python/FastAPI: sessões, progresso, cortes e downloads.
- Worker Python: captura, transcrição, seleção, rastreamento e renderização com FFmpeg.
- Adaptadores Streamlink/yt-dlp: verificar comportamento de cada plataforma e registrar versões efetivamente testadas.
- Transcrição local com faster-whisper; detecção/rastreamento com OpenCV e modelo adequado, com licenças registradas.
- Seleção semântica: avaliação sobre transcrição e imagens. Se um modelo adicional ou credencial for necessário, declarar a dependência e configuração antes de considerar essa função pronta. Picos de volume isolados não bastam para afirmar que a IA entende histórias.
- SQLite para sessões e fila na instalação inicial; arquivos em volume persistente. Reinicialização recupera sessões sem duplicar cortes.
- Distribuição do serviço em Docker Compose e guia em português, com comandos prontos. A interface e a versão do serviço devem ser compatíveis.

## Painel
Campo de link e botão Monitorar na primeira tela. Mostrar plataforma, estado real, tempo capturado, última atividade, fila e erros acionáveis. Galeria com título, duração, prévia, download e motivo da seleção. Controles para parar sessão e remover cortes com confirmação. Layout testado em larguras de celular e desktop, sem exigir extensão ou janela flutuante.

## Operação e proteção
Autenticação para acesso remoto, segredos fora do navegador, downloads protegidos, limites de disco e concorrência, retenção configurável e logs sem credenciais. Processos de mídia têm timeout e limpeza. Reiniciar worker pelo supervisor de serviços, com tentativas limitadas e aviso quando a recuperação falhar. A live pode estar indisponível, exigir autenticação ou mudar sua integração; apresentar o erro real.

## Critérios de aceitação
- Capturar uma live pública real em cada plataforma suportada; produzir corte reproduzível de cada uma. Testes com arquivos locais não substituem esses testes.
- Validar também um vídeo controlado com fala e rosto para conferir timestamps, legendas, áudio e composição.
- Inspecionar pelo menos um corte completo visualmente e audivelmente.
- Testar detecção sem rosto, várias pessoas, queda de rede, fim da live, reinício e limite de disco.
- Abrir painel e baixar/reproduzir MP4 em navegador desktop e Android. Emulação responsiva complementa, mas não substitui o teste físico no Android.
- Verificar que fechar o painel não encerra a captura no servidor.
- A entrega final informa testes executados, versões, ambiente e pendências. Nunca declarar perfeição ou validação de dispositivo que não foi testado.

## Entrega prevista
Código completo, pacote de instalação, painel acessível após implantação, guia passo a passo e relatório de validação. O relatório deve separar resultados reais, testes controlados e verificações ainda pendentes.

## Limites atuais
Nenhum servidor permanente foi configurado. Nenhuma live foi capturada. Não há acesso ao Android físico do usuário para validação. A implantação permanente pode exigir infraestrutura e custos; escolher um provedor e contratar recursos não faz parte de autorização presumida para construir código.
