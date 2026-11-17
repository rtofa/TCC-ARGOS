import { useState } from 'react'
import { Link } from 'react-router-dom'
import { query } from '../api.js'
import { useApi } from '../hooks/useApi.js'
import { Loading, ErrorState } from '../components/States.jsx'
import { ColumnChart, RankBars, StatTile } from '../components/Charts.jsx'
import { SeverityBadge } from '../components/Badges.jsx'
import { formatDateTime, formatDuration, formatMoney, formatNumber } from '../format.js'

const TABS = [
  { id: 'executive', label: 'Executivo' },
  { id: 'operations', label: 'Operação' },
  { id: 'delivery', label: 'Entrega (DORA)' },
  { id: 'services', label: 'Serviços' },
]

const DAYS_OPTIONS = [7, 14, 30, 90]

function ExecutiveTab({ days }) {
  const exec = useApi(`/business/executive${query({ days })}`)
  if (exec.error) return <ErrorState error={exec.error} onRetry={exec.reload} />
  if (!exec.data) return <Loading />
  const d = exec.data

  const chartItems = (d.lossByDay ?? []).map((item) => {
    const dt = new Date(`${item.day}T12:00:00`)
    return {
      key: item.day,
      short: dt.toLocaleDateString('pt-BR', { day: '2-digit', month: '2-digit' }),
      long: dt.toLocaleDateString('pt-BR', { weekday: 'short', day: '2-digit', month: 'short' }),
      value: Number(item.value ?? 0),
      detail: `${item.incidents} incidente(s)`,
    }
  })

  return (
    <>
      {!d.kpisConfigured && (
        <section className="card">
          <p className="muted">
            Nenhum KPI de negócio vinculado a serviços ainda. Configure os KPIs em{' '}
            <Link to="/settings/business">KPIs de negócio</Link> para estimar o impacto financeiro dos incidentes.
          </p>
        </section>
      )}

      <div className="stat-grid">
        <StatTile label={`Impacto estimado (${days}d)`} value={formatMoney(d.totalLoss)} tone="primary"
                  hint={`${d.incidentsWithImpact} de ${d.incidents} incidente(s) com impacto`} />
        <StatTile label="Incidentes críticos" value={formatNumber(d.criticalIncidents)}
                  hint={`${formatNumber(d.impactedMinutes)} min acumulados`} />
        <StatTile label="MTTR médio" value={formatDuration(d.mttrSeconds)} hint="No período" />
        <StatTile label="Total de incidentes" value={formatNumber(d.incidents)} hint={`Últimos ${days} dias`} />
      </div>

      <section className="card">
        <h2>Impacto financeiro diário</h2>
        <ColumnChart
          items={chartItems}
          format={(v) => formatMoney(v)}
          formatTick={(v) => formatMoney(v, { compact: true })}
          valueLabel="perda estimada"
          label="Impacto diário de incidentes"
        />
      </section>

      <div className="grid-2">
        <section className="card">
          <h2>Impacto por serviço</h2>
          <RankBars
            items={(d.lossByService ?? []).map((s) => ({
              key: s.service,
              label: s.service,
              value: Number(s.value ?? 0),
              detail: `${s.incidents} incidente(s)`,
            }))}
            format={(v) => formatMoney(v)}
            label="Serviços com maior impacto"
          />
        </section>

        <section className="card">
          <h2>KPIs afetados</h2>
          <RankBars
            items={(d.lossByKpi ?? []).map((k) => ({
              key: k.kpi,
              label: k.kpi,
              value: Number(k.lostValue ?? 0),
              display: `${formatNumber(k.lostUnits)} ${k.unit} · ${formatMoney(k.lostValue)}`,
            }))}
            format={(v) => formatMoney(v)}
            label="KPIs mais impactados"
          />
        </section>
      </div>

      {d.topIncidents?.length > 0 && (
        <section className="card">
          <h2>Maiores incidentes do período</h2>
          <div className="table-scroll">
            <table className="table">
              <thead>
                <tr>
                  <th>Incidente</th>
                  <th>Severidade</th>
                  <th>Abertura</th>
                  <th className="numeric">Duração</th>
                  <th className="numeric">Impacto</th>
                </tr>
              </thead>
              <tbody>
                {d.topIncidents.map((inc) => (
                  <tr key={inc.incidentId}>
                    <td><Link to={`/incidents/${inc.incidentId}`}>{inc.title}</Link></td>
                    <td><SeverityBadge severity={inc.severity} /></td>
                    <td className="nowrap muted">{formatDateTime(inc.openedAt)}</td>
                    <td className="numeric">{inc.minutes} min{inc.ongoing ? ' (ativo)' : ''}</td>
                    <td className="numeric"><strong>{formatMoney(inc.value)}</strong></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </>
  )
}

function OperationsTab({ days, now }) {
  const from = new Date(now - days * 86400e3).toISOString()
  const metrics = useApi(`/incidents/metrics${query({ openedFrom: from, groupBy: 'severity' })}`)
  if (metrics.error) return <ErrorState error={metrics.error} onRetry={metrics.reload} />
  if (!metrics.data) return <Loading />

  const overall = metrics.data.overall ?? {}
  const bySeverity = metrics.data.bySeverity ?? []

  return (
    <>
      <div className="stat-grid">
        <StatTile label="Total de incidentes" value={formatNumber(overall.total)} tone="primary" />
        <StatTile label="Abertos agora" value={formatNumber(overall.open)} />
        <StatTile label="MTTA médio" value={formatDuration(overall.mttaSeconds)} />
        <StatTile label="MTTR médio" value={formatDuration(overall.mttrSeconds)} />
      </div>

      <section className="card">
        <h2>Distribuição por severidade</h2>
        <RankBars
          items={bySeverity.map((b) => ({
            key: b.severity,
            label: b.severity,
            value: b.total,
            display: `${b.total} (${b.open} ativos · MTTR ${formatDuration(b.mttrSeconds)})`,
          }))}
          format={(v) => String(v)}
          label="Incidentes por severidade"
        />
      </section>
    </>
  )
}

function DeliveryTab({ days, now }) {
  const from = new Date(now - days * 86400e3).toISOString()
  const dora = useApi(`/metrics/dora${query({ from })}`)
  if (dora.error) return <ErrorState error={dora.error} onRetry={dora.reload} />
  if (!dora.data) return <Loading />

  const d = dora.data
  return (
    <>
      <div className="stat-grid">
        <StatTile label="Frequência de deploy" value={`${formatNumber(d.deploymentFrequency?.perDay)} / dia`} tone="primary"
                  hint={`${d.deploymentFrequency?.totalDeploys ?? 0} deploys · ${d.deploymentFrequency?.level ?? '—'}`} />
        <StatTile label="Lead time médio" value={formatDuration(d.leadTimeForChanges?.averageSeconds)}
                  hint={`Nível ${d.leadTimeForChanges?.level ?? '—'}`} />
        <StatTile label="Change failure rate" value={`${formatNumber(d.changeFailureRate?.percentage)}%`}
                  hint={`${d.changeFailureRate?.failedDeploys ?? 0} falhos · ${d.changeFailureRate?.level ?? '—'}`} />
        <StatTile label="MTTR" value={formatDuration(d.meanTimeToRestore?.mttrSeconds)}
                  hint={`Nível ${d.meanTimeToRestore?.level ?? '—'}`} />
      </div>

      <section className="card">
        <h2>Deploys correlacionados com incidentes</h2>
        {!d.correlatedIncidents?.length ? (
          <p className="muted small">Nenhum deploy associado à abertura de incidentes neste período.</p>
        ) : (
          <div className="table-scroll">
            <table className="table">
              <thead>
                <tr>
                  <th>Serviço</th>
                  <th>Versão</th>
                  <th>Autor</th>
                  <th>Incidente</th>
                  <th className="numeric">Intervalo</th>
                </tr>
              </thead>
              <tbody>
                {d.correlatedIncidents.map((c) => (
                  <tr key={`${c.deployId}-${c.incidentId}`}>
                    <td><span className="chip">{c.service}</span></td>
                    <td><code className="mono">{c.version}</code></td>
                    <td className="muted">{c.author ?? '—'}</td>
                    <td><Link to={`/incidents/${c.incidentId}`}>{c.incidentTitle}</Link></td>
                    <td className="numeric">{c.minutesAfterDeploy} min depois</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  )
}

function ServicesTab() {
  const services = useApi('/telemetry/services')
  if (services.error) return <ErrorState error={services.error} onRetry={services.reload} />
  if (!services.data) return <Loading />

  const items = services.data
  return (
    <section className="card">
      <h2>Serviços instrumentados (última 1 h)</h2>
      {items.length === 0 ? (
        <p className="muted small">Nenhum serviço enviou telemetria OTLP na última hora.</p>
      ) : (
        <div className="table-scroll">
          <table className="table">
            <thead>
              <tr>
                <th>Serviço</th>
                <th className="numeric">Spans</th>
                <th className="numeric">Spans com erro</th>
                <th className="numeric">Logs</th>
                <th className="numeric">Logs de erro</th>
              </tr>
            </thead>
            <tbody>
              {items.map((s) => (
                <tr key={s.service}>
                  <td><Link to={`/telemetry?service=${encodeURIComponent(s.service)}`}>{s.service}</Link></td>
                  <td className="numeric">{formatNumber(s.spanCount)}</td>
                  <td className="numeric">{formatNumber(s.errorSpanCount)}</td>
                  <td className="numeric">{formatNumber(s.logCount)}</td>
                  <td className="numeric">{formatNumber(s.errorLogCount)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

const currentMinute = () => Math.floor(Date.now() / 60000) * 60000

export default function Dashboards() {
  const [tab, setTab] = useState('executive')
  const [days, setDays] = useState(30)
  const [now, setNow] = useState(currentMinute)

  return (
    <>
      <header className="page-header">
        <div>
          <h1>Dashboards</h1>
          <p className="muted">Visão executiva, operação de incidentes, entrega DORA e saúde dos serviços</p>
        </div>
        {tab !== 'services' && (
          <label className="filter-group">
            <span className="filter-label">Período</span>
            <select
              value={days}
              onChange={(e) => {
                setDays(Number(e.target.value))
                setNow(currentMinute())
              }}
            >
              {DAYS_OPTIONS.map((d) => (
                <option key={d} value={d}>Últimos {d} dias</option>
              ))}
            </select>
          </label>
        )}
      </header>

      <nav className="tabs" aria-label="Visões do dashboard">
        {TABS.map((t) => (
          <button
            key={t.id}
            type="button"
            className={`btn btn-small ${tab === t.id ? 'btn-primary' : ''}`}
            onClick={() => {
              setTab(t.id)
              setNow(currentMinute())
            }}
          >
            {t.label}
          </button>
        ))}
      </nav>

      {tab === 'executive' && <ExecutiveTab days={days} />}
      {tab === 'operations' && <OperationsTab days={days} now={now} />}
      {tab === 'delivery' && <DeliveryTab days={days} now={now} />}
      {tab === 'services' && <ServicesTab />}
    </>
  )
}
