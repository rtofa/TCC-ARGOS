import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, publicApiUrl } from '../api.js'
import { useApi } from '../hooks/useApi.js'
import { useAuth } from '../auth/useAuth.js'
import { CodeBlock } from '../components/Code.jsx'
import PageGuide from '../components/PageGuide.jsx'
import { timeAgo } from '../format.js'

const STACKS = [
  { id: 'otel', label: 'OpenTelemetry Collector / Docker' },
  { id: 'java', label: 'Java / Spring Boot' },
  { id: 'node', label: 'Node.js / Express' },
  { id: 'python', label: 'Python / FastAPI' },
  { id: 'go', label: 'Go (OTLP HTTP)' },
  { id: 'curl', label: 'Teste rápido via cURL' },
]

function buildSnippet(stack, apiUrl, apiKey) {
  const key = apiKey || '<SUA_ARGOS_API_KEY>'
  switch (stack) {
    case 'otel':
      return `# otelcol-config.yaml
exporters:
  otlphttp/argos:
    endpoint: ${apiUrl}/webhooks/otlp
    encoding: json
    compression: none
    headers:
      X-API-Key: "${key}"

service:
  pipelines:
    traces:
      receivers: [otlp]
      exporters: [otlphttp/argos]
    logs:
      receivers: [otlp]
      exporters: [otlphttp/argos]
    metrics:
      receivers: [otlp]
      exporters: [otlphttp/argos]`
    case 'java':
      return `# Variáveis de ambiente da aplicação Spring Boot / JVM com OpenTelemetry Agent
OTEL_SERVICE_NAME=meu-servico-java
OTEL_EXPORTER_OTLP_PROTOCOL=http/json
OTEL_EXPORTER_OTLP_ENDPOINT=${apiUrl}/webhooks/otlp
OTEL_EXPORTER_OTLP_HEADERS=X-API-Key=${key}

java -javaagent:opentelemetry-javaagent.jar -jar app.jar`
    case 'node':
      return `# Variáveis de ambiente para Node.js (@opentelemetry/auto-instrumentations-node)
export OTEL_SERVICE_NAME="meu-servico-node"
export OTEL_EXPORTER_OTLP_PROTOCOL="http/json"
export OTEL_EXPORTER_OTLP_ENDPOINT="${apiUrl}/webhooks/otlp"
export OTEL_EXPORTER_OTLP_HEADERS="X-API-Key=${key}"

node --require @opentelemetry/auto-instrumentations-node/register server.js`
    case 'python':
      return `# Variáveis de ambiente para Python (opentelemetry-distro + opentelemetry-exporter-otlp-proto-http)
export OTEL_SERVICE_NAME="meu-servico-python"
export OTEL_EXPORTER_OTLP_PROTOCOL="http/json"
export OTEL_EXPORTER_OTLP_ENDPOINT="${apiUrl}/webhooks/otlp"
export OTEL_EXPORTER_OTLP_HEADERS="X-API-Key=${key}"

opentelemetry-instrument uvicorn main:app --host 0.0.0.0 --port 8000`
    case 'go':
      return `// Configuração via variáveis de ambiente padrão do SDK Go (otlptracehttp / otlploghttp)
export OTEL_SERVICE_NAME="meu-servico-go"
export OTEL_EXPORTER_OTLP_ENDPOINT="${apiUrl}/webhooks/otlp"
export OTEL_EXPORTER_OTLP_HEADERS="X-API-Key=${key}"`
    default:
      return `curl -X POST "${apiUrl}/webhooks/otlp/v1/traces" \\
  -H "Content-Type: application/json" \\
  -H "X-API-Key: ${key}" \\
  -d '{
    "resourceSpans": [{
      "resource": { "attributes": [{ "key": "service.name", "value": { "stringValue": "minha-app-teste" } }] },
      "scopeSpans": [{ "spans": [{
        "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
        "spanId": "00f067aa0ba902b7",
        "name": "GET /health",
        "kind": 2,
        "startTimeUnixNano": "'$(date +%s000000000)'",
        "endTimeUnixNano": "'$(($(date +%s) + 1))000000000'",
        "status": { "code": 1 }
      }] }]
    }]
  }'`
  }
}

