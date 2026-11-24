import { useEffect, useRef, useState } from 'react'
import { Icon } from './Icon.jsx'
import {
  buildMonthCells,
  formatBrDate,
  localIsoDate,
  parseIsoDate,
} from '../dates.js'

const WEEKDAYS = ['D', 'S', 'T', 'Q', 'Q', 'S', 'S']

function initialCursor(dateFrom) {
  const parsed = parseIsoDate(dateFrom)
  if (parsed) return { year: parsed.year, month: parsed.month - 1 }
  const now = new Date()
  return { year: now.getFullYear(), month: now.getMonth() }
}

/**
 * Compact calendar trigger + floating 312px month popover supporting single day, date range,
 * and optional preset links without cluttering the page layout.
 */
export default function DateCalendar({
  label = 'Calendário',
  active = false,
  dateFrom = '',
  dateTo = '',
  dayMeta,
  align = 'left',
  allowClear = true,
  presets = [],
  onSelectDate,
  onSelectPreset,
  onClear,
  onMonthChange,
  onOpenChange,
}) {
  const [open, setOpen] = useState(false)
  const [cursor, setCursor] = useState(() => initialCursor(dateFrom))
  const [mode, setMode] = useState(() => (dateFrom && dateTo && dateFrom !== dateTo ? 'range' : 'single'))
  const [pendingStart, setPendingStart] = useState('')
  const rootRef = useRef(null)

  function setPopover(nextOpen) {
    setOpen(nextOpen)
    onOpenChange?.(nextOpen)
  }

  useEffect(() => {
    if (!open) return undefined
    function handleOutside(e) {
      if (rootRef.current && !rootRef.current.contains(e.target)) {
        setOpen(false)
        onOpenChange?.(false)
      }
    }
    function handleKey(e) {
      if (e.key === 'Escape') {
        setOpen(false)
        onOpenChange?.(false)
      }
    }
    document.addEventListener('mousedown', handleOutside)
    document.addEventListener('keydown', handleKey)
    return () => {
      document.removeEventListener('mousedown', handleOutside)
      document.removeEventListener('keydown', handleKey)
    }
  }, [open, onOpenChange])

  const cells = buildMonthCells(cursor.year, cursor.month)
  const monthLabel = new Date(cursor.year, cursor.month, 1).toLocaleDateString('pt-BR', {
    month: 'short',
    year: 'numeric',
  })

  function moveMonth(delta) {
    const nextDate = new Date(cursor.year, cursor.month + delta, 1)
    const next = { year: nextDate.getFullYear(), month: nextDate.getMonth() }
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
      if (allowClear && dateFrom === iso && (!dateTo || dateTo === iso)) {
        onClear?.()
      } else {
        onSelectDate({ dateFrom: iso, dateTo: iso })
      }
      setPopover(false)
      return
    }

    if (!pendingStart) {
      setPendingStart(iso)
    } else {
      const [start, end] = pendingStart <= iso ? [pendingStart, iso] : [iso, pendingStart]
      setPendingStart('')
      onSelectDate({ dateFrom: start, dateTo: end })
      setPopover(false)
    }
  }

  function pickToday() {
    const today = localIsoDate()
    const now = new Date()
    setPendingStart('')
    setMode('single')
    setCursor({ year: now.getFullYear(), month: now.getMonth() })
    onMonthChange?.(now.getFullYear(), now.getMonth())
    onSelectDate({ dateFrom: today, dateTo: today })
    setPopover(false)
  }

  const activeStart = pendingStart || dateFrom
  const activeEnd = pendingStart || (dateTo || dateFrom)
  const [rangeMin, rangeMax] =
    activeStart && activeEnd
      ? activeStart <= activeEnd
        ? [activeStart, activeEnd]
        : [activeEnd, activeStart]
      : ['', '']

  return (
    <div ref={rootRef} className="calendar-anchor">
      <button
        type="button"
        className="toggle calendar-trigger"
        aria-expanded={open}
        aria-pressed={open || active}
        onClick={() => setPopover(!open)}
      >
        <Icon name="calendar" size={14} />
        <span>{label}</span>
      </button>

      {open && (
        <div
          className={`card calendar-popover ${align === 'right' ? 'is-right' : ''}`}
          role="dialog"
          aria-label="Selecionar data no calendário"
        >
          <div className="calendar-topbar">
            <div className="calendar-nav">
              <button type="button" className="calendar-nav-btn" onClick={() => moveMonth(-1)} aria-label="Mês anterior">
                ‹
              </button>
              <strong className="calendar-month">{monthLabel}</strong>
              <button type="button" className="calendar-nav-btn" onClick={() => moveMonth(1)} aria-label="Próximo mês">
                ›
              </button>
            </div>

            <div className="segmented segmented-sm" role="group" aria-label="Modo de seleção">
              <button
                type="button"
                aria-pressed={mode === 'single'}
                onClick={() => {
                  setMode('single')
                  setPendingStart('')
                }}
              >
                Dia
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
          </div>

          <div className="calendar-weekdays" aria-hidden="true">
            {WEEKDAYS.map((w, idx) => (
              <span key={`${w}-${idx}`}>{w}</span>
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
                  {meta?.count > 0 && <i className={`calendar-pip sev-${meta.highestSeverity}`} aria-hidden="true" />}
                </button>
              )
            })}
          </div>

          <div className="calendar-footer">
            {pendingStart ? (
              <span className="muted small">
                Início: {formatBrDate(pendingStart)} · escolha o fim
              </span>
            ) : (
              <div className="calendar-quick">
                <button type="button" className="link-button small" onClick={pickToday}>
                  Hoje
                </button>
                {presets.map((p) => (
                  <button
                    key={p.id}
                    type="button"
                    className="link-button small"
                    onClick={() => {
                      setPendingStart('')
                      onSelectPreset?.(p.id)
                      setPopover(false)
                    }}
                  >
                    {p.label}
                  </button>
                ))}
              </div>
            )}

            {allowClear && (dateFrom || dateTo) && (
              <button
                type="button"
                className="link-button small"
                onClick={() => {
                  setPendingStart('')
                  onClear?.()
                  setPopover(false)
                }}
              >
                Limpar
              </button>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
