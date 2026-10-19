import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { query } from '../api.js'
import { useApi } from '../hooks/useApi.js'
import { useAuth, canWrite } from '../auth/useAuth.js'
import { Loading, ErrorState } from '../components/States.jsx'
import { SeverityBadge, StatusBadge, ServiceChips } from '../components/Badges.jsx'
import NewIncidentDialog from '../components/NewIncidentDialog.jsx'
import { SEVERITIES, STATUSES, STATUS_LABEL, formatDuration, timeAgo } from '../format.js'

const PERIOD_HOURS = { '24h': 24, '7d': 24 * 7, '30d': 24 * 30 }
const PAGE_SIZE = 20

const currentMinute = () => Math.floor(Date.now() / 60000) * 60000

function Toggle({ pressed, onClick, children }) {
  return (
    <button type="button" className="toggle" aria-pressed={pressed} onClick={onClick}>
      {children}
    </button>
  )
}

export default function Incidents() {
  const { session } = useAuth()
  const [params, setParams] = useSearchParams()
  const [creating, setCreating] = useState(false)
  // "now" only moves when the user changes a filter, so the request path stays stable between renders.
  const [now, setNow] = useState(currentMinute)

  const statuses = params.getAll('status')
  const severities = params.getAll('severity')
  const period = params.get('period') ?? ''
  const page = Number(params.get('page') ?? 0)

  function update(changes) {
    const next = new URLSearchParams(params)
    for (const [key, value] of Object.entries({ page: undefined, ...changes })) {
      next.delete(key)
      for (const item of [value].flat()) if (item !== undefined && item !== '') next.append(key, item)
    }
    setNow(currentMinute())
    setParams(next, { replace: true })
  }

  const toggle = (key, current, value) =>
    update({ [key]: current.includes(value) ? current.filter((v) => v !== value) : [...current, value] })

  const openedFrom = PERIOD_HOURS[period] ? new Date(now - PERIOD_HOURS[period] * 3600e3).toISOString() : undefined
  const { data, error, loading, reload } = useApi(
    `/incidents${query({ status: statuses, severity: severities, openedFrom, page, size: PAGE_SIZE })}`,
  )

  return (
    <>
      <header className="page-header">
        <div>
          <h1>Incidentes</h1>
          <p className="muted">Acompanhe, investigue e resolva</p>
        </div>
        {canWrite(session) && (
          <button type="button" className="btn btn-primary" onClick={() => setCreating(true)}>
            Novo incidente
          </button>
        )}
      </header>

      <section className="card filters" aria-label="Filtros">
        <div className="filter-group">
          <span className="filter-label">Status</span>
          {STATUSES.map((status) => (
            <Toggle key={status} pressed={statuses.includes(status)} onClick={() => toggle('status', statuses, status)}>
              {STATUS_LABEL[status]}
            </Toggle>
          ))}
        </div>
        <div className="filter-group">
          <span className="filter-label">Severidade</span>
          {SEVERITIES.map((severity) => (
            <Toggle key={severity} pressed={severities.includes(severity)} onClick={() => toggle('severity', severities, severity)}>
              {severity}
            </Toggle>
          ))}
        </div>
        <label className="filter-group">
          <span className="filter-label">Aberto em</span>
          <select value={period} onChange={(e) => update({ status: statuses, severity: severities, period: e.target.value })}>
            <option value="">Qualquer data</option>
            <option value="24h">Últimas 24 h</option>
            <option value="7d">Últimos 7 dias</option>
            <option value="30d">Últimos 30 dias</option>
          </select>
        </label>
      </section>

      {error && <ErrorState error={error} onRetry={reload} />}
      {!data && !error && <Loading />}
      {data && (
        <section className={`card ${loading ? 'is-refreshing' : ''}`}>
          {data.content.length === 0 ? (
            <p className="empty">Nenhum incidente encontrado com esses filtros.</p>
          ) : (
            <div className="table-scroll">
              <table className="table">
                <thead>
                  <tr>
                    <th>Severidade</th>
                    <th>Incidente</th>
                    <th>Status</th>
                    <th>Serviços</th>
                    <th>Origem</th>
                    <th>Aberto</th>
                    <th>Tempo até resolver</th>
                  </tr>
                </thead>
                <tbody>
                  {data.content.map((incident) => (
                    <tr key={incident.id}>
                      <td><SeverityBadge severity={incident.severity} /></td>
                      <td><Link to={`/incidents/${incident.id}`}>{incident.title}</Link></td>
                      <td><StatusBadge status={incident.status} /></td>
                      <td><ServiceChips services={incident.affectedServices} /></td>
                      <td className="muted">{incident.source === 'ALERT' ? 'Alerta' : 'Manual'}</td>
                      <td className="nowrap muted">{timeAgo(incident.openedAt)}</td>
                      <td className="nowrap muted">{formatDuration(incident.mttrSeconds)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <footer className="pagination">
            <span className="muted">
              {data.totalElements} {data.totalElements === 1 ? 'incidente' : 'incidentes'}
            </span>
            <span>
              <button type="button" className="btn" disabled={page <= 0} onClick={() => update({ status: statuses, severity: severities, period, page: page - 1 })}>
                Anterior
              </button>
              <span className="page-indicator">
                {data.totalPages === 0 ? 0 : page + 1} / {data.totalPages}
              </span>
              <button type="button" className="btn" disabled={page + 1 >= data.totalPages} onClick={() => update({ status: statuses, severity: severities, period, page: page + 1 })}>
                Próxima
              </button>
            </span>
          </footer>
        </section>
      )}

      {creating && <NewIncidentDialog onClose={() => setCreating(false)} />}
    </>
  )
}
