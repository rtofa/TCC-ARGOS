import { Link } from 'react-router-dom'
import { useApi } from '../hooks/useApi.js'
import { Loading, ErrorState } from './States.jsx'
import { formatMs } from '../format.js'



/** Spans in tree order (parents first, siblings by start time) with their depth. */
function arrange(spans) {
  const byId = new Map(spans.map((span) => [span.spanId, span]))
  const children = new Map()
  const roots = []
  for (const span of spans) {
    if (span.parentSpanId && byId.has(span.parentSpanId)) {
      if (!children.has(span.parentSpanId)) children.set(span.parentSpanId, [])
      children.get(span.parentSpanId).push(span)
    } else {
      roots.push(span)
    }
  }
  const byStart = (a, b) => Date.parse(a.startTime) - Date.parse(b.startTime)
  const rows = []
  const visit = (span, depth) => {
    rows.push({ span, depth })
    for (const child of (children.get(span.spanId) ?? []).sort(byStart)) visit(child, depth + 1)
  }
  for (const root of roots.sort(byStart)) visit(root, 0)
  return rows
}

/** The classic trace waterfall: one bar per span, placed by start and sized by duration. */
export default function TraceWaterfall({ traceId, onClose }) {
  const { data, error, reload } = useApi(`/telemetry/traces/${traceId}`)
  const logs = useApi(`/telemetry/logs?traceId=${traceId}&limit=50`)

  if (error) return <ErrorState error={error} onRetry={reload} />
  if (!data) return <Loading />

  const total = Math.max(data.durationMs, 0.001)
  const origin = Date.parse(data.startTime)
  const rows = arrange(data.spans)

  return (
    <section className="card" aria-label="Detalhe do trace">
      <div className="card-header">
        <h2>
          Trace <code className="mono">{traceId.slice(0, 12)}…</code>
        </h2>
        <span className="muted small">
          {data.spans.length} spans · {formatMs(data.durationMs)}
          {onClose && (
            <button type="button" className="btn btn-link" onClick={onClose}>
              Fechar
            </button>
          )}
        </span>
      </div>

      <ol className="waterfall">
        {rows.map(({ span, depth }) => {
          const left = ((Date.parse(span.startTime) - origin) / total) * 100
          const width = Math.max((span.durationMs / total) * 100, 0.6)
          return (
            <li key={span.spanId} className={span.error ? 'wf-row wf-error' : 'wf-row'}>
              <div className="wf-label" style={{ paddingLeft: `${depth * 14}px` }}>
                <strong>{span.service}</strong> <span className="muted">{span.name}</span>
                {span.error && <span className="badge wf-badge">erro</span>}
              </div>
              <div className="wf-track" title={span.statusMessage ?? ''}>
                <span className="wf-bar" style={{ left: `${Math.min(left, 99.4)}%`, width: `${Math.min(width, 100 - left)}%` }} />
                <span className="wf-time mono">{formatMs(span.durationMs)}</span>
              </div>
            </li>
          )
        })}
      </ol>

      {logs.data?.length > 0 && (
        <>
          <h3>Logs deste trace</h3>
          <LogList logs={logs.data} compact />
        </>
      )}
    </section>
  )
}

const LEVEL_CLASS = (n) => (n >= 17 ? 'log-error' : n >= 13 ? 'log-warn' : '')

/** Newest first, as returned by the API. */
export function LogList({ logs, compact = false }) {
  if (!logs.length) return <p className="empty">Nenhum log no período.</p>
  return (
    <ul className="log-list">
      {logs.map((log, i) => (
        <li key={`${log.occurredAt}-${i}`} className={LEVEL_CLASS(log.severityNumber)}>
          <time className="mono muted" dateTime={log.occurredAt}>
            {new Date(log.occurredAt).toLocaleTimeString('pt-BR')}
          </time>
          <span className="badge log-level">{log.severityText || 'LOG'}</span>
          {!compact && <strong>{log.service}</strong>}
          <span className="log-body">{log.body}</span>
          {log.traceId && !compact && (
            <Link to={`/telemetry?tab=traces&trace=${log.traceId}`} className="mono small">
              trace
            </Link>
          )}
        </li>
      ))}
    </ul>
  )
}

