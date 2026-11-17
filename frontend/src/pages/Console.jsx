import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api, query } from '../api.js'
import { useApi, useAsync } from '../hooks/useApi.js'
import { useAuth } from '../auth/useAuth.js'
import { Icon, StatusIcon } from '../components/Icon.jsx'
import { SeverityBadge } from '../components/Badges.jsx'
import CommandSearch from '../components/CommandSearch.jsx'
import ServiceTopologyGraph from '../components/ServiceTopologyGraph.jsx'
import PageGuide from '../components/PageGuide.jsx'
import { Loading, ErrorState } from '../components/States.jsx'
import { STATUS_LABEL, formatDuration, formatMoney, timeAgo } from '../format.js'
import { visiblePages } from '../navigation.js'

const DAY_MS = 86400e3
const CHART_DAYS = 14
const ACTIVE = ['OPEN', 'INVESTIGATING', 'MITIGATED']
const SEVERITY_RANK = { SEV1: 0, SEV2: 1, SEV3: 2, SEV4: 3 }
const STATUS_ORDER = { critical: 0, warning: 1, good: 2 }
const STATUS_TEXT = { good: 'Operacional', warning: 'Incidente ativo', critical: 'Incidente crítico' }
const MAX_TILES = 16
const INFRA_SERVICES = new Set(['otelcol-contrib', 'load-generator', 'flagd', 'flagd-ui', 'telemetry-docs', 'smoke'])

const optional = (promise) => promise.catch(() => null)

async function loadConsole() {
  const now = Date.now()
  const chartStart = new Date(now)
  chartStart.setHours(0, 0, 0, 0)
  chartStart.setDate(chartStart.getDate() - (CHART_DAYS - 1))
  const weekAgo = new Date(now - 7 * DAY_MS).toISOString()

  const [active, recent, deploys, weekDeploys, metrics, telemetryServices, topology] = await Promise.all([
    api.get(`/incidents${query({ status: ACTIVE, size: 100 })}`),
    api.get(`/incidents${query({ openedFrom: chartStart.toISOString(), size: 100 })}`),
    api.get(`/deploys${query({ size: 50 })}`),
    api.get(`/deploys${query({ from: weekAgo, size: 1 })}`),
    api.get(`/incidents/metrics${query({ openedFrom: weekAgo })}`),
    optional(api.get('/telemetry/services')),
    optional(api.get('/telemetry/topology')),
  ])
  return {
    now,
    active,
    recent,
    deploys,
    weekDeploys: weekDeploys.totalElements,
    metrics: metrics.overall,
    telemetryServices: (telemetryServices ?? []).filter((s) => !INFRA_SERVICES.has(s.service)),
    topology,
  }
}

// --- derived data (pure) ---------------------------------------------------------------------

/** Services discovered via OpenTelemetry, incidents or deploys; status reflects active incidents and live OTLP errors. */
function buildServices(active, recent, deploys, telemetryServices = []) {
  const services = new Map()
  const entry = (name) => {
    if (!services.has(name)) {
      services.set(name, { name, worst: null, incident: null, activeCount: 0, lastDeploy: null, spans: 0, errorSpans: 0, logs: 0, errorLogs: 0 })
    }
    return services.get(name)
  }
  for (const t of telemetryServices) {
    const service = entry(t.service)
    service.spans = t.spans
    service.errorSpans = t.errorSpans
    service.logs = t.logs
    service.errorLogs = t.errorLogs
  }
  for (const incident of active) {
    for (const name of incident.affectedServices) {
      const service = entry(name)
      service.activeCount += 1
      if (!service.worst || SEVERITY_RANK[incident.severity] < SEVERITY_RANK[service.worst]) {
        service.worst = incident.severity
        service.incident = incident
      }
    }
  }
  for (const incident of recent) incident.affectedServices.forEach(entry)
  for (const deploy of deploys) {
    const service = entry(deploy.service)
    if (!service.lastDeploy) service.lastDeploy = deploy // newest first
  }
  return [...services.values()]
    .map((service) => {
      const status = service.worst
        ? SEVERITY_RANK[service.worst] <= 1
          ? 'critical'
          : 'warning'
        : service.errorSpans > 0
          ? 'warning'
          : 'good'
      return {
        ...service,
        status,
        to: service.incident ? `/incidents/${service.incident.id}` : `/telemetry?tab=traces&service=${encodeURIComponent(service.name)}`,
      }
    })
    .sort((a, b) => STATUS_ORDER[a.status] - STATUS_ORDER[b.status] || a.name.localeCompare(b.name))
}

