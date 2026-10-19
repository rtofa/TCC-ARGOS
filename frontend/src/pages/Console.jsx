import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api, query } from '../api.js'
import { useAsync } from '../hooks/useApi.js'
import { useAuth } from '../auth/useAuth.js'
import { Icon, StatusIcon } from '../components/Icon.jsx'
import { LogoMark } from '../components/Logo.jsx'
import { SeverityBadge } from '../components/Badges.jsx'
import CommandSearch from '../components/CommandSearch.jsx'
import IncidentsPerDay from '../components/IncidentsPerDay.jsx'
import { Loading, ErrorState } from '../components/States.jsx'
import { SEVERITIES, STATUS_LABEL, formatDuration, timeAgo } from '../format.js'
import { loadRecentPages, visiblePages } from '../navigation.js'

const DAY_MS = 86400e3
const CHART_DAYS = 14
const ACTIVE = ['OPEN', 'INVESTIGATING', 'MITIGATED']
const SEVERITY_RANK = { SEV1: 0, SEV2: 1, SEV3: 2, SEV4: 3 }
const STATUS_ORDER = { critical: 0, warning: 1, good: 2 }
const STATUS_TEXT = { good: 'Operacional', warning: 'Incidente ativo', critical: 'Incidente crítico' }
const MAX_TILES = 12

const optional = (promise) => promise.catch(() => null)
const dayKey = (date) => `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}`

async function loadConsole(role) {
  const now = Date.now()
  const chartStart = new Date(now)
  chartStart.setHours(0, 0, 0, 0)
  chartStart.setDate(chartStart.getDate() - (CHART_DAYS - 1))
  const weekAgo = new Date(now - 7 * DAY_MS).toISOString()

  const [active, recent, deploys, weekDeploys, anyIncident, metrics, sources, keys] = await Promise.all([
    api.get(`/incidents${query({ status: ACTIVE, size: 100 })}`),
    api.get(`/incidents${query({ openedFrom: chartStart.toISOString(), size: 100 })}`),
    api.get(`/deploys${query({ size: 50 })}`),
    api.get(`/deploys${query({ from: weekAgo, size: 1 })}`),
    api.get(`/incidents${query({ size: 1 })}`),
    api.get(`/incidents/metrics${query({ openedFrom: weekAgo })}`),
    optional(api.get('/alert-sources')),
    role === 'ADMIN' ? optional(api.get('/api-keys')) : Promise.resolve(null),
  ])
  return {
    now,
    chartStart: chartStart.getTime(),
    active,
    recent,
    deploys,
    weekDeploys: weekDeploys.totalElements,
    hasIncidents: anyIncident.totalElements > 0,
    metrics: metrics.overall,
    sources,
    keys,
  }
}

// --- derived data (pure) ---------------------------------------------------------------------

function dailyCounts(incidents, chartStart, now) {
  const todayKey = dayKey(new Date(now))
  const days = Array.from({ length: CHART_DAYS }, (_, i) => {
    const date = new Date(chartStart)
    date.setDate(date.getDate() + i)
    return {
      key: dayKey(date),
      short: date.toLocaleDateString('pt-BR', { day: '2-digit', month: '2-digit' }),
      long: date.toLocaleDateString('pt-BR', { weekday: 'long', day: 'numeric', month: 'long' }),
      today: dayKey(date) === todayKey,
      count: 0,
    }
  })
  const byKey = new Map(days.map((day) => [day.key, day]))
  for (const incident of incidents) {
    const day = byKey.get(dayKey(new Date(incident.openedAt)))
    if (day) day.count += 1
  }
  return days
}

