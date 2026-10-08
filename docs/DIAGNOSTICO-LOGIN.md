Correção do diagnóstico de login — 08/10/2026

A API local respondeu 401 JSON a uma senha de teste inválida. O encaminhamento privado externo respondeu 401 sem JSON em uma requisição não autenticada. A entrada pelo navegador apresentou erro genérico, mas sua causa exata ainda não foi confirmada. A verificação do encaminhamento no navegador foi bloqueada por política do ambiente.

O cliente agora diferencia erros JSON da aplicação, recusas HTTP 401/403 sem JSON e respostas HTTP 200 inválidas. Não aceita HTML como sucesso e informa o código HTTP. Nenhuma proteção de autenticação foi removida.

Validação: teste executa a função API real em Node com seis cenários (401 vazio, senha incorreta, 200 HTML, 422 com lista, 200 JSON válido e expiração da sessão). Falhou antes da alteração e passou depois. node --check também passou. Não foi validado login externo nem implantada esta alteração no Codespace existente.
