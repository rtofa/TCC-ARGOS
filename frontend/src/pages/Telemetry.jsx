import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { query } from '../api.js'
import { useApi } from '../hooks/useApi.js'
import { Loading, ErrorState } from '../components/States.jsx'
import TraceWaterfall, { LogList } from '../components/TraceWaterfall.jsx'
import ServiceTopologyGraph from '../components/ServiceTopologyGraph.jsx'
import RedCharts from '../components/RedCharts.jsx'
import { formatMs, timeAgo } from '../format.js'

const PERIODS = { '15m': 15, '1h': 60, '6h': 360, '24h': 1440 }
const TABS = {
  services: 'Serviços & Topologia',
  red: 'Métricas RED',
  traces: 'Traces',
  logs: 'Logs',
}
const LEVELS = ['', 'INFO', 'WARN', 'ERROR']

const currentMinute = () => Math.floor(Date.now() / 60000) * 60000

function ServicesTab({ window, onPickTraces, onPickRed }) {
  const { data, error, reload } = useApi(`/telemetry/services${query(window)}`, 5000)
  const topology = useApi(`/telemetry/topology${query(window)}`, 5000)

  if (error) return <ErrorState error={error} onRetry={reload} />
  if (!data) return <Loading />
  if (!data.length) return <EmptyTelemetry />
  return (
    <>
      <section className="card">
        <div className="card-header">
          <div>
            <h2>Grafo automático de dependência entre microsserviços (Service Topology Map)</h2>
            <p className="muted small no-margin">
              Desenhado automaticamente a partir de parent_span_id → span_id nos traces OTLP; clique em um nó para abrir
              as Métricas RED do serviço
            </p>
          </div>
        </div>
        {topology.data ? (
          <ServiceTopologyGraph topology={topology.data} onSelectService={onPickRed} />
        ) : (
          <Loading />
        )}
      </section>

      <div className="table-scroll card">
        <table className="table">
          <thead>
            <tr>
              <th>Serviço</th>
              <th>Spans</th>
              <th>Spans com erro</th>
              <th>Logs</th>
              <th>Logs de erro</th>
              <th>Último sinal</th>
              <th>Ações</th>
            </tr>
          </thead>
          <tbody>
            {data.map((s) => (
              <tr key={s.service}>
                <td>
                  <button type="button" className="btn btn-link" onClick={() => onPickTraces(s.service)}>
                    {s.service}
                  </button>
                </td>
                <td>{s.spans}</td>
                <td className={s.errorSpans ? 'text-danger' : ''}>{s.errorSpans}</td>
                <td>{s.logs}</td>
                <td className={s.errorLogs ? 'text-danger' : ''}>{s.errorLogs}</td>
                <td className="muted">{timeAgo(s.lastSeen)}</td>
                <td className="nowrap">
                  <button type="button" className="link-button" onClick={() => onPickRed(s.service)}>
                    Métricas RED
                  </button>
                  {' · '}
                  <button type="button" className="link-button" onClick={() => onPickTraces(s.service)}>
                    Traces
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  )
}

function RedTab({ window, service }) {
  const red = useApi(`/telemetry/red${query({ ...window, service })}`, 5000)
  const deploys = useApi(`/deploys${query({ from: window.from, service: service || undefined, size: 50 })}`, 5000)
  const incidents = useApi(`/incidents${query({ openedFrom: window.from, size: 50 })}`, 5000)

  if (red.error) return <ErrorState error={red.error} onRetry={red.reload} />
  if (!red.data) return <Loading />
  return (
    <RedCharts
      red={red.data}
      deploys={deploys.data?.content ?? []}
      incidents={incidents.data?.content ?? []}
      service={service}
    />
  )
}

function TracesTab({ window, service, onlyErrors, selected, onSelect }) {
  const { data, error, reload } = useApi(
    `/telemetry/traces${query({ ...window, service, status: onlyErrors ? 'ERROR' : undefined, limit: 50 })}`,
    5000,
  )
  return (
    <>
      {selected && <TraceWaterfall traceId={selected} onClose={() => onSelect(null)} />}
      {error ? (
        <ErrorState error={error} onRetry={reload} />
      ) : !data ? (
        <Loading />
      ) : !data.length ? (
        <EmptyTelemetry />
      ) : (
        <div className="table-scroll card">
          <table className="table">
            <thead>
              <tr>
                <th>Requisição</th>
                <th>Serviços</th>
                <th>Spans</th>
                <th>Duração</th>
                <th>Quando</th>
              </tr>
            </thead>
            <tbody>
              {data.map((t) => (
                <tr key={t.traceId} className={t.traceId === selected ? 'row-selected' : ''}>
                  <td>
                    <button type="button" className="btn btn-link" onClick={() => onSelect(t.traceId)}>
                      {t.rootName}
                    </button>{' '}
                    {t.errorCount > 0 && <span className="badge wf-badge">{t.errorCount} erro(s)</span>}
                    <div className="muted small mono">{t.traceId.slice(0, 12)}…</div>
                  </td>
                  <td className="small">{t.services.join(', ')}</td>
                  <td>{t.spanCount}</td>
                  <td>{formatMs(t.durationMs)}</td>
                  <td className="muted">{timeAgo(t.startTime)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}

function LogsTab({ window, service, level, text }) {
  const { data, error, reload } = useApi(`/telemetry/logs${query({ ...window, service, level, q: text, limit: 200 })}`, 5000)
  if (error) return <ErrorState error={error} onRetry={reload} />
  if (!data) return <Loading />
  return (
    <section className="card">
      <LogList logs={data} />
    </section>
  )
}

function EmptyTelemetry() {
  return (
    <section className="card">
      <p className="empty">
        Nenhuma telemetria no período. Configure o OpenTelemetry Collector para enviar dados com uma{' '}
        <Link to="/settings/api-keys">chave de API</Link>.
      </p>
    </section>
  )
}

export default function Telemetry() {
  const [params, setParams] = useSearchParams()
  const [now, setNow] = useState(currentMinute)
  const [textInput, setTextInput] = useState(params.get('q') ?? '')

  const tab = TABS[params.get('tab')] ? params.get('tab') : 'services'
  const period = PERIODS[params.get('period')] ? params.get('period') : '1h'
  const service = params.get('service') ?? ''
  const level = params.get('level') ?? ''
  const text = params.get('q') ?? ''
  const onlyErrors = params.get('errors') === '1'
  const trace = params.get('trace')

  function update(changes) {
    const next = new URLSearchParams(params)
    for (const [key, value] of Object.entries(changes)) {
      if (value === undefined || value === null || value === '') next.delete(key)
      else next.set(key, value)
    }
    setNow(currentMinute())
    setParams(next, { replace: true })
  }

  const window = { from: new Date(now - PERIODS[period] * 60000).toISOString() }
  const services = useApi(`/telemetry/services${query(window)}`)

  return (
    <>
      <header className="page-header">
        <div>
          <h1>Telemetria</h1>
          <p className="muted">Topologia de microsserviços, métricas RED, traces e logs via OpenTelemetry</p>
        </div>
        <button type="button" className="btn" onClick={() => update({})}>
          Atualizar
        </button>
      </header>

      <nav className="tabs" aria-label="Sinais">
        {Object.entries(TABS).map(([key, label]) => (
          <button key={key} type="button" className="toggle" aria-pressed={tab === key} onClick={() => update({ tab: key })}>
            {label}
          </button>
        ))}
      </nav>

      <section className="card filters" aria-label="Filtros">
        <label>
          Período
          <select value={period} onChange={(e) => update({ period: e.target.value })}>
            {Object.keys(PERIODS).map((p) => (
              <option key={p} value={p}>
                Últimos {p}
              </option>
            ))}
          </select>
        </label>
        <label>
          Serviço
          <select value={service} onChange={(e) => update({ service: e.target.value })}>
            <option value="">Todos</option>
            {services.data?.map((s) => (
              <option key={s.service} value={s.service}>
                {s.service}
              </option>
            ))}
          </select>
        </label>
        {tab === 'traces' && (
          <label className="inline">
            <input type="checkbox" checked={onlyErrors} onChange={(e) => update({ errors: e.target.checked ? '1' : '' })} />
            Somente com erro
          </label>
        )}
        {tab === 'logs' && (
          <>
            <label>
              Nível mínimo
              <select value={level} onChange={(e) => update({ level: e.target.value })}>
                {LEVELS.map((l) => (
                  <option key={l} value={l}>
                    {l || 'Todos'}
                  </option>
                ))}
              </select>
            </label>
            <form
              onSubmit={(e) => {
                e.preventDefault()
                update({ q: textInput.trim() })
              }}
            >
              <input
                type="search"
                placeholder="Buscar no texto do log"
                value={textInput}
                onChange={(e) => setTextInput(e.target.value)}
                aria-label="Buscar no texto do log"
              />
            </form>
          </>
        )}
      </section>

      {tab === 'services' && (
        <ServicesTab
          window={window}
          onPickTraces={(s) => update({ tab: 'traces', service: s })}
          onPickRed={(s) => update({ tab: 'red', service: s })}
        />
      )}
      {tab === 'red' && <RedTab window={window} service={service} />}
      {tab === 'traces' && (
        <TracesTab
          window={window}
          service={service}
          onlyErrors={onlyErrors}
          selected={trace}
          onSelect={(id) => update({ trace: id })}
        />
      )}
      {tab === 'logs' && <LogsTab window={window} service={service} level={level} text={text} />}
    </>
  )
}
