# LiveClip Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Execução direta autorizada pelo usuário, sem perguntas adicionais.

**Goal:** monitorar lives e gerar MP4 vertical com seleção semântica, rosto e legendas.
**Architecture:** API e painel no mesmo serviço Python, captura isolada, análise em worker, SQLite e volumes persistentes. Docker Compose fornece Ollama para análise local.
**Tech Stack:** FastAPI, SQLite, Streamlink, FFmpeg, OpenCV, faster-whisper, Ollama.
**Spec:** PROJETO-MONITOR-DE-LIVES.md

## Global Constraints
- Interface em português, uma live simultânea, até dez horas, cortes de até cinco minutos.
- Nenhum estado simulado; sem afirmar teste físico no Android ou hospedagem permanente não efetuada.
- Downloads autenticados, sem credenciais em logs; fontes limitadas às plataformas.

## Review Focus
- Parada e retomada durante captura e renderização.
- URL maliciosa, travessia de caminhos e conteúdo de legenda com sintaxe de ASS.
- Modelo indisponível ou resposta inválida.
- Sem áudio, sem rosto e vários rostos.
- Espaço em disco baixo e segmentos incompletos.

## Task 1: persistência e segurança
Files: liveclip/store.py, liveclip/security.py, tests/test_core.py.
Interfaces: Store(path), create_session(url), update_session(id, **fields), sessions(), clips(); validate_url(url) -> (platform, normalized_url).
- [ ] Escrever e executar testes de URL, persistência, idempotência e exclusão segura; confirmar falha antes da implementação.
- [ ] Implementar SQLite transacional e validação de URL; executar python -m pytest tests/test_core.py -q, esperado: sucesso.

## Task 2: seleção e edição
Files: liveclip/intelligence.py, liveclip/media.py, tests/test_media.py.
Interfaces: validate_candidates(payload, transcript, duration); write_ass(words, start, end, path); render(source, start, end, words, out).
- [ ] Executar testes de limites semânticos, injeção de legenda, renderização real e saída sem áudio; esperado: falha inicial.
- [ ] Implementar Whisper, seleção Ollama, rastreamento e FFmpeg; executar testes com inspeção por ffprobe e imagem.

## Task 3: captura, worker e API
Files: liveclip/capture.py, liveclip/worker.py, liveclip/app.py, tests/test_api.py.
Interfaces: Capture(url, folder, stop_event); Engine(store, settings); create_app(settings) -> FastAPI.
- [ ] Escrever testes de autenticação, limite de sessões, idempotência e rotas de download; esperado: falha inicial.
- [ ] Implementar reconexão, fila persistente, renderização independente e recuperação; executar suite inteira.

## Task 4: painel e distribuição
Files: liveclip/static/*, Dockerfile, compose.yaml, iniciar.sh, iniciar.ps1, README.md.
- [ ] Testar acesso e interação em viewport desktop e celular, inclusive estado sem cortes e erro de rede.
- [ ] Gerar fixture de vídeo e MP4 real; tentar modelos e lives reais, registrar impedimentos.
- [ ] Revisar código com revisor independente; corrigir achados importantes e executar suite.
- [ ] Empacotar código e relatório. Só hospedar se houver infraestrutura permanente adequada acessível.
