"""Teste de fumaça do Argos: percorre os fluxos principais via HTTP contra um ambiente publicado.

Uso:  python infra/scripts/smoke-test.py http://<app_url>

Cria duas organizações de teste ("Loja A" e "Loja B", com e-mails únicos por execução), então rode no ensaio do
deploy, não no ambiente da apresentação. Sai com código 1 se alguma verificação falhar.
"""
import json
import io
import sys
import time
import urllib.error
import urllib.request

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8081").rstrip("/")
# Acentos corretos também no console do Windows.
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
RUN = str(int(time.time()))
results = []


def call(method, path, body=None, token=None, key=None, raw=None):
    data = raw.encode() if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    if key:
        req.add_header("X-API-Key", key)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            text = r.read().decode()
            return r.status, (json.loads(text) if text.strip().startswith(("{", "[")) else text)
    except urllib.error.HTTPError as e:
        text = e.read().decode()
        try:
            return e.code, json.loads(text)
        except Exception:
            return e.code, text


def check(name, status, ok_codes, body=None, extra=True):
    ok = status in ok_codes and extra
    results.append(ok)
    detail = "" if ok else f" -> {str(body)[:200]}"
    print(f"{'PASS' if ok else 'FAIL'}  {status}  {name}{detail}")
    return ok


def nanos(t):
    return str(int(t * 1_000_000_000))


now = time.time()
email1 = f"admin{RUN}@a.test"
s, b = call("POST", "/api/auth/register", {"organizationName": "Loja A", "userName": "Admin A",
                                           "email": email1, "password": "senha-forte-1"})
check("cadastro da organização A", s, {200, 201}, b)
s, b = call("POST", "/api/auth/login", {"email": email1, "password": "senha-forte-1"})
check("login do administrador A", s, {200}, b)
tok = b.get("token") if isinstance(b, dict) else None
if not tok:
    print("Sem token: o resto do teste depende do login.")
    sys.exit(1)

s, b = call("POST", "/api/auth/register", {"organizationName": "Outra", "userName": "X",
                                           "email": email1, "password": "senha-forte-1"})
check("cadastro com e-mail repetido é recusado (4xx)", s, {400, 409}, b)

s, b = call("GET", "/api/users", token=tok)
check("lista de usuários", s, {200}, b, isinstance(b, list) and len(b) == 1)
editor = f"editor{RUN}@a.test"
s, b = call("POST", "/api/users", {"name": "Editor A", "email": editor, "password": "senha-123", "role": "EDITOR"},
            token=tok)
check("criar usuário Editor", s, {200, 201}, b)
s, b = call("POST", "/api/auth/login", {"email": editor, "password": "senha-123"})
check("login do Editor", s, {200}, b)

s, b = call("POST", "/api/incidents", {"title": "Checkout lento", "severity": "SEV2",
                                       "affectedServices": ["checkout"]}, token=tok)
check("abrir incidente manual", s, {200, 201}, b)
inc = b.get("id") if isinstance(b, dict) else None
s, b = call("PATCH", f"/api/incidents/{inc}", {"status": "INVESTIGATING"}, token=tok)
check("mudar status do incidente", s, {200}, b)
s, b = call("POST", f"/api/incidents/{inc}/comments", {"body": "Investigando o banco"}, token=tok)
check("comentar no incidente", s, {200, 201}, b)
s, b = call("GET", f"/api/incidents/{inc}/timeline", token=tok)
check("linha do tempo do incidente", s, {200}, b)
s, b = call("GET", "/api/incidents/metrics", token=tok)
check("métricas de incidentes", s, {200}, b)

s, b = call("POST", "/api/api-keys", {"name": "Collector"}, token=tok)
check("gerar chave de API", s, {200, 201}, b)
key = b.get("key") if isinstance(b, dict) else None
s, b = call("GET", "/api/webhooks/ping", key=key)
check("ping com a chave de API", s, {200}, b)

trace = "a" * 32
spans = json.dumps({"resourceSpans": [{"resource": {"attributes": [
    {"key": "service.name", "value": {"stringValue": "checkout"}}]}, "scopeSpans": [{"scope": {"name": "t"}, "spans": [
        {"traceId": trace, "spanId": "b" * 16, "name": "GET /cart", "kind": 2, "startTimeUnixNano": nanos(now - 1),
         "endTimeUnixNano": nanos(now), "status": {"code": 2, "message": "boom"},
         "attributes": [{"key": "http.status_code", "value": {"intValue": "500"}}]}]}]}]})
