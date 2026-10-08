# LiveClip no GitHub Codespaces

1. Abra https://github.com/codespaces/new?repo=leodss0617/LiveClip e escolha a máquina de **2 núcleos**.
2. Espere a configuração terminar. No terminal, digite `liveclip`.
3. Espere a construção e os modelos. Use `liveclip logs` para acompanhar.
4. Abra o endereço do painel mostrado no terminal e use a senha mostrada ali. A porta deve permanecer **Private**.
5. Cole um link público de YouTube, Kick ou Twitch. A seleção durante lives considera blocos de 20 minutos de vídeo recebido; não garante um corte por bloco. O celular apenas controla o painel.

Para atualizar: `liveclip atualizar`. Para parar os serviços: `liveclip parar`.
**Para encerrar o consumo de processamento, pare também o Codespace em https://github.com/codespaces.** Parar os serviços não desliga a máquina. Uma máquina parada ainda ocupa armazenamento; excluir o Codespace apaga seus arquivos e modelos. Baixe seus cortes antes.

Sem cadastrar pagamento, use somente a cota gratuita disponível em sua conta e mantenha cobrança adicional desativada. A cota é limitada; CPU, armazenamento, downloads e modelos contam para recursos da máquina. Codespaces de 2 núcleos não tem GPU e uma IA maior pode continuar lenta. O suporte de links depende de disponibilidade e restrições das plataformas.

Esta configuração foi preparada para Codespaces, mas sua execução na máquina remota e a qualidade de cortes reais ainda precisam ser verificadas. Não representa garantia de funcionamento perfeito ou de viralização.

## Botão de parada e contador

O painel mostra uma estimativa de horas reais de máquina. Informe o saldo que você conferiu em https://github.com/settings/billing. Se o GitHub mostrar core-hours, divida pela quantidade de núcleos da sua máquina para informar horas reais. O contador desconta somente o tempo observado com o serviço do painel em execução. Não considera outras máquinas, armazenamento, construção inicial nem intervalos com o serviço parado. Não detecta renovação: quando a cota mudar, confira no GitHub e atualize o saldo informado. Nunca use esta estimativa como garantia de gratuidade ou como medição oficial.

Para autorizar o botão **Parar máquina virtual** uma única vez:

1. Abra https://github.com/settings/personal-access-tokens/new e crie um token **fine-grained**, com prazo de validade curto, proprietário leodss0617 e acesso somente ao repositório LiveClip.
2. Em Repository permissions, adicione **Codespaces lifecycle admin: Read and write**. Não adicione acesso a outros repositórios.
3. Copie o token e abra https://github.com/settings/codespaces. Na seção de secrets, crie o segredo **LIVECLIP_CODESPACES_TOKEN**, cole o valor ali e autorize somente LiveClip. Não mande o token no chat nem coloque em arquivos do projeto.
4. Pare e abra novamente o Codespace para carregar o segredo. Execute `liveclip atualizar`.
5. No painel, cancele tarefas ativas e espere o encerramento. Baixe os cortes que deseja guardar; depois toque em **Parar máquina virtual** e confirme.

O botão envia a parada somente para a máquina atual. Sem autorização, fica desabilitado e mostra o link para parar pelo GitHub. Falhas de rede/permissão não são apresentadas como sucesso; confira sempre o estado na página de Codespaces. Quando a máquina parar, o painel fica indisponível. Arquivos permanecem no Codespace e armazenamento continua contabilizado. Para ligar novamente, use https://github.com/codespaces.

A parada remota foi testada com respostas simuladas 200/403 e timeout, sem desligar uma máquina real durante a validação.

## Recomeçar após ficar preso na criação (0.1.14)

O repositório agora usa o ambiente padrão do GitHub, sem instalar Features adicionais na criação. O postCreate só registra o comando local; modelos e serviços são preparados depois de o terminal abrir. Isso reduz etapas próprias do projeto, mas não garante o provisionamento da infraestrutura do GitHub.

1. Abra https://github.com/codespaces e localize a máquina antiga. Se o menu oferecer **Stop codespace**, pare antes de criar outra, para não manter duas máquinas consumindo recursos. Não exclua uma máquina que contenha cortes ou dados que deseja guardar.
2. Abra https://github.com/codespaces/new?repo=1409497795, escolha **main** e máquina de **2 núcleos**, e crie um Codespace novo. Ele usará a configuração atual do repositório.
3. Quando o terminal aparecer, execute `liveclip`.
4. Se o terminal informar erro, execute `liveclip diagnostico` e envie a saída, sem senhas ou tokens.
5. Se continuar somente em “Configurando seu espaço de código”, não repita criações. Consulte os logs de criação. Com um terminal GitHub CLI autenticado, o comando é `gh codespace logs -c NOME_COMPLETO_DO_CODESPACE`. No editor, Command Palette → Codespaces: View Creation Log. Sem terminal/editor, envie a tela da lista https://github.com/codespaces para verificar o estado real.

A tela de espera sozinha não permite distinguir erro de infraestrutura, download, construção ou falha de conexão do navegador. A causa exata da máquina antiga ainda não foi confirmada sem os logs. Atualizar o repositório não modifica automaticamente uma criação antiga em andamento.
