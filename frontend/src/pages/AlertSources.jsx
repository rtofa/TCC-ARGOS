import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api, publicApiUrl } from '../api.js'
import { useApi } from '../hooks/useApi.js'
import { useAuth, canWrite } from '../auth/useAuth.js'
import { Loading, ErrorState } from '../components/States.jsx'
import { SeverityBadge } from '../components/Badges.jsx'
import { CodeBlock, CopyButton } from '../components/Code.jsx'
import AlertSourceDialog from '../components/AlertSourceDialog.jsx'
import { SEVERITIES, formatDateTime } from '../format.js'

const DEFAULT_FORMAT_EXAMPLE = `{
  "title": "CPU alta no checkout-api",
  "dedupKey": "cpu-checkout",
  "status": "firing",
  "severity": "critical",
  "service": "checkout-api",
  "description": "CPU média acima de 90% por 5 minutos"
}`

/** Strips the leading /api that the backend includes in webhookPath. */
const webhookUrl = (path) => `${publicApiUrl()}${path.replace(/^\/api/, '')}`

function OtlpMonitorRulesCard({ editable }) {
  const rules = useApi('/telemetry/rules')
  const services = useApi('/telemetry/services')
  const [service, setService] = useState('*')
  const [windowSeconds, setWindowSeconds] = useState('90')
  const [maxErrorRate, setMaxErrorRate] = useState('2.0')
  const [maxP95Ms, setMaxP95Ms] = useState('800')
  const [minRequests, setMinRequests] = useState('3')
  const [severity, setSeverity] = useState('SEV2')
  const [saving, setSaving] = useState(false)
  const [ruleError, setRuleError] = useState(null)

  async function saveRule(e) {
    e.preventDefault()
    setSaving(true)
    setRuleError(null)
    try {
      await api.post('/telemetry/rules', {
        service: service.trim() || '*',
        windowSeconds: Number(windowSeconds) || 90,
        maxErrorRatePercent: maxErrorRate === '' ? null : Number(maxErrorRate),
        maxP95LatencyMs: maxP95Ms === '' ? null : Number(maxP95Ms),
        minRequests: Number(minRequests) || 3,
        severity,
        enabled: true,
      })
      rules.reload()
    } catch (err) {
      setRuleError(err.message)
    } finally {
      setSaving(false)
    }
  }

  function editRule(r) {
    setService(r.service)
    setWindowSeconds(String(r.windowSeconds))
    setMaxErrorRate(r.maxErrorRatePercent ?? '')
    setMaxP95Ms(r.maxP95LatencyMs ?? '')
    setMinRequests(String(r.minRequests))
    setSeverity(r.severity)
  }

  async function toggleRule(r) {
    setRuleError(null)
    try {
      await api.put(`/telemetry/rules/${r.id}`, {
        service: r.service,
        windowSeconds: r.windowSeconds,
        maxErrorRatePercent: r.maxErrorRatePercent,
        maxP95LatencyMs: r.maxP95LatencyMs,
        minRequests: r.minRequests,
        severity: r.severity,
        enabled: !r.enabled,
      })
      rules.reload()
    } catch (err) {
      setRuleError(err.message)
    }
  }

  async function deleteRule(r) {
    if (!window.confirm(`Remover regra OTLP para "${r.service}"?`)) return
    setRuleError(null)
    try {
      await api.delete(`/telemetry/rules/${r.id}`)
      rules.reload()
    } catch (err) {
      setRuleError(err.message)
    }
  }

  return (
    <section className="card">
      <div className="card-header">
        <div>
          <h2>Regras de Monitoramento OpenTelemetry (SLO & Latência)</h2>
          <p className="muted small no-margin">
            Configure os limites do monitor automático OTLP por serviço para abrir incidentes por taxa de erro (%) ou
            degradação de latência p95 (ms)
          </p>
        </div>
      </div>

      {ruleError && <p className="form-error" role="alert">{ruleError}</p>}

      {editable && (
        <form className="slo-rule-form" onSubmit={saveRule}>
          <div className="form-grid">
            <label>
              Serviço (* = todos)
              <input
                list="otlp-services-list"
                value={service}
                onChange={(e) => setService(e.target.value)}
                placeholder="ex.: checkout ou *"
                required
              />
              <datalist id="otlp-services-list">
                <option value="*" />
                {services.data?.map((s) => (
                  <option key={s.service} value={s.service} />
                ))}
              </datalist>
            </label>
            <label>
              Taxa de erro máx. (%)
              <input
                type="number"
                step="0.1"
                min="0.1"
                max="100"
                value={maxErrorRate}
                onChange={(e) => setMaxErrorRate(e.target.value)}
                placeholder="ex.: 2.0"
              />
            </label>
            <label>
              Latência p95 máx. (ms)
              <input
                type="number"
                step="10"
                min="1"
                value={maxP95Ms}
                onChange={(e) => setMaxP95Ms(e.target.value)}
                placeholder="ex.: 800"
              />
            </label>
            <label>
              Janela (segundos)
              <input
                type="number"
                min="30"
                max="3600"
                value={windowSeconds}
                onChange={(e) => setWindowSeconds(e.target.value)}
                required
              />
            </label>
            <label>
              Amostra mínima (spans)
              <input
                type="number"
                min="1"
                max="1000"
                value={minRequests}
                onChange={(e) => setMinRequests(e.target.value)}
                required
              />
            </label>
            <label>
              Severidade do incidente
              <select value={severity} onChange={(e) => setSeverity(e.target.value)}>
                {SEVERITIES.map((s) => (
                  <option key={s} value={s}>{s}</option>
                ))}
              </select>
            </label>
          </div>
          <div className="dialog-actions">
            <button type="submit" className="btn btn-primary" disabled={saving}>
              {saving ? 'Salvando…' : 'Salvar regra OTLP'}
            </button>
          </div>
        </form>
      )}

      {rules.error && <ErrorState error={rules.error} onRetry={rules.reload} />}
      {!rules.data && !rules.error && <Loading />}
      {rules.data && (
        rules.data.length === 0 ? (
          <p className="muted small">
            Usando regra padrão automática (janela de 90s, mínimo de 3 spans com falha). Crie uma regra acima para
            personalizar o SLO de erro e latência p95 por serviço.
          </p>
        ) : (
          <div className="table-scroll">
            <table className="table">
              <thead>
                <tr>
                  <th>Serviço</th>
                  <th>Taxa de erro máx.</th>
                  <th>Latência p95 máx.</th>
                  <th>Janela / Amostra</th>
                  <th>Severidade</th>
                  <th>Status</th>
                  {editable && <th aria-label="Ações" />}
                </tr>
              </thead>
              <tbody>
                {rules.data.map((r) => (
                  <tr key={r.id}>
                    <td>
                      <code className="mono">{r.service === '*' ? '* (Padrão global)' : r.service}</code>
                    </td>
                    <td>{r.maxErrorRatePercent != null ? `> ${r.maxErrorRatePercent}%` : <span className="muted">—</span>}</td>
                    <td>{r.maxP95LatencyMs != null ? `> ${r.maxP95LatencyMs} ms` : <span className="muted">—</span>}</td>
                    <td className="muted">{r.windowSeconds}s · mín. {r.minRequests} spans</td>
                    <td><SeverityBadge severity={r.severity} /></td>
                    <td>
                      <span className={`badge ${r.enabled ? 'status status-RESOLVED' : ''}`}>
                        {r.enabled ? 'Ativa' : 'Pausada'}
                      </span>
                    </td>
                    {editable && (
                      <td className="actions-cell">
                        <button type="button" className="link-button" onClick={() => editRule(r)}>Editar</button>
                        <button type="button" className="link-button" onClick={() => toggleRule(r)}>
                          {r.enabled ? 'Pausar' : 'Ativar'}
                        </button>
                        <button type="button" className="link-button danger" onClick={() => deleteRule(r)}>Remover</button>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )
      )}
    </section>
  )
}

export default function AlertSources() {
  const { session } = useAuth()
  const editable = canWrite(session)
  const sources = useApi('/alert-sources')
  const [editing, setEditing] = useState(null) // null = closed, {} = new, source = edit
  const [error, setError] = useState(null)

  async function remove(source) {
    if (!window.confirm(`Remover a fonte "${source.name}"? Alertas enviados para ela passarão a ser recusados.`)) return
    setError(null)
    try {
      await api.delete(`/alert-sources/${source.id}`)
      sources.reload()
    } catch (e) {
      setError(e.message)
    }
  }

  return (
    <>
      <header className="page-header">
        <div>
          <h1>Fontes de alerta & SLO</h1>
          <p className="muted">Configure regras de SLO OpenTelemetry e receba alertas de qualquer ferramenta</p>
        </div>
        {editable && (
          <button type="button" className="btn btn-primary" onClick={() => setEditing({})}>
            Nova fonte webhook
          </button>
        )}
      </header>

      <OtlpMonitorRulesCard editable={editable} />

      {error && <p className="form-error" role="alert">{error}</p>}
      {sources.error && <ErrorState error={sources.error} onRetry={sources.reload} />}
      {!sources.data && !sources.error && <Loading />}
      {sources.data && (
        <section className={`card ${sources.loading ? 'is-refreshing' : ''}`}>
          <h2>Fontes de webhook configuradas</h2>
          {sources.data.length === 0 ? (
            <p className="empty">
              Nenhuma fonte configurada. Crie uma para traduzir o formato da sua ferramenta, ou use o formato padrão abaixo.
            </p>
          ) : (
            <div className="table-scroll">
              <table className="table">
                <thead>
                  <tr>
                    <th>Nome</th>
                    <th>Endereço do webhook</th>
                    <th>Severidade padrão</th>
                    <th>Atualizada</th>
                    {editable && <th aria-label="Ações" />}
                  </tr>
                </thead>
                <tbody>
                  {sources.data.map((source) => (
                    <tr key={source.id}>
                      <td>{source.name}</td>
                      <td>
                        <span className="url-cell">
                          <code className="mono">{source.webhookPath}</code>
                          <CopyButton text={webhookUrl(source.webhookPath)} label="Copiar URL" />
                        </span>
                      </td>
                      <td><SeverityBadge severity={source.defaultSeverity} /></td>
                      <td className="nowrap muted">{formatDateTime(source.updatedAt)}</td>
                      {editable && (
                        <td className="actions-cell">
                          <button type="button" className="link-button" onClick={() => setEditing(source)}>Editar</button>
                          <button type="button" className="link-button danger" onClick={() => remove(source)}>Remover</button>
                        </td>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}

      <section className="card">
        <h2>Como enviar alertas</h2>
        <ol className="steps">
          <li>
            Gere uma chave em <Link to="/settings/api-keys">Chaves de API</Link> (somente administradores).
          </li>
          <li>
            Na ferramenta de monitoramento, configure um webhook <strong>POST</strong> para o endereço da fonte com o
            cabeçalho <code className="mono">X-API-Key</code>.
          </li>
          <li>
            Alertas com o mesmo identificador atualizam o mesmo incidente, e um alerta resolvido encerra o incidente.
          </li>
        </ol>
        <div className="grid-2 no-margin">
          <CodeBlock title="Formato padrão do Argos (sem configurar fonte)" code={DEFAULT_FORMAT_EXAMPLE} />
          <CodeBlock
            title="Testar pelo terminal"
            code={`curl -X POST ${publicApiUrl()}/webhooks/alerts \\\n  -H "X-API-Key: <sua-chave>" \\\n  -H "Content-Type: application/json" \\\n  -d '{"title":"CPU alta","dedupKey":"cpu-1","status":"firing","severity":"critical","service":"checkout-api"}'`}
          />
        </div>
      </section>

      {editing && (
        <AlertSourceDialog
          source={editing.id ? editing : null}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null)
            sources.reload()
          }}
        />
      )}
    </>
  )
}
