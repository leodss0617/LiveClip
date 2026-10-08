# Avaliações periódicas e memória local

O usuário autorizou implementação autônoma sem novas perguntas. Objetivo: avaliar cada
20 minutos de vídeo recebido, podendo não gerar clipes; cada resultado mostra nota
estimada de 0 a 10; incluir instalação de Windows/Linux/Termux dentro do ZIP.

O worker libera blocos completos de 1200 segundos para seus jobs limitados de 90 segundos
com contexto sobreposto; nunca envia 20 minutos de transcrição de uma vez à IA. O fim
da captura libera a sobra menor. Checkpoints continuam por trecho e só avançam após
sucesso. A borda artificial entre blocos não conta como fim de história ou música.

Critérios editoriais: interesse, contexto e desfecho, cada um 0–10, com justificativa.
Descartar nota menor que 7; nenhuma obrigação de gerar. O classificador musical não
substitui avaliação editorial. Nota de edição é verificação técnica separada do conteúdo;
10/10 é avaliação estimada, não garantia de qualidade/viralização. Falhas não recebem
estado pronto. Usuário pode dar nota 0–10 e observação depois de assistir.

Memória SQLite limitada: últimas avaliações editoriais e técnicas e feedback humano.
Próximas seleções recebem estatísticas e exemplos com feedback humano. Sem treinamento
de pesos, sem promessa de melhora monotônica, sem serviços pagos. Notas próprias não
viram exemplos de qualidade confirmada. Base apagável; dados preservados na atualização.
