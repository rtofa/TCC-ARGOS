const DATE_RE = /^(\d{4})-(\d{2})-(\d{2})$/

export function pad2(n) {
  return String(n).padStart(2, '0')
}

export function localIsoDate(date = new Date()) {
  return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`
}

export function shiftIsoDate(iso, deltaDays) {
  const parts = parseIsoDate(iso)
  if (!parts) return ''
  return localIsoDate(new Date(parts.year, parts.month - 1, parts.day + deltaDays))
}

export function parseIsoDate(iso) {
  const match = typeof iso === 'string' ? DATE_RE.exec(iso.trim()) : null
  if (!match) return null
  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  const check = new Date(year, month - 1, day)
  if (check.getFullYear() !== year || check.getMonth() !== month - 1 || check.getDate() !== day) {
    return null
  }
  return { year, month, day }
}

export function formatBrDate(iso) {
  const p = parseIsoDate(iso)
  return p ? `${pad2(p.day)}/${pad2(p.month)}/${p.year}` : ''
}

export function formatShortBrDate(iso) {
  const p = parseIsoDate(iso)
  return p ? `${pad2(p.day)}/${pad2(p.month)}` : ''
}

/**
 * Converts local YYYY-MM-DD bounds to [openedFrom, openedTo) UTC ISO instants.
 */
export function dateRangeToInstants(dateFrom, dateTo) {
  const p1 = parseIsoDate(dateFrom)
  if (!p1) return { from: undefined, to: undefined }
  const p2 = parseIsoDate(dateTo) ?? p1

  const d1 = new Date(p1.year, p1.month - 1, p1.day, 0, 0, 0, 0)
  const d2 = new Date(p2.year, p2.month - 1, p2.day, 0, 0, 0, 0)
  const [start, endBase] = d1 <= d2 ? [d1, d2] : [d2, d1]
  const exclusiveEnd = new Date(endBase.getFullYear(), endBase.getMonth(), endBase.getDate() + 1, 0, 0, 0, 0)
  return {
    from: start.toISOString(),
    to: exclusiveEnd.toISOString(),
  }
}

export function monthBounds(year, monthIndex) {
  const start = new Date(year, monthIndex, 1, 0, 0, 0, 0)
  const end = new Date(year, monthIndex + 1, 1, 0, 0, 0, 0)
  return {
    openedFrom: start.toISOString(),
    openedTo: end.toISOString(),
    tz: Intl.DateTimeFormat().resolvedOptions().timeZone || 'America/Sao_Paulo',
  }
}

export function buildMonthCells(year, monthIndex) {
  const firstDay = new Date(year, monthIndex, 1)
  const startOffset = firstDay.getDay() // 0 = Sunday
  const daysInMonth = new Date(year, monthIndex + 1, 0).getDate()
  const totalCells = Math.ceil((startOffset + daysInMonth) / 7) * 7
  const todayIso = localIsoDate()

  return Array.from({ length: totalCells }, (_, idx) => {
    const date = new Date(year, monthIndex, 1 - startOffset + idx)
    const iso = localIsoDate(date)
    return {
      iso,
      day: date.getDate(),
      inMonth: date.getMonth() === monthIndex && date.getFullYear() === year,
      isToday: iso === todayIso,
    }
  })
}
