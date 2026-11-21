---
title: KPIs de negócio e impacto financeiro
---

# KPIs de negócio e impacto financeiro

O Argos traduz indisponibilidade técnica em impacto de negócio: quantos pedidos (ou outra unidade) deixaram de acontecer e quanto dinheiro ficou em risco durante cada incidente. Para isso, a organização cadastra os seus **KPIs de negócio** e diz quais serviços sustentam cada um.

## Cadastrar um KPI

Na tela **KPIs de negócio**, Administradores e Editores clicam em **Novo KPI** e preenchem:

| Campo | Descrição | Exemplo |
|---|---|---|
| **Nome** | Nome do KPI (único na organização) | Pedidos |
| **Unidade** | Unidade de medida | pedidos |
| **Volume normal por minuto** | Quanto o KPI produz por minuto em operação normal (não pode ser negativo) | 12 |
| **Valor por unidade (R$, opcional)** | Quanto vale cada unidade | 180 |
| **Descrição** | Explicação do KPI | Pedidos concluídos no checkout |
| **Serviços que sustentam este KPI** | Cada serviço com o seu **Peso em %** | checkout 100%, payment 90% |

O **peso** diz quanto do KPI se perde se o serviço parar totalmente: 100% significa que o KPI zera sem ele.

Clique em **Adicionar serviço** para incluir mais serviços e em **Remover serviço** para tirar um. Use os mesmos nomes de serviço da telemetria e dos incidentes. Salve para concluir.

Sem valor por unidade, o Argos calcula as unidades perdidas, mas não o valor em reais.

## Tabela de KPIs

A lista mostra **Nome**, **Volume normal**, **Valor por unidade**, **Valor por minuto** (volume × valor, ou seja, quanto o KPI vale por minuto de operação), **Serviços (peso)** e as ações **Editar** e **Excluir**. Leitores e Executivos apenas consultam.

## Como o impacto de um incidente é calculado

Para cada KPI ligado a um serviço afetado pelo incidente:

**unidades perdidas = volume normal por minuto × peso do serviço × degradação × minutos do incidente**

**prejuízo = unidades perdidas × valor por unidade**

### Degradação

A degradação é obtida de duas formas:

| Método | Quando | Degradação usada |
|---|---|---|
| **Telemetria** | Há telemetria suficiente do serviço durante o incidente | A taxa de erro real medida nos spans |
| **Severidade** | Não há telemetria suficiente | SEV1 = 100%, SEV2 = 50%, SEV3 = 20%, SEV4 = 5% |

Se nenhum serviço afetado está ligado a um KPI, o incidente fica **sem estimativa** e o impacto aparece como "—" (ou zero).

### Duração

Os minutos contam da abertura até a resolução. Em incidentes ainda abertos, contam até agora: o prejuízo cresce enquanto o incidente não for resolvido.

### Exemplo

KPI "Pedidos": 10 pedidos por minuto, R$ 200 cada, checkout com peso 100%. Um incidente SEV2 no checkout durante 30 minutos, sem telemetria suficiente:

10 × 100% × 50% × 30 = **150 pedidos perdidos**, ou 150 × R$ 200 = **R$ 30.000**.

Se o mesmo incidente afetar também o payment (peso 80%), o cálculo soma a contribuição de cada serviço.

## Onde o impacto aparece

- **Início**: cartão "Perda estimada · 24 h" e coluna "Impacto estimado" dos incidentes ativos.
- **Detalhe do incidente**: quadro "Impacto no negócio", com o valor total, as unidades por KPI e o método usado.
- **Dashboards**: visão Executivo, com impacto diário, por serviço e por KPI e os maiores incidentes do período.

## Boas práticas

- Use o volume de um dia típico, no horário de pico se quiser uma estimativa conservadora.
- Comece pelos KPIs que a diretoria acompanha (pedidos, receita, cadastros, pagamentos).
- Revise os pesos quando a arquitetura mudar.
