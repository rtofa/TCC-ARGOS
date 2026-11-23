/** Every page of the console: drives the sidebar, the search and the onboarding tour. */
export const PAGES = [
  { path: '/', label: 'Início', icon: 'home', description: 'Operação em tempo real: serviços, incidentes e atividade agora' },
  { path: '/overview', label: 'Visão geral', icon: 'overview', description: 'Tendências SRE, métricas DORA e confiabilidade por serviço' },
  { path: '/incidents', label: 'Incidentes', icon: 'incidents', description: 'Acompanhe, investigue e resolva crises', tourId: 'nav-incidents' },
  { path: '/deploys', label: 'Deploys', icon: 'deploys', description: 'O que mudou, Quality Gate e histórico' },
  { path: '/telemetry', label: 'Telemetria', icon: 'monitor', description: 'Topologia, gráficos RED, traces e logs', tourId: 'nav-telemetry' },
  { path: '/dashboards', label: 'Dashboards', icon: 'kpi', description: 'Impacto financeiro e visão executiva' },
  { path: '/reports', label: 'Relatórios', icon: 'report', description: 'Baixe relatórios dos dados de microsserviços (CSV, JSON, HTML, PDF)' },
  { path: '/connect', label: 'Conectar App', icon: 'connect', description: 'Configure e valide sua aplicação via OpenTelemetry', tourId: 'nav-connect' },
  {
    path: '/integrations',
    label: 'Integrações',
    icon: 'plug',
    description: 'Catálogo de conectores e avisos (Slack, Discord, E-mail)',
    section: 'integrations',
    tourId: 'nav-integrations',
  },
  {
    path: '/settings/business',
    label: 'KPIs de negócio',
    icon: 'kpi',
    description: 'Pedidos e receita que cada serviço sustenta',
    section: 'integrations',
  },
  {
    path: '/alert-sources',
    label: 'Fontes de alerta',
    icon: 'alerts',
    description: 'Receba alertas de qualquer ferramenta e regras SLO',
    section: 'integrations',
  },
  {
    path: '/settings/api-keys',
    label: 'Chaves de API',
    icon: 'keys',
    description: 'Credenciais para ferramentas externas',
    section: 'integrations',
    adminOnly: true,
  },
  {
    path: '/settings/users',
    label: 'Usuários',
    icon: 'users',
    description: 'Gerencie membros e papéis da organização',
    section: 'integrations',
    adminOnly: true,
    tourId: 'nav-users',
  },
]

export const visiblePages = (session) => PAGES.filter((page) => !page.adminOnly || session?.role === 'ADMIN')

/** The page a path belongs to: /incidents/123 belongs to Incidentes. */
export function pageFor(pathname) {
  return (
    PAGES.find((page) => page.path === pathname) ??
    PAGES.filter((page) => page.path !== '/' && pathname.startsWith(`${page.path}/`)).at(0) ??
    null
  )
}

const RECENT_KEY = 'argos.recentPages'

/** Recently visited pages (per browser); a convenience, so storage failures are ignored. */
export function loadRecentPages() {
  try {
    const value = JSON.parse(localStorage.getItem(RECENT_KEY))
    return Array.isArray(value) ? value.filter((path) => typeof path === 'string') : []
  } catch {
    return []
  }
}

export function recordVisit(path) {
  try {
    const next = [path, ...loadRecentPages().filter((p) => p !== path)].slice(0, 6)
    localStorage.setItem(RECENT_KEY, JSON.stringify(next))
  } catch {
    // Private mode / blocked storage: nothing to remember.
  }
}
