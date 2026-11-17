import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api, query } from '../api.js'
import { useAsync } from '../hooks/useApi.js'
import { Loading, ErrorState } from '../components/States.jsx'
import { SeverityBadge } from '../components/Badges.jsx'
import IncidentsPerDay from '../components/IncidentsPerDay.jsx'
import PageGuide from '../components/PageGuide.jsx'
import { SEVERITIES, STATUS_LABEL, formatDuration } from '../format.js'

const PERIODS = [
  ['24h', 'Últimas 24 h'],
  ['7d', '7 dias'],
  ['30d', '30 dias'],
  ['90d', '90 dias'],
]
const PERIOD_HOURS = { '24h': 24, '7d': 24 * 7, '30d': 24 * 30, '90d': 24 * 90 }
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

function buildServiceReliability(incidents, deploys) {
  const byService = new Map()
  const ensure = (svc) => {
    if (!byService.has(svc)) {
      byService.set(svc, { service: svc, total: 0, critical: 0, open: 0, deploys: 0, mttrSum: 0, mttrCount: 0 })
    }
    return byService.get(svc)
  }
  for (const inc of incidents) {
    for (const svc of inc.affectedServices ?? []) {
      const row = ensure(svc)
      row.total += 1
      if (inc.severity === 'SEV1' || inc.severity === 'SEV2') row.critical += 1
      if (inc.status !== 'RESOLVED') row.open += 1
      if (inc.mttrSeconds !== null && inc.mttrSeconds !== undefined) {
        row.mttrSum += inc.mttrSeconds
        row.mttrCount += 1
      }
    }
  }
  for (const dep of deploys) {
    ensure(dep.service).deploys += 1
  }
  return [...byService.values()]
    .map((r) => ({ ...r, avgMttr: r.mttrCount > 0 ? Math.round(r.mttrSum / r.mttrCount) : null }))
    .sort((a, b) => b.total - a.total || b.critical - a.critical || b.deploys - a.deploys)
    .slice(0, 10)
}