function buildActivity(incidents, deploys) {
  const events = []
  for (const incident of incidents) {
    const to = `/incidents/${incident.id}`
    events.push({
      id: `opened-${incident.id}`,
      at: incident.openedAt,
      icon: incident.source === 'ALERT' ? 'alerts' : 'incidents',
      tone: 'incident',
      title: incident.title,
      detail: `${incident.severity} aberto${incident.source === 'ALERT' ? ' por alerta' : ''}`,
      to,
    })
    if (incident.resolvedAt) {
      events.push({ id: `resolved-${incident.id}`, at: incident.resolvedAt, icon: 'check', tone: 'resolved', title: incident.title, detail: 'resolvido', to })
    }
  }
  for (const deploy of deploys) {
    events.push({
      id: `deploy-${deploy.id}`,
      at: deploy.deployedAt,
      icon: 'deploys',
      tone: 'deploy',
      title: `${deploy.service} ${deploy.version}`,
      detail: `deploy em ${deploy.environment}${deploy.author ? ` por ${deploy.author}` : ''}`,
      to: `/deploys?service=${encodeURIComponent(deploy.service)}`,
    })
  }
  return events.sort((a, b) => Date.parse(b.at) - Date.parse(a.at)).slice(0, 6)
}

const plural = (count, one, many) => `${count} ${count === 1 ? one : many}`

// --- widgets ---------------------------------------------------------------------------------

function Widget({ title, icon, action, className = '', tourId, children }) {
  return (
    <section className={`card widget ${className}`} data-tour={tourId}>
      <header className="widget-header">
        <span className="widget-title">
          <Icon name={icon} size={16} />
          <h2>{title}</h2>
        </span>
        {action}
      </header>
      {children}
    </section>
  )
}

const WidgetLink = ({ to, children }) => (
  <Link to={to} className="widget-link">
    {children} <Icon name="arrow" size={14} />
  </Link>
)

function ServiceMapWidget({ services, topology, className = 'span-8' }) {
  const navigate = useNavigate()
  const hasGraph = (topology?.edges?.length ?? 0) > 0
  const [view, setView] = useState('auto')
  const mode = view === 'auto' ? (hasGraph ? 'graph' : 'grid') : view
  const shown = services.slice(0, MAX_TILES)
  return (
    <Widget
      title="Mapa de serviços e topologia (OpenTelemetry ao vivo)"
      icon="service"
      className={className}
      tourId="service-map"
      action={
        <span className="inline-actions no-margin">
          {hasGraph && (
            <span className="segmented segmented-sm" role="group" aria-label="Modo de visualização">
              <button type="button" aria-pressed={mode === 'graph'} onClick={() => setView('graph')}>
                Topologia
              </button>
              <button type="button" aria-pressed={mode === 'grid'} onClick={() => setView('grid')}>
                Grade
              </button>
            </span>
          )}
          <WidgetLink to="/telemetry">Telemetria</WidgetLink>
        </span>
      }
    >
      {mode === 'graph' && topology?.nodes?.length ? (
        <ServiceTopologyGraph
          topology={topology}
          onSelectService={(svc) => navigate(`/telemetry?tab=red&service=${encodeURIComponent(svc)}`)}
        />
      ) : (
        <>
          <ul className="status-legend" aria-label="Legenda">
            {['good', 'warning', 'critical'].map((status) => (
              <li key={status}>
                <StatusIcon status={status} size={14} />
                {status === 'good' ? 'Operacional' : status === 'warning' ? 'Degradado / SEV3-4' : 'Incidente SEV1/SEV2'}
              </li>
            ))}
          </ul>
          {shown.length === 0 ? (
            <p className="empty">Os serviços aparecem aqui assim que enviarem telemetria OTLP ou forem citados em um incidente.</p>
          ) : (
            <div className="service-grid">
              {shown.map((service) => (
                <Link key={service.name} to={service.to} className={`service-tile service-${service.status}`}>
                  <span className="service-tile-head">
                    <StatusIcon status={service.status} size={16} />
                    <strong>{service.name}</strong>
                  </span>
                  <span className="service-tile-state">
                    {service.worst
                      ? `${service.worst} · ${plural(service.activeCount, 'incidente ativo', 'incidentes ativos')}`
                      : service.errorSpans > 0
                        ? `${service.errorSpans} span(s) com erro`
                        : 'Operacional'}
                  </span>
                  <span className="service-tile-deploy">
                    <Icon name="monitor" size={13} />
                    {service.spans > 0 || service.logs > 0
                      ? `${service.spans} spans · ${service.logs} logs (1h)`
                      : service.lastDeploy
                        ? `${service.lastDeploy.version} · ${timeAgo(service.lastDeploy.deployedAt)}`
                        : 'aguardando telemetria'}
                  </span>
                </Link>
              ))}
            </div>
          )}
          {services.length > MAX_TILES && <p className="muted small">+{services.length - MAX_TILES} serviços</p>}
        </>
      )}
    </Widget>
  )
}

