export const SEVERITIES = ['SEV1', 'SEV2', 'SEV3', 'SEV4']
export const STATUSES = ['OPEN', 'INVESTIGATING', 'MITIGATED', 'RESOLVED']

export const SEVERITY_LABEL = {
  SEV1: 'SEV1 · Crítico',
  SEV2: 'SEV2 · Alto',
  SEV3: 'SEV3 · Médio',
  SEV4: 'SEV4 · Baixo',
}

export const STATUS_LABEL = {
  OPEN: 'Aberto',
  INVESTIGATING: 'Investigando',
  MITIGATED: 'Mitigado',
  RESOLVED: 'Resolvido',
}

export const ROLE_LABEL = {
  ADMIN: 'Administrador',
  EDITOR: 'Editor',
  VIEWER: 'Leitor',
  EXECUTIVE: 'Executivo',
}

/** 95 -> "1 min", 7500 -> "2 h 5 min", null -> "—". */
export function formatDuration(seconds) {
  if (seconds === null || seconds === undefined) return '—'
  if (seconds < 60) return `${Math.round(seconds)} s`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes} min`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return minutes % 60 ? `${hours} h ${minutes % 60} min` : `${hours} h`
  const days = Math.floor(hours / 24)
  return hours % 24 ? `${days} d ${hours % 24} h` : `${days} d`
}

const relative = new Intl.RelativeTimeFormat('pt-BR', { numeric: 'auto' })

/** "há 5 minutos", "ontem"… */
export function timeAgo(iso, now = Date.now()) {
  const seconds = Math.round((Date.parse(iso) - now) / 1000)
  const steps = [
    ['day', 86400],
    ['hour', 3600],
    ['minute', 60],
  ]
  for (const [unit, size] of steps) {
    if (Math.abs(seconds) >= size) return relative.format(Math.round(seconds / size), unit)
  }
  return 'agora'
}

export const formatDateTime = (iso) =>
  iso ? new Date(iso).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' }) : '—'