export default function ConnectApp() {
  const { session } = useAuth()
  const isAdmin = session.role === 'ADMIN'
  const apiUrl = publicApiUrl()

  const [stack, setStack] = useState('otel')
  const [serviceName, setServiceName] = useState('minha-aplicacao')
  const [apiKey, setApiKey] = useState('')
  const [creatingKey, setCreatingKey] = useState(false)
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState(null)

  const services = useApi('/telemetry/services')
  const keys = useApi(isAdmin ? '/api-keys' : null)

  // Polling ao vivo a cada 3 segundos para detectar quando o novo serviço começar a enviar sinais.
  useEffect(() => {
    const id = setInterval(() => services.reload(), 3000)
    return () => clearInterval(id)
  }, [services])

  async function generateKey() {
    setCreatingKey(true)
    setTestResult(null)
    try {
      const created = await api.post('/api-keys', { name: `Conexão ${serviceName.trim() || 'App'}` })
      setApiKey(created.key)
      keys.reload()
      setTestResult({ ok: true, text: `Chave criada (${created.prefix}…) e preenchida automaticamente nos exemplos abaixo.` })
    } catch (err) {
      setTestResult({ ok: false, text: err.message })
    } finally {
      setCreatingKey(false)
    }
  }

  async function sendTestTelemetry() {
    const key = apiKey.trim()
    if (!key) {
      setTestResult({ ok: false, text: 'Informe ou gere uma X-API-Key antes de enviar o sinal de teste.' })
      return
    }
    setTesting(true)
    setTestResult(null)
    const nowNs = BigInt(Date.now()) * 1_000_000n
    const endNs = nowNs + 35_000_000n
    const cleanSvc = serviceName.trim() || 'minha-aplicacao'
    const randHex = (len) => Array.from({ length: len }, () => Math.floor(Math.random() * 16).toString(16)).join('')
    const traceId = randHex(32)
    const spanId = randHex(16)

    try {
      const res = await fetch('/api/webhooks/otlp/v1/traces', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-API-Key': key },
        body: JSON.stringify({
          resourceSpans: [{
            resource: { attributes: [{ key: 'service.name', value: { stringValue: cleanSvc } }] },
            scopeSpans: [{
              spans: [{
                traceId,
                spanId,
                name: 'GET /api/teste-conexao',
                kind: 2,
                startTimeUnixNano: nowNs.toString(),
                endTimeUnixNano: endNs.toString(),
                status: { code: 1 },
              }],
            }],
          }],
        }),
      })
      if (!res.ok) throw new Error(`HTTP ${res.status} ao enviar trace (verifique a X-API-Key).`)
      services.reload()
      setTestResult({ ok: true, text: `Span de teste enviado para "${cleanSvc}"! Aguarde a confirmação ao vivo ao lado.` })
    } catch (err) {
      setTestResult({ ok: false, text: err.message })
    } finally {
      setTesting(false)
    }
  }

  const serviceList = services.data ?? []
  const targetConnected = serviceList.find((s) => s.service === serviceName.trim())

  return (
    <>
      <header className="page-header">
        <div>
          <h1>Conectar Aplicação</h1>
          <p className="muted">Envie traces, logs e métricas da sua aplicação para o Argos em 3 passos simples</p>
        </div>
        <Link to="/telemetry" className="btn">Abrir Telemetria</Link>
      </header>

      <PageGuide
        id="connect-app"
        summary="Este assistente guia você na instrumentação da aplicação que será monitorada pelo Argos usando o padrão aberto OpenTelemetry (OTLP/HTTP)."
        steps={[
          'Escolha o tipo/linguagem da aplicação que deseja analisar.',
          'Gere ou informe uma X-API-Key e copie o bloco de configuração pronto para o seu ambiente.',
          'Valide a conexão ao vivo com um envio de teste ou iniciando seu serviço.',
        ]}
      />

      <div className="grid-2">
        <section className="card">
          <h2>Passo 1 — Credencial e Stack</h2>
          <div style={{ display: 'grid', gap: 12 }}>
            <label className="field">
              <span>Nome do serviço monitorado</span>
              <input
                value={serviceName}
                onChange={(e) => setServiceName(e.target.value)}
                placeholder="checkout-api"
              />
            </label>

            <label className="field">
              <span>Chave de API (X-API-Key)</span>
              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                <input
                  value={apiKey}
                  onChange={(e) => setApiKey(e.target.value)}
                  placeholder="argos_..."
                  style={{ flex: '1 1 180px', minWidth: 0 }}
                />
                {isAdmin && (
                  <button
                    type="button"
                    className="btn btn-primary btn-small"
                    disabled={creatingKey}
                    onClick={generateKey}
                  >
                    {creatingKey ? 'Gerando…' : 'Gerar chave'}
                  </button>
                )}
              </div>
              {!isAdmin && (
                <small className="muted">Peça a um Administrador uma chave gerada em Chaves de API.</small>
              )}
            </label>

            <div>
              <span className="filter-label" style={{ display: 'block', marginBottom: 6 }}>Tecnologia da aplicação</span>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                {STACKS.map((s) => (
                  <button
                    key={s.id}
                    type="button"
                    className={`btn btn-small ${stack === s.id ? 'btn-primary' : ''}`}
                    onClick={() => setStack(s.id)}
                  >
                    {s.label}
                  </button>
                ))}
              </div>
            </div>

            <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap', marginTop: 4 }}>
              <button
                type="button"
                className="btn btn-primary"
                disabled={testing}
                onClick={sendTestTelemetry}
              >
                {testing ? 'Enviando sinal…' : 'Testar conexão agora'}
              </button>
              <Link to="/integrations" className="btn">Configurar Slack / Discord / E-mail</Link>
            </div>

            {testResult && (
              <p
                role="status"
                className="small"
                style={{
                  margin: 0,
                  padding: '8px 12px',
                  borderRadius: 6,
                  background: testResult.ok ? 'rgba(22, 163, 74, 0.1)' : 'rgba(220, 38, 38, 0.1)',
                  color: testResult.ok ? 'var(--ok-text)' : 'var(--alert-text)',
                }}
              >
                {testResult.text}
              </p>
            )}
          </div>
        </section>

        <section className="card">
          <h2>Passo 3 — Detector ao vivo (atualiza a cada 3s)</h2>
          {targetConnected ? (
            <div style={{ padding: 14, borderRadius: 8, background: 'var(--ok-soft)', color: 'var(--ok-text)', marginBottom: 14 }}>
              <strong>Conectado:</strong> o serviço <code className="mono">{targetConnected.service}</code> está enviando telemetria ({targetConnected.spanCount} spans, último {timeAgo(targetConnected.lastSeenAt)}).
            </div>
          ) : (
            <p className="muted small" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <span className="spinner" aria-hidden="true" style={{ width: 14, height: 14, borderWidth: 2 }} />
              Aguardando spans do serviço <strong>{serviceName || '—'}</strong>…
            </p>
          )}

          <h3 style={{ fontSize: 14, marginTop: 12 }}>Serviços já recebendo dados ({serviceList.length})</h3>
          {serviceList.length === 0 ? (
            <p className="muted small">Nenhum serviço detectado na última hora.</p>
          ) : (
            <ul className="plain-list" style={{ display: 'grid', gap: 6, maxHeight: 220, overflowY: 'auto' }}>
              {serviceList.map((s) => (
                <li key={s.service} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: 13 }}>
                  <Link to={`/telemetry?service=${encodeURIComponent(s.service)}`}>
                    <strong>{s.service}</strong>
                  </Link>
                  <span className="muted">{s.spanCount} spans · {s.logCount} logs · {timeAgo(s.lastSeenAt)}</span>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>

      <section className="card">
        <h2>Passo 2 — Configuração pronta para copiar</h2>
        <CodeBlock
          title={STACKS.find((s) => s.id === stack)?.label ?? 'Configuração'}
          code={buildSnippet(stack, apiUrl, apiKey)}
        />
      </section>
    </>
  )
}
