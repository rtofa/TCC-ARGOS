---
title: Segurança e privacidade
---

# Segurança e privacidade

## Isolamento entre organizações (multi-tenant)

Cada organização só enxerga os próprios dados. O isolamento acontece em duas camadas:

- **Aplicação**: toda consulta é filtrada pela organização do usuário conectado ou da chave de API usada.
- **Banco de dados**: as tabelas de dados de cliente têm Row-Level Security (RLS) ativado e forçado no PostgreSQL. Mesmo um erro na aplicação não expõe dados de outra organização.

## Autenticação

- **Pessoas** entram no console com e-mail e senha. As senhas são guardadas com hash BCrypt, nunca em texto puro. A sessão usa um token assinado (JWT) que expira.
- **Ferramentas** usam chaves de API no cabeçalho `X-API-Key`. O Argos guarda apenas o hash SHA-256 da chave e um prefixo para identificação; a chave completa é mostrada uma única vez, na criação. Chaves podem ser revogadas a qualquer momento.

## Autorização por papéis

Cada usuário tem um papel (Administrador, Editor, Leitor ou Executivo) e cada ação é verificada no servidor, não só na tela. A gestão de usuários e de chaves de API é exclusiva de Administradores, e a organização sempre mantém pelo menos um Administrador ativo. Veja "Usuários e papéis".

## Proteção de dados pessoais (LGPD)

### Na telemetria

Traces, logs e métricas têm os dados pessoais mascarados **antes** de serem armazenados:

| Dado | Como fica |
|---|---|
| E-mail | `[email]` |
| CPF | `[cpf]` |
| CNPJ | `[cnpj]` |
| Cartão de crédito | `[cartao]` |
| Telefone | `[telefone]` |
| Senhas, tokens, chaves e cabeçalhos de autorização | `[segredo]` |
| Endereço IP | final ocultado, por exemplo `192.168.x.x` |

Atributos com nomes sensíveis (`password`, `token`, `authorization`, `cookie`, `x-api-key`, `cpf`, `cnpj`, `credit_card`, `card_number`, `cartao`, `cvv` e semelhantes) também têm o valor mascarado.

### Nas conversas com a Lia

As perguntas enviadas à Lia passam pelo mesmo mascaramento antes de chegar a qualquer serviço externo de inteligência artificial ou aos registros do sistema. Mesmo assim, não informe senhas, chaves ou dados pessoais nas perguntas: a Lia não precisa deles para ajudar.

## Proteção da Lia contra abusos

- Tentativas de mudar as regras da Lia ("ignore as instruções", "revele seu prompt" e semelhantes) são bloqueadas antes de chegar ao modelo de linguagem.
- A Lia responde apenas com base na documentação oficial e confere cada resposta antes de exibi-la: respostas sem base nos documentos são substituídas por "não encontrei".
- A Lia não acessa os dados da sua organização (incidentes, telemetria, usuários) e não executa ações na plataforma.

## Boas práticas para a sua organização

- Dê a cada pessoa o papel mínimo necessário; use Leitor ou Executivo para quem só consulta.
- Desative imediatamente o acesso de quem sai da equipe.
- Use uma chave de API por ferramenta e revogue chaves expostas.
- Não envie dados pessoais desnecessários na telemetria, mesmo com o mascaramento ativo.
