import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api, query } from '../api.js'
import { useAsync } from '../hooks/useApi.js'
import { Loading, ErrorState } from '../components/States.jsx'
import { SeverityBadge, StatusBadge, ServiceChips } from '../components/Badges.jsx'
import { DeployList } from '../components/DeployList.jsx'
import IncidentsPerDay from '../components/IncidentsPerDay.jsx'
import { SEVERITIES, STATUS_LABEL, formatDuration, timeAgo } from '../format.js'

const PERIODS = [
  ['24h', 'Últimas 24 h'],
  ['7d', '7 dias'],
  ['30d', '30 dias'],
]
const PERIOD_HOURS = { '24h': 24, '7d': 24 * 7, '30d': 24 * 30 }
const ACTIVE = ['OPEN', 'INVESTIGATING', 'MITIGATED']
const DAY_MS = 86400e3
const DORA_LEVEL_LABEL = { ELITE: 'Elite', HIGH: 'Alto', MEDIUM: 'Médio', LOW: 'Baixo', 'N/A': 'Sem amostra' }

const dayKey = (date) => `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}`

function buildDailySeries(incidents, deploys, chartDays, now) {
  const start = new Date(now)
  start.setHours(0, 0, 0, 0)
  start.setDate(start.getDate() - (chartDays - 1))
  const todayKey = dayKey(new Date(now))
  const days = Array.from({ length: chartDays }, (_, i) => {
    const date = new Date(start)
    date.setDate(date.getDate() + i)
    return {
      key: dayKey(date),
      short: date.toLocaleDateString('pt-BR', { day: '2-digit', month: '2-digit' }),
      long: date.toLocaleDateString('pt-BR', { weekday: 'long', day: 'numeric', month: 'long' }),
      today: dayKey(date) === todayKey,
      count: 0,
      deploys: [],
    }
  })
  const byKey = new Map(days.map((d) => [d.key, d]))
  for (const inc of incidents) {
    const day = byKey.get(dayKey(new Date(inc.openedAt)))
    if (day) day.count += 1
  }
  for (const dep of deploys) {
    const day = byKey.get(dayKey(new Date(dep.deployedAt)))
    if (day) day.deploys.push(dep)
  }
  return days
}

/** Metrics for the period plus what is active right now (a size=1 page per status gives its count). */
async function loadHome(period) {
  const now = Date.now()
  const openedFrom = new Date(now - PERIOD_HOURS[period] * 3600e3).toISOString()
  const chartFrom = new Date(now - 14 * DAY_MS).toISOString()
  const [metrics, dora, recent, periodIncidents, deploys, ...counts] = await Promise.all([
    api.get(`/incidents/metrics${query({ openedFrom, groupBy: 'severity' })}`),
    api.get(`/metrics/dora${query({ from: openedFrom })}`).catch(() => null),
    api.get(`/incidents${query({ status: ACTIVE, size: 8 })}`),
    api.get(`/incidents${query({ openedFrom: chartFrom, size: 100 })}`),
    api.get(`/deploys${query({ size: 50 })}`),
    ...ACTIVE.map((status) => api.get(`/incidents${query({ status, size: 1 })}`)),
  ])
  return {
    now,
    metrics,
    dora,
    periodIncidents: periodIncidents.content,
    deploys: deploys.content,
    active: {
      total: recent.totalElements,
      byStatus: Object.fromEntries(ACTIVE.map((status, i) => [status, counts[i].totalElements])),
      items: recent.content,
    },
  }
}

function Tile({ label, value, hint, tone, badge }) {
  return (
    <section className={`card tile ${tone ? `tile-${tone}` : ''}`}>
      <div className="card-header no-margin">
        <h2>{label}</h2>
        {badge}
      </div>
      <p className="tile-value">{value}</p>
      {hint && <p className="tile-hint">{hint}</p>}
    </section>
  )
}

function DoraBadge({ level }) {
  if (!level) return null
  return <span className={`badge dora-badge dora-${level}`}>{DORA_LEVEL_LABEL[level] ?? level}</span>
}

