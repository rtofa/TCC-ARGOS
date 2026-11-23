---
title: Lia, a assistente do Argos
---

# Lia, a assistente do Argos

A **Lia** é a assistente virtual do Argos. Ela tira dúvidas sobre o funcionamento, as funcionalidades, os processos e o uso da plataforma, respondendo somente com base nesta documentação oficial.

## Como falar com a Lia

1. Clique no **balão com ponto de interrogação** no canto inferior direito de qualquer tela (ao passar o mouse aparece "Dúvidas? Fale com a Lia").
2. Escreva a pergunta no campo "Pergunte para a Lia…" e pressione Enter (Shift+Enter quebra a linha), ou clique em uma das sugestões.
3. A resposta aparece enquanto é escrita.
4. Para interromper uma resposta, clique no botão de parar. Para recomeçar do zero, use o botão de **Nova conversa** no topo do chat.
5. Feche o chat no **X** ou com a tecla Esc. A conversa continua guardada enquanto você navega entre as telas.

Todos os papéis (Administrador, Editor, Leitor e Executivo) podem conversar com a Lia.

## O que perguntar

- Como fazer algo: "Como configuro notificações no Slack?", "Como registro um deploy pelo GitHub Actions?"
- Como algo funciona: "Quando o Quality Gate bloqueia um deploy?", "Como o prejuízo de um incidente é calculado?"
- O que significa algo: "O que é MTTA?", "O que são métricas RED?"
- Onde encontrar algo: "Onde vejo os traces com erro de um incidente?"
- Problemas: "Minha aplicação não aparece na Telemetria, o que pode ser?"

A Lia entende perguntas de continuação, como "e para o Discord?" depois de perguntar sobre o Slack.

## O que a Lia não faz

- **Não responde assuntos fora do Argos.** Nesses casos ela avisa que só ajuda com a plataforma.
- **Não inventa.** Quando a documentação não cobre a pergunta, ela responde "Não encontrei essa informação na documentação do Argos."
- **Não acessa os dados da sua organização.** Ela não vê seus incidentes, sua telemetria nem seus usuários: ela explica como você mesmo encontra essas informações.
- **Não executa ações.** Ela não abre incidentes, não altera configurações e não cria chaves; ela explica o passo a passo.
- **Não segue instruções para mudar o próprio comportamento** nem revela as próprias instruções.

## Como a Lia chega a uma resposta

1. **Verificação da pergunta**: dados pessoais são mascarados e tentativas de manipulação são bloqueadas.
2. **Entendimento**: a Lia identifica se a pergunta é sobre o Argos e a reescreve quando ela depende da conversa anterior.
3. **Busca na documentação**: a documentação é buscada de duas formas ao mesmo tempo, por palavras (busca textual) e por significado (busca semântica), e os resultados são combinados e ordenados por relevância.
4. **Resposta**: a Lia escreve a resposta usando apenas os trechos encontrados e marca de qual trecho veio cada informação (essas marcações servem à conferência e não aparecem na tela).
5. **Conferência**: antes de exibir, a resposta é conferida com os trechos. Se ela citar fontes inexistentes ou trouxer dados que não estão na documentação, é trocada por "não encontrei".

Se os modelos de linguagem estiverem indisponíveis, a Lia mostra os trechos da documentação mais relacionados à pergunta.

## Dicas para perguntar melhor

- Use os nomes das telas e funcionalidades (Incidentes, Quality Gate, Monitor SLO, KPIs de negócio).
- Faça uma pergunta por vez.
- Se a resposta não ajudou, reformule com mais detalhes ou pergunte de outro jeito.

## Base de conhecimento (Administradores)

Administradores veem, no botão de informações do chat, a base de conhecimento usada pela Lia: quantidade de documentos e trechos, se a busca é híbrida ou somente textual, se o reranking está ligado e quais modelos de linguagem estão configurados. O botão **Reindexar documentos** processa novamente a documentação (só os documentos novos ou alterados são reprocessados).

## Privacidade

As perguntas passam por mascaramento de dados pessoais antes de chegar a qualquer serviço externo. Mesmo assim, não informe senhas, chaves de API ou dados pessoais nas perguntas.