s, b = call("POST", "/api/webhooks/otlp/v1/traces", raw=spans, key=key)
check("OTLP: traces", s, {200, 202}, b)
logs = json.dumps({"resourceLogs": [{"resource": {"attributes": [
    {"key": "service.name", "value": {"stringValue": "checkout"}}]}, "scopeLogs": [{"logRecords": [
        {"timeUnixNano": nanos(now), "severityNumber": 17, "severityText": "ERROR",
         "body": {"stringValue": "falha no pagamento"}, "traceId": trace}]}]}]})
s, b = call("POST", "/api/webhooks/otlp/v1/logs", raw=logs, key=key)
check("OTLP: logs", s, {200, 202}, b)
metrics = json.dumps({"resourceMetrics": [{"resource": {"attributes": [
    {"key": "service.name", "value": {"stringValue": "checkout"}}]}, "scopeMetrics": [{"metrics": [
        {"name": "cpu.usage", "unit": "1", "gauge": {"dataPoints": [{"timeUnixNano": nanos(now), "asDouble": 0.93}]}}]}]}]})
s, b = call("POST", "/api/webhooks/otlp/v1/metrics", raw=metrics, key=key)
check("OTLP: métricas", s, {200, 202}, b)
s, b = call("GET", "/api/telemetry/services", token=tok)
check("telemetria mostra o serviço", s, {200}, b, "checkout" in json.dumps(b))
s, b = call("GET", f"/api/telemetry/traces/{trace}", token=tok)
check("detalhe do trace", s, {200}, b)

s, b = call("POST", "/api/webhooks/alerts", {"title": "CPU alta no checkout", "dedupKey": f"cpu-{RUN}",
                                             "status": "firing", "severity": "SEV2", "service": "checkout"}, key=key)
check("alerta externo via webhook", s, {200, 201, 202}, b)
s, b = call("POST", "/api/webhooks/deploys", {"service": "checkout", "version": "1.4.2", "commitSha": "abc1234"},
            key=key)
check("deploy via webhook", s, {200, 201, 202}, b)
s, b = call("GET", "/api/deploys", token=tok)
check("lista de deploys", s, {200}, b)
s, b = call("GET", "/api/metrics/dora", token=tok)
check("métricas DORA", s, {200}, b)

s, b = call("POST", "/api/business/kpis", {"name": "Pedidos", "unit": "pedidos", "baselinePerMinute": 12,
                                           "valuePerUnit": 150, "services": [{"service": "checkout", "impactPercent": 80}]},
            token=tok)
check("criar KPI de negócio", s, {200, 201}, b)
s, b = call("GET", "/api/business/executive", token=tok)
check("painel executivo", s, {200}, b)
s, b = call("POST", "/api/notifications/channels", {"name": "Plantão", "type": "EMAIL", "target": "ops@a.test",
                                                    "minSeverity": "SEV2"}, token=tok)
check("criar canal de notificação", s, {200, 201}, b)
s, b = call("GET", "/api/telemetry/rules", token=tok)
check("regras de telemetria", s, {200}, b)
s, b = call("GET", "/api/support/knowledge", token=tok)
check("Lia: base de conhecimento", s, {200}, b)

time.sleep(3)
s, b = call("GET", "/api/incidents", token=tok)
body = json.dumps(b)
check("incidente aberto pelo alerta aparece na lista", s, {200}, b, "CPU alta no checkout" in body)

email2 = f"admin{RUN}@b.test"
s, b = call("POST", "/api/auth/register", {"organizationName": "Loja B", "userName": "Admin B",
                                           "email": email2, "password": "senha-forte-2"})
check("cadastro da organização B", s, {200, 201}, b)
tok2 = b.get("token") if isinstance(b, dict) else None
s, b = call("GET", "/api/incidents", token=tok2)
check("isolamento: B não vê incidentes de A", s, {200}, b, "Checkout lento" not in json.dumps(b))
s, b = call("GET", f"/api/incidents/{inc}", token=tok2)
check("isolamento: B não abre incidente de A por id", s, {403, 404}, b)

s, b = call("GET", "/actuator/health/readiness")
check("saúde (readiness)", s, {200}, b)

print(f"\n{sum(results)}/{len(results)} verificações passaram")
sys.exit(0 if all(results) else 1)
