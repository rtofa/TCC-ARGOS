---
title: Usuários e papéis
---

# Usuários e papéis

O Argos controla o acesso por papéis (RBAC). Cada usuário pertence a uma única organização e tem um único papel.

## Papéis

| Papel | Resumo |
|---|---|
| **Administrador** | Acesso total: gerencia usuários, chaves de API, integrações e incidentes |
| **Editor** | Opera incidentes, configura alertas, regras SLO, canais de notificação e KPIs |
| **Leitor** | Visualiza incidentes, telemetria e métricas sem alterar configurações |
| **Executivo** | Foco em dashboards executivos, impacto financeiro e métricas DORA; visualiza sem alterar |

## Permissões detalhadas

| Ação | Administrador | Editor | Leitor | Executivo |
|---|---|---|---|---|
| Ver todas as telas de consulta (Início, Visão geral, Incidentes, Deploys, Telemetria, Dashboards, Integrações, KPIs, Fontes de alerta) | sim | sim | sim | sim |
| Conversar com a Lia | sim | sim | sim | sim |
| Criar incidentes, mudar status e severidade, comentar e atribuir | sim | sim | não | não |
| Criar, editar e remover fontes de alerta e regras do Monitor SLO | sim | sim | não | não |
| Criar, editar, testar e remover canais de notificação | sim | sim | não | não |
| Cadastrar, editar e excluir KPIs de negócio | sim | sim | não | não |
| Gerenciar usuários (tela Usuários) | sim | não | não | não |
| Criar e revogar chaves de API (tela Chaves de API) | sim | não | não | não |
| Gerar chave no assistente Conectar App | sim | não | não | não |
| Reindexar a base de conhecimento da Lia | sim | não | não | não |

Quando alguém tenta uma ação sem permissão, o console mostra "Você não tem permissão para esta ação."

## Gerenciar usuários

A tela **Usuários** ("Usuários e Acessos") é exclusiva do Administrador. Para os outros papéis aparece "Acesso restrito".

### Lista de membros

| Coluna | Conteúdo |
|---|---|
| **Usuário** | Nome e e-mail; o seu próprio usuário aparece marcado como "Você" |
| **Papel** | Papel atual, que pode ser alterado direto na tabela |
| **Status** | Ativo ou Desativado |
| **Último acesso** | Último login, ou "Nunca acessou" |
| **Ações** | **Redefinir senha** e **Desativar** (ou **Ativar**, para contas desativadas) |

O campo "Filtrar por nome ou e-mail…" encontra membros rapidamente.

### Adicionar um membro

1. Clique em **Adicionar usuário à organização**.
2. Preencha **Nome completo** (até 120 caracteres), **E-mail corporativo**, **Senha inicial (mín. 6 caracteres)** e **Papel (RBAC)**.
3. Clique em **Criar usuário**. A mensagem "Usuário criado e habilitado na organização." confirma.
4. Passe a senha inicial para a pessoa por um canal seguro.

O e-mail precisa ser válido e não pode estar em uso por outro usuário.

### Alterar o papel

Escolha o novo papel na coluna **Papel**. A mudança passa a valer quando o usuário entrar novamente.

### Desativar ou reativar

Use **Desativar** para bloquear o acesso de quem não precisa mais dele (por exemplo, quem saiu da empresa). A conta continua no histórico (comentários, atribuições), mas não consegue entrar. Para devolver o acesso, clique em **Ativar** na mesma linha.

### Redefinir a senha

Clique em **Redefinir senha**, digite a nova senha temporária (mínimo de 6 caracteres) e clique em **Salvar senha**. Repasse a senha ao usuário.

## Regras de segurança

- A organização precisa manter **pelo menos um Administrador ativo**: não é possível rebaixar nem desativar o último Administrador ("A organização deve manter pelo menos um administrador ativo.").
- Ninguém pode desativar o **próprio acesso** ("Você não pode desativar o seu próprio acesso.").
- Cada usuário pertence a uma única organização, e o isolamento entre organizações é garantido no banco de dados (RLS).

O Argos não envia convites nem recuperação de senha por e-mail: o Administrador cria o acesso e repassa a senha.
