# EditorClipesDSS — Android 3.4.5 (18/11/2026)

Codigo-fonte atualizado do EditorClipesDSS no Termux, com versionCode 33
e versionName 3.4.5.

## Recursos
- Android: streamer e reacao em cortes verticais 9:16, legendas e clipes.
- Bibliotecas de clipes e botoes de compartilhamento com TikTok.
- Kick/Twitch: login em painel seguro Custom Tabs iniciado no aplicativo.
  Google nao permite login em WebView. Autenticacao de navegador nao
  concede, por si, acesso OAuth as APIs das plataformas.
- Monitor Kick: chat publico consultado periodicamente; ranking por picos
  de participacao, reacoes e alinhamento com fala/audio e contexto.
- Motor Python em bridge para captura de lives, exportacao e testes.

## Testes e instalacao
No Termux, o motor passou em 83 testes (3 ignorados) e o APK
3.4.5 foi compilado com sucesso. A integracao visual deve ser
validada em uso real no aparelho.
Para compilar no GitHub, use GitHub Actions com JDK 17 e Android API 36.
O workflow gera chave de debug temporaria: o APK gerado em CI pode
nao atualizar a instalacao assinada pelo debug.keystore do Termux.

## Ambiente
O override android.aapt2FromMavenOverride do Termux deve ficar somente
no arquivo de configuracao local, nunca no repositorio compartilhado.
O chat da Kick depende de endpoint publico nao oficial, sujeito a mudancas.
O monitoramento em segundo plano depende das restricoes do Android.

## Privacidade
Nao versionar senhas, tokens, cookies, arquivos .env, keystores,
logs, bancos locais, videos particulares, caches ou backups.

## Video de demonstracao
O unico MP4 no repositorio e app/src/main/res/raw/sample_live.mp4 (amostra de aproximadamente 4 segundos). Ele e necessario porque SampleVideoHelper referencia R.raw.sample_live durante a compilacao. Os clipes de lives e videos particulares continuam ignorados.
