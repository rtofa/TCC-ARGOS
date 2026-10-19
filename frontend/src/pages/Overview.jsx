import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api, query } from '../api.js'
import { useAsync } from '../hooks/useApi.js'
import { Loading, ErrorState } from '../components/States.jsx'
import { SeverityBadge, StatusBadge, ServiceChips } from '../components/Badges.jsx'
import { DeployList } from '../components/DeployList.jsx'
import { SEVERITIES, STATUS_LABEL, formatDuration, timeAgo } from '../format.js'

const PERIODS = [
  ['24h', 'Últimas 24 h'],
  ['7d', '7 dias'],
  ['30d', '30 dias'],
]
const PERIOD_HOURS = { '24h': 24, '7d': 24 * 7, '30d': 24 * 30 }
const ACTIVE = ['OPEN', 'INVESTIGATING', 'MITIGATED']

/** Metrics for the period plus what is active right now (a size=1 page per status gives its count). */
async function loadHome(period) {
  const openedFrom = new Date(Date.now() - PERIOD_HOURS[period] * 3600e3).toISOString()
  const [metrics, recent, deploys, ...counts] = await Promise.all([
    api.get(`/incidents/metrics${query({ openedFrom, groupBy: 'severity' })}`),
    api.get(`/incidents${query({ status: ACTIVE, size: 8 })}`),
    api.get(`/deploys${query({ size: 5 })}`),
    ...ACTIVE.map((status) => api.get(`/incidents${query({ status, size: 1 })}`)),
  ])
  return {
    metrics,
    deploys: deploys.content,
    active: {
      total: recent.totalElements,
      byStatus: Object.fromEntries(ACTIVE.map((status, i) => [status, counts[i].totalElements])),
      items: recent.content,
    },
  }
}

function Tile({ label, value, hint, tone }) {
  return (
    <section className={`card tile ${tone ? `tile-${tone}` : ''}`}>
      <h2>{label}</h2>
      <p className="tile-value">{value}</p>
      {hint && <p className="tile-hint">{hint}</p>}
    </section>
  )
}

function SeverityBars({ bySeverity }) {
  const counts = Object.fromEntries((bySeverity ?? []).map((row) => [row.severity, row.count]))
  const max = Math.max(1, ...SEVERITIES.map((s) => counts[s] ?? 0))
  return (
    <ul className="bars">
      {SEVERITIES.map((severity) => {
        const count = counts[severity] ?? 0
        return (
          <li key={severity}>
            <SeverityBadge severity={severity} />
            <span className="bar-track">
              <span className={`bar-fill bar-${severity}`} style={{ width: `${(count / max) * 100}%` }} />
            </span>
            <strong>{count}</strong>
          </li>
        )
      })}
    </ul>
  )
}

function StatusStrip({ byStatus, total }) {
  return (
    <>
      <div className="strip" role="img" aria-label="Distribuição dos incidentes ativos por status">
        {ACTIVE.map(
          (status) =>
            byStatus[status] > 0 && (
              <span key={status} className={`strip-part strip-${status}`} style={{ flexGrow: byStatus[status] }} />
            ),
        )}
        {total === 0 && <span className="strip-part strip-empty" style={{ flexGrow: 1 }} />}
      </div>
      <ul className="legend">
        {ACTIVE.map((status) => (
          <li key={status}>
            <i className={`dot dot-${status}`} aria-hidden="true" />
            {STATUS_LABEL[status]}
            <strong>{byStatus[status]}</strong>
          </li>
        ))}
      </ul>
    </>
  )
}

export default function Overview() {
  const [period, setPeriod] = useState('24h')
  const { data, error, loading, reload } = useAsync(period, loadHome)

  return (
    <>
      <header className="page-header">
        <div>
          <h1>Visão geral</h1>
          <p className="muted">Como está a saúde dos seus serviços agora</p>
        </div>
        <div className="segmented" role="group" aria-label="Período">
          {PERIODS.map(([value, label]) => (
            <button key={value} type="button" aria-pressed={period === value} onClick={() => setPeriod(value)}>
              {label}
            </button>
          ))}
        </div>
      </header>

      {error && <ErrorState error={error} onRetry={reload} />}
      {!data && !error && <Loading />}
      {data && (
        <div className={loading ? 'is-refreshing' : undefined}>
          <div className={`banner ${data.active.total === 0 ? 'banner-ok' : 'banner-alert'}`}>
            {data.active.total === 0
              ? 'Tudo operacional — nenhum incidente ativo.'
              : `${data.active.total} ${data.active.total === 1 ? 'incidente ativo' : 'incidentes ativos'} exigem atenção.`}
          </div>

          <div className="tiles">
            <Tile label="Incidentes ativos" value={data.active.total} tone={data.active.total ? 'alert' : 'ok'} hint="aberto, investigando ou mitigado" />
            <Tile label="Abertos no período" value={data.metrics.overall.count} hint={PERIODS.find(([v]) => v === period)[1]} />
            <Tile label="MTTA" value={formatDuration(data.metrics.overall.mttaSeconds)} hint="tempo médio até reconhecer" />
            <Tile label="MTTR" value={formatDuration(data.metrics.overall.mttrSeconds)} hint="tempo médio até resolver" />
          </div>

          <div className="grid-2">
            <section className="card">
              <h2>Incidentes por severidade</h2>
              <SeverityBars bySeverity={data.metrics.bySeverity} />
            </section>
            <section className="card">
              <h2>Incidentes ativos por status</h2>
              <StatusStrip byStatus={data.active.byStatus} total={data.active.total} />
            </section>
          </div>

          <section className="card">
            <div className="card-header">
              <h2>Incidentes ativos recentes</h2>
              <Link to="/incidents">Ver todos</Link>
            </div>
            {data.active.items.length === 0 ? (
              <p className="empty">Nenhum incidente ativo no momento.</p>
            ) : (
              <table className="table">
                <thead>
                  <tr>
                    <th>Severidade</th>
                    <th>Incidente</th>
                    <th>Status</th>
                    <th>Serviços</th>
                    <th>Aberto</th>
                  </tr>
                </thead>
                <tbody>
                  {data.active.items.map((incident) => (
                    <tr key={incident.id}>
                      <td><SeverityBadge severity={incident.severity} /></td>
                      <td><Link to={`/incidents/${incident.id}`}>{incident.title}</Link></td>
                      <td><StatusBadge status={incident.status} /></td>
                      <td><ServiceChips services={incident.affectedServices} /></td>
                      <td className="nowrap muted">{timeAgo(incident.openedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>

          <section className="card">
            <div className="card-header">
              <h2>Últimos deploys</h2>
              <Link to="/deploys">Ver todos</Link>
            </div>
            {data.deploys.length === 0 ? (
              <p className="empty">
                Nenhum deploy registrado. <Link to="/deploys">Conecte seu pipeline</Link> para saber o que mudou antes de cada incidente.
              </p>
            ) : (
              <DeployList deploys={data.deploys} />
            )}
          </section>
        </div>
      )}
    </>
  )
}
