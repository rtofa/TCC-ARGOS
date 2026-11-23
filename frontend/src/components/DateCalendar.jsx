import { useState } from 'react'
import { Icon } from './Icon.jsx'
import {
  buildMonthCells,
  formatBrDate,
  localIsoDate,
  parseIsoDate,
  shiftIsoDate,
} from '../dates.js'

const WEEKDAYS = ['Dom', 'Seg', 'Ter', 'Qua', 'Qui', 'Sex', 'Sáb']

function initialCursor(dateFrom) {
  const parsed = parseIsoDate(dateFrom)
  if (parsed) return { year: parsed.year, month: parsed.month - 1 }
  const now = new Date()
  return { year: now.getFullYear(), month: now.getMonth() }
}

/**
 * Interactive month calendar for filtering a single date or an inclusive [dateFrom, dateTo] range.
 * Optional `dayMeta` ({ [YYYY-MM-DD]: { count, highestSeverity } }) renders incident dots per day.
 */
export default function DateCalendar({
  dateFrom = '',
  dateTo = '',
  dayMeta,
  onChange,
  onMonthChange,
  onClose,
}) {
  const [cursor, setCursor] = useState(() => initialCursor(dateFrom))
  const [mode, setMode] = useState(() => (dateFrom && dateTo && dateFrom !== dateTo ? 'range' : 'single'))
  const [pendingStart, setPendingStart] = useState('')

  const cells = buildMonthCells(cursor.year, cursor.month)
  const monthLabel = new Date(cursor.year, cursor.month, 1).toLocaleDateString('pt-BR', {
    month: 'long',
    year: 'numeric',
  })

  function moveMonth(delta) {
    const nextDate = new Date(cursor.year, cursor.month + delta, 1)
    const next = { year: nextDate.getFullYear(), month: nextDate.getMonth() }
    setCursor(next)
    onMonthChange?.(next.year, next.month)
  }

  function jumpToday() {
    const now = new Date()
    const next = { year: now.getFullYear(), month: now.getMonth() }
    setCursor(next)
    onMonthChange?.(next.year, next.month)
  }

  function handleDayClick(iso) {
    const parts = parseIsoDate(iso)
    if (parts && (parts.year !== cursor.year || parts.month - 1 !== cursor.month)) {
      setCursor({ year: parts.year, month: parts.month - 1 })
      onMonthChange?.(parts.year, parts.month - 1)
    }

    if (mode === 'single') {
      setPendingStart('')
      if (dateFrom === iso && (!dateTo || dateTo === iso)) {
        onChange({ dateFrom: '', dateTo: '' })
      } else {
        onChange({ dateFrom: iso, dateTo: iso })
      }
      return
    }

    if (!pendingStart) {
      setPendingStart(iso)
      onChange({ dateFrom: iso, dateTo: iso })
    } else {
      const [start, end] = pendingStart <= iso ? [pendingStart, iso] : [iso, pendingStart]
      setPendingStart('')
      onChange({ dateFrom: start, dateTo: end })
    }
  }

  function applyPreset(fromIso, toIso) {
    setPendingStart('')
    setMode(fromIso === toIso ? 'single' : 'range')
    const p = parseIsoDate(fromIso)
    if (p) {
      setCursor({ year: p.year, month: p.month - 1 })
      onMonthChange?.(p.year, p.month - 1)
    }
    onChange({ dateFrom: fromIso, dateTo: toIso })
  }

  const today = localIsoDate()
  const monthStart = `${cursor.year}-${String(cursor.month + 1).padStart(2, '0')}-01`
  const monthLastDay = new Date(cursor.year, cursor.month + 1, 0).getDate()
  const monthEnd = `${cursor.year}-${String(cursor.month + 1).padStart(2, '0')}-${String(monthLastDay).padStart(2, '0')}`

  const activeStart = pendingStart || dateFrom
  const activeEnd = pendingStart || (dateTo || dateFrom)
  const [rangeMin, rangeMax] =
    activeStart && activeEnd
      ? activeStart <= activeEnd
        ? [activeStart, activeEnd]
        : [activeEnd, activeStart]
      : ['', '']

  return (
    <section className="card calendar-panel" aria-label="Calendário de filtro por data">
      <div className="calendar-topbar">
        <div className="calendar-nav">
          <button type="button" className="btn btn-small" onClick={() => moveMonth(-1)} aria-label="Mês anterior">
            ‹
          </button>
          <strong className="calendar-month">{monthLabel}</strong>
          <button type="button" className="btn btn-small" onClick={() => moveMonth(1)} aria-label="Próximo mês">
            ›
          </button>
          <button type="button" className="link-button small" onClick={jumpToday}>
            Mês atual
          </button>
        </div>

        <div className="calendar-controls">
          <div className="segmented" role="group" aria-label="Modo de seleção">
            <button
              type="button"
              aria-pressed={mode === 'single'}
              onClick={() => {
                setMode('single')
                setPendingStart('')
              }}
            >
              Dia único
            </button>
            <button
              type="button"
              aria-pressed={mode === 'range'}
              onClick={() => {
                setMode('range')
                setPendingStart('')
              }}
            >
              Intervalo
            </button>
          </div>
          {onClose && (
            <button type="button" className="btn btn-small" onClick={onClose} aria-label="Fechar calendário">
              <Icon name="close" size={14} />
            </button>
          )}
        </div>
      </div>

      <div className="calendar-presets">
        <span className="muted small">Atalhos:</span>
        <button type="button" className="toggle" onClick={() => applyPreset(today, today)}>Hoje</button>
        <button type="button" className="toggle" onClick={() => applyPreset(shiftIsoDate(today, -1), shiftIsoDate(today, -1))}>Ontem</button>
        <button type="button" className="toggle" onClick={() => applyPreset(shiftIsoDate(today, -6), today)}>Últimos 7 dias</button>
        <button type="button" className="toggle" onClick={() => applyPreset(monthStart, monthEnd)}>Este mês</button>
        {(dateFrom || dateTo) && (
          <button
            type="button"
            className="link-button small"
            onClick={() => {
              setPendingStart('')
              onChange({ dateFrom: '', dateTo: '' })
            }}
          >
            Limpar datas
          </button>
        )}
      </div>

      <div className="calendar-weekdays" aria-hidden="true">
        {WEEKDAYS.map((w) => (
          <span key={w}>{w}</span>
        ))}
      </div>

      <div className="calendar-grid" role="grid" aria-label={monthLabel}>
        {cells.map((cell) => {
          const meta = dayMeta?.[cell.iso]
          const isStart = rangeMin && cell.iso === rangeMin
          const isEnd = rangeMax && cell.iso === rangeMax
          const inRange = rangeMin && rangeMax && cell.iso >= rangeMin && cell.iso <= rangeMax
          const classes = [
            'calendar-day',
            !cell.inMonth && 'is-outside',
            cell.isToday && 'is-today',
            inRange && 'is-in-range',
            (isStart || isEnd) && 'is-selected',
            meta?.count > 0 && 'has-incidents',
          ]
            .filter(Boolean)
            .join(' ')

          const tooltip = meta?.count
            ? `${formatBrDate(cell.iso)}: ${meta.count} ${meta.count === 1 ? 'incidente' : 'incidentes'} (${meta.highestSeverity})`
            : formatBrDate(cell.iso)

          return (
            <button
              key={cell.iso}
              type="button"
              className={classes}
              onClick={() => handleDayClick(cell.iso)}
              title={tooltip}
              aria-pressed={Boolean(isStart || isEnd)}
              aria-label={tooltip}
            >
              <span className="calendar-day-num">{cell.day}</span>
              {meta?.count > 0 && (
                <span className={`calendar-dot sev-${meta.highestSeverity}`}>
                  {meta.count > 99 ? '99+' : meta.count}
                </span>
              )}
            </button>
          )
        })}
      </div>

      <div className="calendar-footer">
        <span className="muted small">
          {pendingStart
            ? `Início selecionado: ${formatBrDate(pendingStart)}. Clique no dia final do intervalo.`
            : dateFrom
              ? dateTo && dateTo !== dateFrom
                ? `Filtrando de ${formatBrDate(dateFrom)} até ${formatBrDate(dateTo)}`
                : `Filtrando dia ${formatBrDate(dateFrom)}`
              : mode === 'range'
                ? 'Clique no dia inicial e depois no dia final.'
                : 'Clique em um dia para listar apenas incidentes daquela data.'}
        </span>
        {dayMeta && (
          <span className="calendar-legend small muted">
            <span className="calendar-legend-item"><i className="sev-dot sev-SEV1" /> SEV1</span>
            <span className="calendar-legend-item"><i className="sev-dot sev-SEV2" /> SEV2</span>
            <span className="calendar-legend-item"><i className="sev-dot sev-SEV3" /> SEV3</span>
            <span className="calendar-legend-item"><i className="sev-dot sev-SEV4" /> SEV4</span>
          </span>
        )}
      </div>
    </section>
  )
}