const CONFIDENCE_TEXT = { ALTA: 'confiança alta', MEDIA: 'confiança média', BAIXA: 'confiança baixa' }

function KpiTile({ to, label, value, hint, status }) {
  return (
    <Link to={to} className="home-kpi">
      <span className="home-kpi-label">
        {status && <StatusIcon status={status} size={15} />}
        {label}
      </span>
      <strong className="home-kpi-value">{value}</strong>
      <span className="home-kpi-hint">{hint}</span>
    </Link>
  )
}

/** The four numbers that matter first: what is broken, what it costs, how fast we fix, what changed. */
function KpiStrip({ data, executive }) {
  const total = data.active.totalElements
  const critical = data.active.content.filter((i) => SEVERITY_RANK[i.severity] <= 1).length
  const status = total === 0 ? 'good' : critical > 0 ? 'critical' : 'warning'
  const lastDeploy = data.deploys.content[0]
  const loss = executive?.totalLoss
  return (
    <div className="home-kpis" data-tour="kpi-strip">
      <KpiTile to="/incidents?status=OPEN&status=INVESTIGATING&status=MITIGATED" label="Incidentes ativos" status={status}
        value={total} hint={total === 0 ? 'Todos os serviços operacionais' : plural(critical, 'crítico (SEV1/SEV2)', 'críticos (SEV1/SEV2)')} />
      <KpiTile to="/dashboards?painel=executivo&dias=7" label="Perda estimada · 24 h"
        value={loss === undefined ? '…' : formatMoney(loss, { compact: Number(loss) >= 100000 })}
        hint={executive && !executive.kpisConfigured ? 'Configure os KPIs de negócio' : 'pedidos e receita em risco'} />
      <KpiTile to="/overview" label="Tempo até resolver · 7 dias" value={formatDuration(data.metrics.mttrSeconds)}
        hint={`reconhecimento em ${formatDuration(data.metrics.mttaSeconds)}`} />
      <KpiTile to="/deploys" label="Deploys · 7 dias" value={data.weekDeploys}
        hint={lastDeploy ? `último: ${lastDeploy.service} ${lastDeploy.version}, ${timeAgo(lastDeploy.deployedAt)}` : 'nenhum registrado'} />
    </div>
  )
}

function InvestigationCell({ investigation }) {
  if (!investigation) return <span className="muted small">Ainda não investigado</span>
  if (investigation.status === 'PENDING' || investigation.status === 'RUNNING') {
    return <span className="home-investigating"><span className="spinner" aria-hidden="true" /> Investigando…</span>
  }
  if (investigation.status === 'FAILED') return <span className="muted small">A investigação falhou</span>
  return (
    <span className="home-cause">
      <span className="home-cause-text">{investigation.rootCause}</span>
      <span className="muted small">
        {investigation.mode === 'AI' ? <span className="badge badge-ai">IA</span> : <span className="badge">Regras</span>}{' '}
        {CONFIDENCE_TEXT[investigation.confidence] ?? ''}
      </span>
    </span>
  )
}

