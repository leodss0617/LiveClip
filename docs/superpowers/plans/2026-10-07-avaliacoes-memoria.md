# Avaliações e memória Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Avaliar blocos de 20 minutos sem clipes obrigatórios, guardar memória e entregar guia.
**Architecture:** Agendamento no worker, avaliação editorial em módulo separado, memória SQLite
limitada e feedback autenticado no painel. Jobs continuam limitados para execução local.
**Tech Stack:** Python, SQLite, FastAPI, JavaScript, Ollama local.
**Spec:** docs/superpowers/specs/2026-10-07-avaliacoes-memoria.md

## Global Constraints
- Intervalo 1200 segundos de vídeo recebido; sobra processada na finalização.
- Notas 0–10; mínimo 7 para gerar. Não confundir probabilidade acústica com interesse.
- Memória persistente, máximo 1000 registros e exemplos humanos recentes, apagável.
- Guia Windows/Linux/Termux incluso no ZIP. Sem cobrança ou treinamento de pesos.

## Review Focus
- Live curta: finalizar analisa sobra antes de 20 minutos.
- Reinício: checkpoint parcial de bloco continua, sem aguardar novo bloco.
- Zero candidatos: não gera; falha de avaliação não avança checkpoint.
- Feedback inválido/corte não pronto: rejeitar sem contaminar memória.
- Sem detecção de câmera: nota técnica identifica limitação, não promete 10.

### Task 1: Blocos e rubrica
- [x] Testar liberação aos 1200, 2400 e sobra final.
- [x] Implementar review_target e grade_candidates; context/end >=7 e média >=7.
- [x] Testar música de baixa relevância, nota inválida e falha na avaliação.

### Task 2: Memória e painel
- [x] Testar persistência, substituição de feedback, limite e limpeza.
- [x] Implementar Knowledge.record/context/feedback/clear, API autenticada e indicadores.
- [x] Registrar análises e edição; prompt usa só feedback humano como exemplos confirmados.

### Task 3: Guia e entrega
- [x] Guia LEIA-PRIMEIRO.txt com comandos exatos por plataforma e atualização.
- [x] Executar pytest, compilação Python e sintaxe JS/Bash; revisar mudanças.
- [x] Empacotar artefato validado, substituir ZIP existente e entregar.
