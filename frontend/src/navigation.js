/** Every page of the console: drives the sidebar, the search and the quick-access widget. */
export const PAGES = [
  { path: '/', label: 'Início', icon: 'home', description: 'Seu console: saúde, atividade e atalhos' },
  { path: '/overview', label: 'Visão geral', icon: 'overview', description: 'Incidentes por severidade, MTTA e MTTR' },
  { path: '/incidents', label: 'Incidentes', icon: 'incidents', description: 'Acompanhe, investigue e resolva' },
  { path: '/deploys', label: 'Deploys', icon: 'deploys', description: 'O que mudou, onde e quando' },
  {
    path: '/alert-sources',
    label: 'Fontes de alerta',
    icon: 'alerts',
    description: 'Receba alertas de qualquer ferramenta',
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