/** Services named by incidents or deploys; status comes from the worst active incident touching them. */
function buildServices(active, recent, deploys) {
  const services = new Map()
  const entry = (name) => {
    if (!services.has(name)) services.set(name, { name, worst: null, incident: null, activeCount: 0, lastDeploy: null })
    return services.get(name)
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
    .map((service) => ({
      ...service,
      status: !service.worst ? 'good' : SEVERITY_RANK[service.worst] <= 1 ? 'critical' : 'warning',
      to: service.incident ? `/incidents/${service.incident.id}` : `/deploys?service=${encodeURIComponent(service.name)}`,
    }))
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

function buildChecklist(data, role) {
  // Non-admins can't list keys, but deploys or alert-opened incidents prove one exists.
  const keyInUse = data.deploys.totalElements > 0 || data.recent.content.some((i) => i.source === 'ALERT')
  return [
    { label: 'Criar sua organização', done: true },
    {
      label: 'Gerar uma chave de API',
      done: data.keys ? data.keys.length > 0 : keyInUse,
      to: role === 'ADMIN' ? '/settings/api-keys' : null,
      cta: role === 'ADMIN' ? 'Gerar chave' : 'Peça a um administrador',
    },
    { label: 'Conectar uma fonte de alertas', done: Boolean(data.sources?.length), to: '/alert-sources', cta: 'Configurar' },
    { label: 'Registrar o primeiro deploy', done: data.deploys.totalElements > 0, to: '/deploys', cta: 'Ver como' },
    { label: 'Acompanhar o primeiro incidente', done: data.hasIncidents, to: '/incidents', cta: 'Abrir' },
  ]
}

const greetingFor = (hour) => (hour < 12 ? 'Bom dia' : hour < 18 ? 'Boa tarde' : 'Boa noite')
const capitalize = (text) => text.charAt(0).toUpperCase() + text.slice(1)
const plural = (count, one, many) => `${count} ${count === 1 ? one : many}`

// --- widgets ---------------------------------------------------------------------------------

function Widget({ title, icon, action, className = '', children }) {
  return (
    <section className={`card widget ${className}`}>
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

function HealthWidget({ data }) {
  const total = data.active.totalElements
  const status = total === 0 ? 'good' : data.active.content.some((i) => SEVERITY_RANK[i.severity] <= 1) ? 'critical' : 'warning'
  const bySeverity = Object.fromEntries(SEVERITIES.map((s) => [s, data.active.content.filter((i) => i.severity === s).length]))
  return (
    <Widget title="Saúde agora" icon="incidents" className="span-4" action={<WidgetLink to="/incidents?status=OPEN&status=INVESTIGATING&status=MITIGATED">Ativos</WidgetLink>}>
      <div className={`health health-${status}`}>
        <p className="hero-figure">{total}</p>
        <p className="health-label">
          <StatusIcon status={status} />
          {total === 0 ? 'Nenhum incidente ativo' : plural(total, 'incidente ativo', 'incidentes ativos')}
        </p>
      </div>
      <ul className="severity-counts" aria-label="Incidentes ativos por severidade">
        {SEVERITIES.map((severity) => (
          <li key={severity}>
            <SeverityBadge severity={severity} />
            <strong>{bySeverity[severity]}</strong>
          </li>
        ))}
      </ul>
      <dl className="mini-stats">
        <div>
          <dt>MTTA · 7 dias</dt>
          <dd>{formatDuration(data.metrics.mttaSeconds)}</dd>
        </div>
        <div>
          <dt>MTTR · 7 dias</dt>
          <dd>{formatDuration(data.metrics.mttrSeconds)}</dd>
        </div>
        <div>
          <dt>Deploys · 7 dias</dt>
          <dd>{data.weekDeploys}</dd>
        </div>
      </dl>
    </Widget>
  )
}

function ServiceMapWidget({ services }) {
  const shown = services.slice(0, MAX_TILES)
  return (
    <Widget title="Mapa de serviços" icon="service" className="span-8" action={<WidgetLink to="/deploys">Deploys</WidgetLink>}>
      <ul className="status-legend" aria-label="Legenda">
        {['good', 'warning', 'critical'].map((status) => (
          <li key={status}>
            <StatusIcon status={status} size={14} />
            {status === 'good' ? 'Operacional' : status === 'warning' ? 'Incidente SEV3/SEV4' : 'Incidente SEV1/SEV2'}
          </li>
        ))}
      </ul>
      {shown.length === 0 ? (
        <p className="empty">Os serviços aparecem aqui assim que um incidente ou deploy citar o nome deles.</p>
      ) : (
        <div className="service-grid">
          {shown.map((service) => (
            <Link key={service.name} to={service.to} className={`service-tile service-${service.status}`}>
              <span className="service-tile-head">
                <StatusIcon status={service.status} size={16} />
                <strong>{service.name}</strong>
              </span>
              <span className="service-tile-state">
                {service.worst ? `${service.worst} · ${plural(service.activeCount, 'ativo', 'ativos')}` : 'Operacional'}
              </span>
              <span className="service-tile-deploy">
                <Icon name="deploys" size={13} />
                {service.lastDeploy
                  ? `${service.lastDeploy.version} · ${timeAgo(service.lastDeploy.deployedAt)}`
                  : 'sem deploys registrados'}
              </span>
            </Link>
          ))}
        </div>
      )}
      {services.length > MAX_TILES && <p className="muted small">+{services.length - MAX_TILES} serviços</p>}
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

function QuickAccessWidget({ session }) {
  const [recentPaths] = useState(loadRecentPages)
  const allowed = visiblePages(session).filter((page) => page.path !== '/')
  const recent = recentPaths.map((path) => allowed.find((page) => page.path === path)).filter(Boolean)
  const pages = [...recent, ...allowed.filter((page) => !recent.includes(page))].slice(0, 4)
  return (
    <Widget title={recent.length ? 'Visitados recentemente' : 'Acesso rápido'} icon="home" className="span-4">
      <div className="quick-grid">
        {pages.map((page) => (
          <Link key={page.path} to={page.path} className="quick-tile">
            <span className="quick-icon"><Icon name={page.icon} size={18} /></span>
            <strong>{page.label}</strong>
            <span className="muted small">{page.description}</span>
          </Link>
        ))}
      </div>
    </Widget>
  )
}

function ChecklistWidget({ steps }) {
  const done = steps.filter((step) => step.done).length
  return (
    <Widget title="Primeiros passos" icon="check" className="span-4">
      <div className="meter" role="progressbar" aria-valuemin={0} aria-valuemax={steps.length} aria-valuenow={done} aria-label="Progresso da configuração">
        <span style={{ width: `${(done / steps.length) * 100}%` }} />
      </div>
      <p className="meter-label">
        <strong>{done} de {steps.length}</strong> concluídos
      </p>
      {done === steps.length && (
        <p className="checklist-ready">
          <StatusIcon status="good" size={16} />
          Tudo pronto: o Argos já recebe alertas e deploys da sua organização.
        </p>
      )}
      <ul className="checklist">
        {steps.map((step) => (
          <li key={step.label} className={step.done ? 'is-done' : undefined}>
            <span className="check-mark" aria-hidden="true">{step.done && <Icon name="check" size={12} strokeWidth={3} />}</span>
            <span className="checklist-label">
              {step.label}
              <span className="sr-only">{step.done ? ' (concluído)' : ' (pendente)'}</span>
            </span>
            {!step.done && (step.to ? <Link to={step.to} className="small">{step.cta}</Link> : <span className="muted small">{step.cta}</span>)}
          </li>
        ))}
      </ul>
    </Widget>
  )
}

const ROADMAP = [
  { icon: 'monitor', title: 'Monitores inteligentes', code: 'MON-01 · MON-07', text: 'Detecção de anomalias que avisa antes da falha.' },
  { icon: 'kpi', title: 'Impacto no negócio', code: 'NEG-01 · NEG-03', text: 'Quanto cada incidente custa em pedidos e receita.' },
  { icon: 'sparkle', title: 'Resumo com IA', code: 'AI-01', text: 'Resumo técnico e executivo de cada incidente.' },
]

function RoadmapWidget() {
  return (
    <Widget title="Em breve no Argos" icon="sparkle" className="span-4">
      <ul className="roadmap">
        {ROADMAP.map((item) => (
          <li key={item.title}>
            <span className="quick-icon"><Icon name={item.icon} size={18} /></span>
            <span>
              <strong>{item.title}</strong> <span className="badge">{item.code}</span>
              <span className="muted small roadmap-text">{item.text}</span>
            </span>
          </li>
        ))}
      </ul>
    </Widget>
  )
}

// --- page ------------------------------------------------------------------------------------

export default function Console() {
  const { session } = useAuth()
  const [today] = useState(() => new Date())
  const { data, error, loading, reload } = useAsync(session.role, loadConsole)

  const services = data ? buildServices(data.active.content, data.recent.content, data.deploys.content) : []
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
      <section className="console-hero">
        <span className="console-hero-art" aria-hidden="true">
          <LogoMark size={300} />
        </span>
        <div className="console-hero-top">
          <div>
            <p className="console-date">{capitalize(today.toLocaleDateString('pt-BR', { weekday: 'long', day: 'numeric', month: 'long' }))}</p>
            <h1>{greetingFor(today.getHours())}</h1>
            <p className="console-sub">Tudo o que importa sobre os seus serviços, em um só lugar.</p>
          </div>
          {data && (
            <Link to={activeTotal ? '/incidents?status=OPEN&status=INVESTIGATING&status=MITIGATED' : '/overview'} className={`health-pill health-pill-${overall}`}>
              <StatusIcon status={overall} size={16} />
              {activeTotal === 0 ? 'Todos os serviços operacionais' : plural(activeTotal, 'incidente ativo', 'incidentes ativos')}
            </Link>
          )}
        </div>
        <CommandSearch items={searchItems} />
      </section>

      {error && <ErrorState error={error} onRetry={reload} />}
      {!data && !error && <Loading label="Montando seu console…" />}
      {data && (
        <div className={`console-grid ${loading ? 'is-refreshing' : ''}`}>
          <HealthWidget data={data} />
          <Widget
            title={`Incidentes abertos por dia · ${CHART_DAYS} dias`}
            icon="overview"
            className="span-8"
            action={<WidgetLink to="/overview">Visão geral</WidgetLink>}
          >
            <IncidentsPerDay days={dailyCounts(data.recent.content, data.chartStart, data.now)} />
            {data.recent.totalElements > data.recent.content.length && (
              <p className="muted small">Mostrando os {data.recent.content.length} incidentes mais recentes do período.</p>
            )}
          </Widget>
          <ServiceMapWidget services={services} />
          <ActivityWidget events={buildActivity(data.recent.content, data.deploys.content)} />
          <QuickAccessWidget session={session} />
          <ChecklistWidget steps={buildChecklist(data, session.role)} />
          <RoadmapWidget />
        </div>
      )}
    </div>
  )
}
