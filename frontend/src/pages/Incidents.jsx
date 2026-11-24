import { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { query } from '../api.js'
import { useApi } from '../hooks/useApi.js'
import { useAuth, canWrite } from '../auth/useAuth.js'
import { Loading, ErrorState } from '../components/States.jsx'
import { SeverityBadge, StatusBadge, ServiceChips } from '../components/Badges.jsx'
import DateCalendar from '../components/DateCalendar.jsx'
import { Icon } from '../components/Icon.jsx'
import NewIncidentDialog from '../components/NewIncidentDialog.jsx'
import PageGuide from '../components/PageGuide.jsx'
import { dateRangeToInstants, formatBrDate, formatShortBrDate, monthBounds, parseIsoDate } from '../dates.js'
import { SEVERITIES, STATUSES, STATUS_LABEL, formatDuration, timeAgo } from '../format.js'

const PERIOD_HOURS = { '24h': 24, '7d': 24 * 7, '30d': 24 * 30 }
const PERIOD_LABELS = { '24h': 'Últimas 24 h', '7d': 'Últimos 7 dias', '30d': 'Últimos 30 dias' }
const CALENDAR_PRESETS = [
  { id: '24h', label: '24 h' },
  { id: '7d', label: '7 dias' },
  { id: '30d', label: '30 dias' },
]
const PAGE_SIZE = 20

const currentMinute = () => Math.floor(Date.now() / 60000) * 60000

const STATUS_OPTIONS = STATUSES.map((value) => ({ value, label: STATUS_LABEL[value] }))
const SEVERITY_OPTIONS = SEVERITIES.map((value) => ({ value, label: value }))

function FilterDropdown({ label, emptyLabel, pluralLabel, options, selected, onToggle, onClear }) {
  const [open, setOpen] = useState(false)
  const rootRef = useRef(null)

  useEffect(() => {
    if (!open) return undefined
    function handleOutside(event) {
      if (rootRef.current && !rootRef.current.contains(event.target)) setOpen(false)
    }
    function handleKey(event) {
      if (event.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', handleOutside)
    document.addEventListener('keydown', handleKey)
    return () => {
      document.removeEventListener('mousedown', handleOutside)
      document.removeEventListener('keydown', handleKey)
    }
  }, [open])

  const selectedLabels = options.filter((o) => selected.includes(o.value)).map((o) => o.label)
  let summary = emptyLabel
  if (selectedLabels.length === 1) summary = selectedLabels[0]
  else if (selectedLabels.length > 1) summary = `${selectedLabels.length} ${pluralLabel}`

  return (
    <div className="filter-dropdown" ref={rootRef}>
      <button
        type="button"
        className="toggle filter-dropdown-trigger"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-pressed={selected.length > 0}
        aria-label={label}
        title={selectedLabels.length > 0 ? selectedLabels.join(', ') : undefined}
        onClick={() => setOpen((was) => !was)}
      >
        <span>{summary}</span>
        <Icon name="chevron" size={14} className={open ? 'is-open' : undefined} />
      </button>
      {open && (
        <div className="card filter-dropdown-menu" role="listbox" aria-multiselectable="true" aria-label={label}>
          {options.map((opt) => {
            const checked = selected.includes(opt.value)
            return (
              <button
                key={opt.value}
                type="button"
                role="option"
                aria-selected={checked}
                className={`filter-dropdown-option${checked ? ' is-selected' : ''}`}
                onClick={() => onToggle(opt.value)}
              >
                <span className="filter-dropdown-check" aria-hidden="true">
                  {checked && <Icon name="check" size={12} strokeWidth={2.5} />}
                </span>
                <span>{opt.label}</span>
              </button>
            )
          })}
          {selected.length > 0 && (
            <div className="filter-dropdown-footer">
              <button
                type="button"
                className="btn-link"
                onClick={() => {
                  onClear()
                  setOpen(false)
                }}
              >
                Limpar
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

function initialCalendarMonth(dateFrom) {
  const parsed = parseIsoDate(dateFrom)
  if (parsed) return { year: parsed.year, month: parsed.month - 1 }
  const now = new Date()
  return { year: now.getFullYear(), month: now.getMonth() }
}

function buildDayMeta(days) {
  if (!Array.isArray(days)) return {}
  return Object.fromEntries(days.map((d) => [d.date, { count: d.count, highestSeverity: d.highestSeverity }]))
}

export default function Incidents() {
  const { session } = useAuth()
  const [params, setParams] = useSearchParams()
  const [creating, setCreating] = useState(false)
  const [calendarOpen, setCalendarOpen] = useState(() => Boolean(params.get('dateFrom')))
  // "now" only moves when the user changes a filter, so the request path stays stable between renders.
  const [now, setNow] = useState(currentMinute)

  const statuses = params.getAll('status')
  const severities = params.getAll('severity')
  const period = params.get('period') ?? ''
  const dateFrom = params.get('dateFrom') ?? ''
  const dateTo = params.get('dateTo') ?? ''
  const page = Number(params.get('page') ?? 0)
  const [calendarMonth, setCalendarMonth] = useState(() => initialCalendarMonth(dateFrom))

  function update(changes) {
    const next = new URLSearchParams(params)
    for (const [key, value] of Object.entries({ page: undefined, ...changes })) {
      next.delete(key)
      for (const item of [value].flat()) if (item !== undefined && item !== '') next.append(key, item)
    }
    setNow(currentMinute())
    setParams(next, { replace: true })
  }

  const toggle = (key, current, value) =>
    update({ [key]: current.includes(value) ? current.filter((v) => v !== value) : [...current, value] })

  const customRange = dateRangeToInstants(dateFrom, dateTo)
  const openedFrom = customRange.from ?? (PERIOD_HOURS[period] ? new Date(now - PERIOD_HOURS[period] * 3600e3).toISOString() : undefined)
  const openedTo = customRange.to
  const { data, error, loading, reload } = useApi(
    `/incidents${query({ status: statuses, severity: severities, openedFrom, openedTo, page, size: PAGE_SIZE })}`,
  )

  const calendarPath = calendarOpen
    ? `/incidents/calendar${query({ status: statuses, severity: severities, ...monthBounds(calendarMonth.year, calendarMonth.month) })}`
    : null
  const calendarDays = useApi(calendarPath)

  const dateButtonLabel = dateFrom
    ? dateTo && dateTo !== dateFrom
      ? `${formatShortBrDate(dateFrom)} – ${formatShortBrDate(dateTo)}`
      : formatBrDate(dateFrom)
    : PERIOD_LABELS[period] ?? 'Qualquer data'

  return (
    <>
      <header className="page-header">
        <div>
          <h1>Incidentes</h1>
          <p className="muted">Acompanhe, investigue e resolva</p>
        </div>
        {canWrite(session) && (
          <button type="button" className="btn btn-primary" onClick={() => setCreating(true)}>
            Novo incidente
          </button>
        )}
      </header>

      <PageGuide
        id="incidents"
        summary="Central de gestão de crises: incidentes são abertos manualmente, por webhooks de alerta (agrupados por serviço) ou pelo monitor automático de SLO OTLP."
        steps={[
          'Filtre por status, severidade ou use o Calendário para visualizar dias com incidentes e selecionar uma data ou intervalo.',
          'Clique em um incidente para abrir a Sala de Guerra com linha do tempo, deploys relacionados e impacto financeiro.',
          'Atualize o status (Aberto → Investigando → Mitigado → Resolvido) para alimentar MTTA e MTTR.',
        ]}
      />

      <section className="card filters" aria-label="Filtros">
        <div className="filter-group">
          <span className="filter-label">Status</span>
          <FilterDropdown
            label="Filtrar por status"
            emptyLabel="Todos"
            pluralLabel="selecionados"
            options={STATUS_OPTIONS}
            selected={statuses}
            onToggle={(status) => toggle('status', statuses, status)}
            onClear={() => update({ status: [] })}
          />
        </div>
        <div className="filter-group">
          <span className="filter-label">Severidade</span>
          <FilterDropdown
            label="Filtrar por severidade"
            emptyLabel="Todas"
            pluralLabel="selecionadas"
            options={SEVERITY_OPTIONS}
            selected={severities}
            onToggle={(severity) => toggle('severity', severities, severity)}
            onClear={() => update({ severity: [] })}
          />
        </div>
        <div className="filter-group">
          <span className="filter-label">Período</span>
          <DateCalendar
            label={dateButtonLabel}
            active={Boolean(dateFrom || period)}
            dateFrom={dateFrom}
            dateTo={dateTo}
            dayMeta={buildDayMeta(calendarDays.data)}
            presets={CALENDAR_PRESETS}
            onOpenChange={setCalendarOpen}
            onMonthChange={(year, month) => setCalendarMonth({ year, month })}
            onSelectDate={({ dateFrom: nextFrom, dateTo: nextTo }) =>
              update({ status: statuses, severity: severities, period: '', dateFrom: nextFrom, dateTo: nextTo })
            }
            onSelectPreset={(preset) =>
              update({ status: statuses, severity: severities, period: preset, dateFrom: '', dateTo: '' })
            }
            onClear={() =>
              update({ status: statuses, severity: severities, period: '', dateFrom: '', dateTo: '' })
            }
          />
        </div>
      </section>

      {error && <ErrorState error={error} onRetry={reload} />}
      {!data && !error && <Loading />}
      {data && (
        <section className={`card ${loading ? 'is-refreshing' : ''}`}>
          {data.content.length === 0 ? (
            <p className="empty">Nenhum incidente encontrado com esses filtros.</p>
          ) : (
            <div className="table-scroll">
              <table className="table">
                <thead>
                  <tr>
                    <th>Severidade</th>
                    <th>Incidente</th>
                    <th>Status</th>
                    <th>Serviços</th>
                    <th>Origem</th>
                    <th>Aberto</th>
                    <th>Tempo até resolver</th>
                  </tr>
                </thead>
                <tbody>
                  {data.content.map((incident) => (
                    <tr key={incident.id}>
                      <td><SeverityBadge severity={incident.severity} /></td>
                      <td><Link to={`/incidents/${incident.id}`}>{incident.title}</Link></td>
                      <td><StatusBadge status={incident.status} /></td>
                      <td><ServiceChips services={incident.affectedServices} /></td>
                      <td className="muted">{incident.source === 'ALERT' ? 'Alerta' : 'Manual'}</td>
                      <td className="nowrap muted">{timeAgo(incident.openedAt)}</td>
                      <td className="nowrap muted">{formatDuration(incident.mttrSeconds)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <footer className="pagination">
            <span className="muted">
              {data.totalElements} {data.totalElements === 1 ? 'incidente' : 'incidentes'}
            </span>
            <span>
              <button type="button" className="btn" disabled={page <= 0} onClick={() => update({ status: statuses, severity: severities, period, page: page - 1 })}>
                Anterior
              </button>
              <span className="page-indicator">
                {data.totalPages === 0 ? 0 : page + 1} / {data.totalPages}
              </span>
              <button type="button" className="btn" disabled={page + 1 >= data.totalPages} onClick={() => update({ status: statuses, severity: severities, period, page: page + 1 })}>
                Próxima
              </button>
            </span>
          </footer>
        </section>
      )}

      {creating && <NewIncidentDialog onClose={() => setCreating(false)} />}
    </>
  )
}
