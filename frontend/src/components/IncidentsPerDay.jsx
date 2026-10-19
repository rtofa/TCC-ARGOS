import { useState } from 'react'

/** Clean integer ticks (0, 1, 2… / 0, 5, 10…) for small counts. */
function niceScale(max) {
  if (max <= 0) return { top: 1, ticks: [0, 1] }
  const rough = max / 3
  const pow = 10 ** Math.floor(Math.log10(rough))
  const step = Math.max(1, [1, 2, 5, 10].map((m) => m * pow).find((s) => s >= rough))
  const top = Math.ceil(max / step) * step
  const ticks = []
  for (let tick = 0; tick <= top; tick += step) ticks.push(tick)
  return { top, ticks }
}

const plural = (count) => `${count} ${count === 1 ? 'incidente' : 'incidentes'}`

/**
 * Single-series column chart: incidents opened per day. Every value is also in the table view,
 * so the hover tooltip only enhances.
 * days: [{ key, short, long, count, today }]
 */
export default function IncidentsPerDay({ days }) {
  const [hovered, setHovered] = useState(null)
  const { top, ticks } = niceScale(Math.max(0, ...days.map((day) => day.count)))
  const peak = days.reduce((best, day) => (day.count > (best?.count ?? 0) ? day : best), null)
  const total = days.reduce((sum, day) => sum + day.count, 0)

  return (
    <figure className="chart" aria-label={`Incidentes abertos por dia: ${plural(total)} em ${days.length} dias`}>
      <div className="chart-plot">
        <div className="chart-grid" aria-hidden="true">
          {ticks.map((tick) => (
            <div key={tick} className="chart-gridline" style={{ bottom: `${(tick / top) * 100}%` }}>
              <span>{tick}</span>
            </div>
          ))}
        </div>
        <div className="chart-bars" style={{ gridTemplateColumns: `repeat(${days.length}, 1fr)` }}>
          {days.map((day, i) => {
            const height = (day.count / top) * 100
            const edge = i < 2 ? 'start' : i > days.length - 3 ? 'end' : 'center'
            return (
              <div key={day.key} className="chart-col">
                <button
                  type="button"
                  className="chart-hit"
                  aria-label={`${day.long}: ${plural(day.count)}`}
                  onPointerEnter={() => setHovered(i)}
                  onPointerLeave={() => setHovered(null)}
                  onFocus={() => setHovered(i)}
                  onBlur={() => setHovered(null)}
                >
                  <span className={`chart-bar ${day.today ? 'chart-bar-today' : ''}`} style={{ height: `${height}%` }} />
                </button>
                {day === peak && hovered !== i && (
                  <span className="chart-peak" style={{ bottom: `calc(${height}% + 4px)` }} aria-hidden="true">
                    {day.count}
                  </span>
                )}
                {hovered === i && (
                  <div className={`chart-tooltip chart-tooltip-${edge}`} role="tooltip" style={{ bottom: `calc(${height}% + 8px)` }}>
                    <strong>{day.count}</strong>
                    <span>{day.count === 1 ? 'incidente aberto' : 'incidentes abertos'}</span>
                    <span className="chart-tooltip-date">{day.long}</span>
                  </div>
                )}
              </div>
            )
          })}
        </div>
      </div>
      <div className="chart-x" style={{ gridTemplateColumns: `repeat(${days.length}, 1fr)` }} aria-hidden="true">
        {days.map((day) => (
          <span key={day.key} className={day.today ? 'chart-x-today' : undefined}>{day.today ? 'Hoje' : day.short}</span>
        ))}
      </div>
      <details className="chart-table">
        <summary>Ver como tabela</summary>
        <table className="table">
          <thead>
            <tr>
              <th>Dia</th>
              <th>Incidentes abertos</th>
            </tr>
          </thead>
          <tbody>
            {days.map((day) => (
              <tr key={day.key}>
                <td>{day.long}</td>
                <td className="numeric">{day.count}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
    </figure>
  )
}
