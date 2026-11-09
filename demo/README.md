# Ambiente de Demonstração do Argos (OpenTelemetry Demo)

Esta pasta contém a automação que conecta o **Argos** à **OpenTelemetry Demo (Astronomy Shop)**, uma aplicação de e-commerce em microsserviços mantida pelo projeto OpenTelemetry.

## Arquivos

- `seed.sh`: executado automaticamente pelo serviço `seed` do `docker-compose.yml` raiz. Cria a organização `Loja Demo`, o usuário `admin@demo.argos` (`Argos@2026!`) e uma chave de API, gravando-a em `demo/generated/argos.env`.
- `otelcol-config-extras.yml.template`: configuração extra do OpenTelemetry Collector que cria pipelines paralelos (`traces/argos`, `logs/argos`, `metrics/argos`) exportando via `otlphttp` (`encoding: json`) para `http://host.docker.internal:8081/api/webhooks/otlp` com o cabeçalho `X-API-Key`.
- `compose.argos.yaml`: camada complementar do Docker Compose da demo (mapeia `host.docker.internal` e ajusta limites/healthchecks para execução estável no Docker Desktop / WSL2 e Linux).
- `start-demo.ps1` / `start-demo.sh`: baixa a OpenTelemetry Demo (`3.1.0`), injeta a chave do Argos no Collector e sobe os containers.
- `trigger-failure.ps1` / `trigger-failure.sh`: ativa ou desativa falhas reais no `flagd` (`cartFailure`, `productCatalogFailure`, `paymentFailure`) e dispara o alerta correspondente no webhook do Argos.

## Passo a passo rápido

1. Na raiz do repositório, suba o Argos:
   ```bash
   docker compose up --build -d
   ```
2. Suba a aplicação de microsserviços:
   ```powershell
   # Windows
   powershell -ExecutionPolicy Bypass -File .\demo\start-demo.ps1
   ```
   ```bash
   # Linux / macOS
   ./demo/start-demo.sh
   ```
3. Injete uma falha e abra um incidente automaticamente:
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\demo\trigger-failure.ps1 -Flag productCatalogFailure
   ```
4. Investigue no console do Argos: http://localhost:3030/incidents
5. Reverta a falha e resolva o alerta:
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\demo\trigger-failure.ps1 -Flag productCatalogFailure -Reset
   ```
6. Para encerrar a aplicação de demonstração:
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\demo\start-demo.ps1 -Stop
   ```
