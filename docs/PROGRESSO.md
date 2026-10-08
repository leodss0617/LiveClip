# Progresso — IMPLEMENTACAO.md

Execução direta autorizada pelo usuário: construir sem perguntas. Revisões de planejamento não bloqueiam.
Pre-flight: Store alimenta Engine e API; Candidate e Word alimentam render; identidades persistentes por UUID.
Decisão: runtime do Sites não comporta Python/FFmpeg/modelos; distribuir serviço completo em Docker, não publicar painel desconectado como produto funcional. Hospedagem permanente depende de acesso à infraestrutura.

Revisão independente: janelas excessivas, perda de cauda na parada, exclusão concorrente, retomada indevida de captura, contexto insuficiente e cancelamento de tarefas.
Correções: janelas de até 420 segundos com avanço de 90 e contexto de 330; esperar captura finalizar e processar cauda; bloquear exclusão/reanálise enquanto worker possui arquivos; preservar modo finishing; filhos canceláveis por grupo de processos.
Verificação: 21 testes passaram; navegador validou controles e download, sem reprodução H.264 no Chromium de teste.
Limite material: sem implantação permanente, YouTube/Twitch e Android físico não validados, qualidade da seleção semântica ainda sem validação positiva.