/** Active incidents with the cause the AI found and what each one is costing: the core of the home. */
function ActiveIncidentsWidget({ items }) {
  return (
    <Widget title="Incidentes ativos" icon="incidents" className="span-12"
      action={<WidgetLink to="/incidents?status=OPEN&status=INVESTIGATING&status=MITIGATED">Ver todos</WidgetLink>}>
      {!items ? <Loading /> : items.length === 0 ? (
        <p className="home-all-good"><StatusIcon status="good" size={18} /> Nenhum incidente ativo: tudo operacional.</p>
      ) : (
        <div className="table-scroll">
          <table className="table home-incidents">
            <thead>
              <tr>
                <th>Incidente</th>
                <th>Status</th>
                <th>Causa provável</th>
                <th className="numeric">Impacto estimado</th>
              </tr>
            </thead>
            <tbody>
              {items.map((incident) => (
                <tr key={incident.id}>
                  <td>
                    <span className="home-incident-title">
                      <SeverityBadge severity={incident.severity} />
                      <Link to={`/incidents/${incident.id}`}>{incident.title}</Link>
                    </span>
                    <span className="muted small">
                      {incident.services.length ? `${incident.services.join(', ')} · ` : ''}aberto {timeAgo(incident.openedAt)}
                    </span>
                  </td>
                  <td className="nowrap">{STATUS_LABEL[incident.status]}</td>
                  <td><InvestigationCell investigation={incident.investigation} /></td>
                  <td className="numeric nowrap">
                    {incident.impactValue === null ? <span className="muted" title="Nenhum KPI ligado aos serviços">—</span>
                      : formatMoney(incident.impactValue, { compact: Number(incident.impactValue) >= 100000 })}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Widget>
  )
}

function ActivityWidget({ events }) {
  return (
    <Widget title="Atividade recente" icon="clock" className="span-4">
      {events.length === 0 ? (
        <p className="empty">Nada aconteceu nos últimos {CHART_DAYS} dias.</p>
      ) : (
        <ol className="activity">
          {events.map((event) => (
            <li key={event.id}>
              <span className={`activity-icon activity-${event.tone}`}>
                <Icon name={event.icon} size={14} strokeWidth={2} />
              </span>
              <Link to={event.to} className="activity-body">
                <strong>{event.title}</strong>
                <span className="muted small">
                  {event.detail} · {timeAgo(event.at)}
                </span>
              </Link>
            </li>
          ))}
        </ol>
      )}
    </Widget>
  )
}

// --- page ------------------------------------------------------------------------------------

export default function Console() {
  const { session } = useAuth()
  const { data, error, loading, reload } = useAsync(session.role, loadConsole, 5000)
  const activeIncidents = useApi('/ai/active-incidents', 15000)
  const executive = useApi('/business/executive?days=1', 60000)

  const services = data ? buildServices(data.active.content, data.recent.content, data.deploys.content, data.telemetryServices) : []
  const incidents = data
    ? [...data.active.content, ...data.recent.content.filter((i) => !data.active.content.some((a) => a.id === i.id))]
    : []
  const searchItems = [
    ...visiblePages(session).map((page) => ({ group: 'Páginas', label: page.label, hint: page.description, to: page.path, icon: page.icon })),
    ...incidents.map((incident) => ({
      group: 'Incidentes',
      label: incident.title,
      hint: `${incident.severity} · ${STATUS_LABEL[incident.status]}`,
      to: `/incidents/${incident.id}`,
      icon: 'incidents',
    })),
    ...services.map((service) => ({ group: 'Serviços', label: service.name, hint: STATUS_TEXT[service.status], to: service.to, icon: 'service' })),
  ]

  const activeTotal = data?.active.totalElements ?? 0
  const overall = activeTotal === 0 ? 'good' : services.some((s) => s.status === 'critical') ? 'critical' : 'warning'

  return (
    <div className="console">
      <header className="home-header">
        <h1 className="sr-only">Início</h1>
        <CommandSearch items={searchItems} />
        {data && (
          <Link to={activeTotal ? '/incidents?status=OPEN&status=INVESTIGATING&status=MITIGATED' : '/overview'} className={`status-pill status-pill-${overall}`}>
            <StatusIcon status={overall} size={16} />
            {activeTotal === 0 ? 'Todos os serviços operacionais' : plural(activeTotal, 'incidente ativo', 'incidentes ativos')}
          </Link>
        )}
      </header>

      {error && <ErrorState error={error} onRetry={reload} />}
      {!data && !error && <Loading label="Montando seu console…" />}
      {data && (
        <>
          <PageGuide
            id="console-home"
            summary="Sala de Operação em Tempo Real: mostra a saúde atual dos seus serviços, incidentes abertos agora e causa-raiz investigada automaticamente."
            actions={
              <>
                <Link to="/connect" className="btn btn-small btn-primary">Conectar aplicação</Link>
                <Link to="/overview" className="btn btn-small">Ver tendências e DORA (Visão geral)</Link>
              </>
            }
          />
          <KpiStrip data={data} executive={executive.data} />
          <div className={`console-grid ${loading ? 'is-refreshing' : ''}`}>
            <ActiveIncidentsWidget items={activeIncidents.data} />
            <ServiceMapWidget services={services} topology={data.topology} className="span-8" />
            <ActivityWidget events={buildActivity(data.recent.content, data.deploys.content)} />
          </div>
        </>
      )}
    </div>
  )
}
