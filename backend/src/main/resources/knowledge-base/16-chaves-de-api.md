---
title: Chaves de API
---

# Chaves de API

As chaves de API autenticam máquinas e ferramentas externas, sem usuário e senha. Elas são enviadas no cabeçalho `X-API-Key` e identificam a organização dona dos dados.

## Onde as chaves são usadas

| Uso | Endereço |
|---|---|
| Enviar telemetria OpenTelemetry | `POST /api/webhooks/otlp/v1/traces`, `/logs` e `/metrics` |
| Enviar alertas | `POST /api/webhooks/alerts` e `POST /api/webhooks/alerts/<id da fonte>` |
| Registrar deploys | `POST /api/webhooks/deploys` |
| Consultar o Quality Gate | `GET /api/webhooks/deploys/quality-gate` |
| Testar a chave | `GET /api/webhooks/ping` |

As chaves não dão acesso ao console nem às demais APIs: elas servem apenas para esses endereços de integração.

## Criar uma chave

Somente Administradores gerenciam chaves.

1. Abra **Chaves de API**.
2. Clique em **Nova chave**.
3. Dê um **Nome** que identifique onde a chave será usada (até 100 caracteres), por exemplo "Grafana produção" ou "GitHub Actions".
4. Clique em **Gerar chave**.
5. **Copie agora**: a chave começa com `argos_` e é mostrada **uma única vez**. Por segurança, o Argos guarda só um resumo (hash SHA-256) e não consegue mostrá-la de novo. Clique em **Já copiei** para fechar.

Administradores também podem gerar uma chave direto no assistente **Conectar App**.

## Lista de chaves

| Coluna | Conteúdo |
|---|---|
| **Nome** | Nome dado na criação |
| **Chave** | Prefixo da chave, para identificação |
| **Status** | Ativa ou Revogada (com a data da revogação) |
| **Criada** | Data de criação |
| **Último uso** | Último uso da chave por uma ferramenta |
| **Ações** | **Revogar** |

## Revogar uma chave

Clique em **Revogar** na linha da chave. A partir daí, as chamadas com essa chave recebem **HTTP 401**. A revogação não pode ser desfeita: crie uma nova chave e atualize a ferramenta.

Revogue imediatamente qualquer chave exposta (publicada em repositório, enviada em chat, etc.).

## Testar uma chave

A tela mostra o comando "Teste a chave":

```bash
curl "<URL da API>/webhooks/ping" -H "X-API-Key: <sua chave>"
```

Com uma chave válida, a resposta traz a organização e o prefixo da chave. Com chave inválida ou revogada, a resposta é HTTP 401.

## Boas práticas

- Uma chave por ferramenta e por ambiente: assim é possível revogar uma sem parar as outras.
- Guarde as chaves como segredos (variáveis de ambiente, cofres de segredos, secrets do GitHub), nunca no código.
- Revise periodicamente a coluna **Último uso** e revogue chaves que não são mais usadas.
