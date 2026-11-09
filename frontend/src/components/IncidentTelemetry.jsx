import { Link } from 'react-router-dom'
import { api, query } from '../api.js'
import { useAsync } from '../hooks/useApi.js'
import { Loading, ErrorState } from './States.jsx'
import { LogList } from './TraceWaterfall.jsx'
import { formatMs, timeAgo } from '../format.js'

/** Telemetry is read from this long before the incident opened, to catch the lead-up. */
const LOOKBACK_MINUTES = 30
const AFTER_RESOLUTION_MINUTES = 5

/** key = JSON {services, from, to}; module-level so useAsync gets a stable loader. */
function loadIncidentTelemetry(key) {
  const { services, from, to } = JSON.parse(key)
  return Promise.all(
    services.map(async (service) => {
      const [traces, logs] = await Promise.all([
        api.get(`/telemetry/traces${query({ service, status: 'ERROR', from, to, limit: 10 })}`),
        api.get(`/telemetry/logs${query({ service, level: 'ERROR', from, to, limit: 20 })}`),
      ])
      return { traces, logs }
    }),
  ).then((parts) => ({
    traces: dedupe(parts.flatMap((p) => p.traces), (t) => t.traceId).sort((a, b) => Date.parse(b.startTime) - Date.parse(a.startTime)),
    logs: parts.flatMap((p) => p.logs).sort((a, b) => Date.parse(b.occurredAt) - Date.parse(a.occurredAt)).slice(0, 20),
  }))
}

const dedupe = (items, keyOf) => [...new Map(items.map((item) => [keyOf(item), item])).values()]

/** Error traces and error logs of the affected services around the incident: the "what happened inside" card. */
export default function IncidentTelemetry({ incident }) {
  const services = incident.affectedServices
  const opened = Date.parse(incident.openedAt)
  const key =
    services.length === 0
      ? null
      : JSON.stringify({
          services,
          from: new Date(opened - LOOKBACK_MINUTES * 60000).toISOString(),
          to: incident.resolvedAt ? new Date(Date.parse(incident.resolvedAt) + AFTER_RESOLUTION_MINUTES * 60000).toISOString() : undefined,
        })
  const { data, error, reload } = useAsync(key, loadIncidentTelemetry)

  return (
    <section className="card">
      <div className="card-header">
        <h2>Telemetria do incidente</h2>
        <Link to={`/telemetry?tab=traces&errors=1${services[0] ? `&service=${encodeURIComponent(services[0])}` : ''}`}>
          Abrir telemetria
        </Link>
      </div>
      {services.length === 0 ? (
        <p className="muted small">Informe os serviços afetados para ver traces e logs com erro.</p>
      ) : error ? (
        <ErrorState error={error} onRetry={reload} />
      ) : !data ? (
        <Loading />
      ) : (
        <>
          <h3>Traces com erro</h3>
          {data.traces.length === 0 ? (
            <p className="muted small">Nenhum trace com erro de {services.join(', ')} na janela do incidente.</p>
          ) : (
            <ul className="trace-list">
              {data.traces.map((t) => (
                <li key={t.traceId}>
                  <Link to={`/telemetry?tab=traces&trace=${t.traceId}&service=${encodeURIComponent(t.rootService)}`}>
                    {t.rootName}
                  </Link>
                  <span className="muted small">
                    {' '}
                    {t.rootService} · {formatMs(t.durationMs)} · {t.errorCount} erro(s) · {timeAgo(t.startTime)}
                  </span>
                </li>
              ))}
            </ul>
          )}
          <h3>Logs de erro</h3>
          <LogList logs={data.logs} />
        </>
      )}
    </section>
  )
}