function DoraSection({ dora }) {
  if (!dora) return null
  const { deploymentFrequency: df, leadTimeForChanges: lt, changeFailureRate: cfr, meanTimeToRestore: mttr } = dora
  return (
    <section className="card dora-section">
      <div className="card-header">
        <div>
          <h2>Métricas DORA (DevOps Research & Assessment)</h2>
          <p className="muted small no-margin">
            Indicadores de velocidade de entrega e estabilidade operacional com correlação automática entre deploys e
            incidentes (janela de {dora.failureWindowMinutes} min)
          </p>
        </div>
      </div>

      <div className="tiles no-margin">
        <Tile
          label="Deployment Frequency"
          value={`${df.deploysPerDay}/dia`}
          hint={`${df.totalDeploys} deploy(s) no período · ${df.deploysPerWeek}/sem`}
          badge={<DoraBadge level={df.level} />}
        />
        <Tile
          label="Lead Time for Changes"
          value={formatDuration(lt.averageSeconds)}
          hint={lt.sampleSize > 0 ? `mediana: ${formatDuration(lt.medianSeconds)} (${lt.sampleSize} deploys)` : 'informe committedAt no deploy'}
          badge={<DoraBadge level={lt.level} />}
        />
        <Tile
          label="Change Failure Rate"
          value={`${cfr.percentage}%`}
          tone={cfr.failedDeploys > 0 ? 'alert' : 'ok'}
          hint={`${cfr.failedDeploys} de ${cfr.totalDeploys} deploy(s) seguidos por incidente`}
          badge={<DoraBadge level={cfr.level} />}
        />
        <Tile
          label="Mean Time to Restore (MTTR)"
          value={formatDuration(mttr.mttrSeconds)}
          hint={`${mttr.resolvedIncidents} resolvido(s) de ${mttr.totalIncidents} · MTTA ${formatDuration(mttr.mttaSeconds)}`}
          badge={<DoraBadge level={mttr.level} />}
        />
      </div>

      {dora.correlatedIncidents?.length > 0 && (
        <div className="dora-correlations">
          <h3>Deploys correlacionados com abertura de incidente</h3>
          <div className="table-scroll">
            <table className="table">
              <thead>
                <tr>
                  <th>Deploy</th>
                  <th>Autor</th>
                  <th>Incidente subsequente</th>
                  <th>Severidade</th>
                  <th>Intervalo após deploy</th>
                </tr>
              </thead>
              <tbody>
                {dora.correlatedIncidents.map((c) => (
                  <tr key={`${c.deployId}-${c.incidentId}`}>
                    <td>
                      <Link to={`/deploys?service=${encodeURIComponent(c.service)}`}>
                        <strong>{c.service}</strong> <code className="mono">{c.version}</code>
                      </Link>
                    </td>
                    <td className="muted">{c.author || 'pipeline'}</td>
                    <td>
                      <Link to={`/incidents/${c.incidentId}`}>{c.incidentTitle}</Link>
                    </td>
                    <td><SeverityBadge severity={c.severity} /></td>
                    <td className="text-danger">{c.minutesAfterDeploy} min após deploy</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
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
  const { data, error, loading, reload } = useAsync(period, loadHome, 5000)

  return (
    <>
      <header className="page-header">
        <div>
          <h1>Visão geral</h1>
          <p className="muted">Saúde operacional, métricas DORA e correlação de deploys</p>
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

          <DoraSection dora={data.dora} />

          <section className="card" style={{ marginBottom: 16 }}>
            <div className="card-header">
              <h2>Incidentes abertos por dia & Marcadores de Deploy (14 dias)</h2>
              <Link to="/deploys">Ver deploys</Link>
            </div>
            <IncidentsPerDay days={buildDailySeries(data.periodIncidents, data.deploys, 14, data.now)} />
          </section>

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

          <section className="card" style={{ marginBottom: 16 }}>
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
              <DeployList deploys={data.deploys.slice(0, 5)} />
            )}
          </section>
        </div>
      )}
    </>
  )
}