async function loadOverview({ period, service }) {
  const now = Date.now()
  const openedFrom = new Date(now - PERIOD_HOURS[period] * 3600e3).toISOString()
  const chartFrom = new Date(now - 14 * DAY_MS).toISOString()
  const cleanService = service.trim() || undefined

  const [metrics, dora, periodList, chartIncidents, deploys, ...counts] = await Promise.all([
    api.get(`/incidents/metrics${query({ openedFrom, groupBy: 'severity' })}`),
    api.get(`/metrics/dora${query({ from: openedFrom, service: cleanService })}`).catch(() => null),
    api.get(`/incidents${query({ openedFrom, size: 100 })}`),
    api.get(`/incidents${query({ openedFrom: chartFrom, size: 100 })}`),
    api.get(`/deploys${query({ from: openedFrom, service: cleanService, size: 100 })}`),
    ...ACTIVE.map((status) => api.get(`/incidents${query({ status, size: 1 })}`)),
  ])

  const filterBySvc = (items) =>
    cleanService ? items.filter((i) => i.affectedServices?.includes(cleanService)) : items

  return {
    now,
    metrics,
    dora,
    periodIncidents: filterBySvc(periodList.content),
    chartIncidents: filterBySvc(chartIncidents.content),
    deploys: deploys.content,
    active: {
      total: counts.reduce((acc, c) => acc + c.totalElements, 0),
      byStatus: Object.fromEntries(ACTIVE.map((status, i) => [status, counts[i].totalElements])),
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
            Velocidade de entrega e estabilidade com correlação automática entre deploys e incidentes (janela de {dora.failureWindowMinutes} min)
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
  const byRow = Object.fromEntries((bySeverity ?? []).map((row) => [row.severity, row]))
  const max = Math.max(1, ...SEVERITIES.map((s) => byRow[s]?.count ?? 0))
  return (
    <ul className="bars">
      {SEVERITIES.map((severity) => {
        const row = byRow[severity]
        const count = row?.count ?? 0
        return (
          <li key={severity} title={row ? `MTTA: ${formatDuration(row.mttaSeconds)} · MTTR: ${formatDuration(row.mttrSeconds)}` : undefined}>
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
  const [period, setPeriod] = useState('30d')
  const [serviceInput, setServiceInput] = useState('')
  const [serviceFilter, setServiceFilter] = useState('')

  const { data, error, loading, reload } = useAsync(
    `${period}:${serviceFilter}`,
    () => loadOverview({ period, service: serviceFilter }),
    15000,
  )

  const reliability = data ? buildServiceReliability(data.periodIncidents, data.deploys) : []
  const resolvedCount = data ? data.periodIncidents.filter((i) => i.status === 'RESOLVED').length : 0
  const resolutionRate = data && data.periodIncidents.length > 0
    ? Math.round((resolvedCount * 100) / data.periodIncidents.length)
    : null

  return (
    <>
      <header className="page-header">
        <div>
          <h1>Visão geral · Tendências SRE & DORA</h1>
          <p className="muted">Evolução histórica de confiabilidade, entrega contínua e serviços mais acionados</p>
        </div>
        <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'center' }}>
          <form
            className="filter-group"
            onSubmit={(e) => {
              e.preventDefault()
              setServiceFilter(serviceInput.trim())
            }}
          >
            <input
              value={serviceInput}
              onChange={(e) => setServiceInput(e.target.value)}
              placeholder="Filtrar serviço (ex: checkout)"
              className="filter-input"
            />
            <button type="submit" className="btn btn-small">Aplicar</button>
            {serviceFilter && (
              <button
                type="button"
                className="link-button small"
                onClick={() => {
                  setServiceInput('')
                  setServiceFilter('')
                }}
              >
                Limpar
              </button>
            )}
          </form>

          <div className="segmented" role="group" aria-label="Período">
            {PERIODS.map(([value, label]) => (
              <button key={value} type="button" aria-pressed={period === value} onClick={() => setPeriod(value)}>
                {label}
              </button>
            ))}
          </div>
        </div>
      </header>

      <PageGuide
        id="overview-sre"
        summary="Ao contrário do Início (que monitora o estado ao vivo agora), a Visão Geral analisa o histórico do período para engenharia e SRE."
        steps={[
          'Compare as 4 métricas DORA oficiais (frequência, lead time, taxa de falha de mudança e MTTR).',
          'Identifique padrões entre publicações (◆) e picos de abertura de incidentes no gráfico de 14 dias.',
          'Use o ranking de confiabilidade por serviço para priorizar melhorias arquiteturais.',
        ]}
      />

      {error && <ErrorState error={error} onRetry={reload} />}
      {!data && !error && <Loading />}
      {data && (
        <div className={loading ? 'is-refreshing' : undefined}>
          <div className="tiles">
            <Tile label="Abertos no período" value={data.metrics.overall.count} hint={PERIODS.find(([v]) => v === period)[1]} />
            <Tile
              label="Taxa de resolução"
              value={resolutionRate === null ? '—' : `${resolutionRate}%`}
              tone={resolutionRate !== null && resolutionRate >= 80 ? 'ok' : undefined}
              hint={`${resolvedCount} de ${data.periodIncidents.length} incidente(s) resolvidos`}
            />
            <Tile label="MTTA médio" value={formatDuration(data.metrics.overall.mttaSeconds)} hint="tempo até reconhecer" />
            <Tile label="MTTR médio" value={formatDuration(data.metrics.overall.mttrSeconds)} hint="tempo até restaurar" />
          </div>

          <DoraSection dora={data.dora} />

          <section className="card" style={{ marginBottom: 16 }}>
            <div className="card-header">
              <h2>Incidentes abertos por dia & Marcadores de Deploy (14 dias)</h2>
              <Link to="/deploys">Ver histórico de deploys</Link>
            </div>
            <IncidentsPerDay days={buildDailySeries(data.chartIncidents, data.deploys, 14, data.now)} />
          </section>

          <div className="grid-2">
            <section className="card">
              <h2>Distribuição por severidade</h2>
              <SeverityBars bySeverity={data.metrics.bySeverity} />
            </section>
            <section className="card">
              <h2>Incidentes ativos por etapa</h2>
              <StatusStrip byStatus={data.active.byStatus} total={data.active.total} />
            </section>
          </div>

          <section className="card">
            <div className="card-header">
              <h2>Confiabilidade por serviço no período</h2>
              <Link to="/telemetry?tab=red">Analisar métricas RED</Link>
            </div>
            {reliability.length === 0 ? (
              <p className="empty">Sem incidentes ou deploys registrados para serviços neste período.</p>
            ) : (
              <div className="table-scroll">
                <table className="table">
                  <thead>
                    <tr>
                      <th>Serviço</th>
                      <th className="numeric">Incidentes</th>
                      <th className="numeric">Críticos (SEV1/2)</th>
                      <th className="numeric">Ativos agora</th>
                      <th className="numeric">MTTR médio</th>
                      <th className="numeric">Deploys</th>
                    </tr>
                  </thead>
                  <tbody>
                    {reliability.map((row) => (
                      <tr key={row.service}>
                        <td>
                          <Link to={`/telemetry?tab=red&service=${encodeURIComponent(row.service)}`}>
                            <strong>{row.service}</strong>
                          </Link>
                        </td>
                        <td className="numeric">{row.total}</td>
                        <td className="numeric">{row.critical}</td>
                        <td className="numeric">{row.open}</td>
                        <td className="numeric">{formatDuration(row.avgMttr)}</td>
                        <td className="numeric">{row.deploys}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        </div>
      )}
    </>
  )
}
