import { useState } from 'react'

/** Clean ticks (0, 5, 10… / 0, 20 mil, 40 mil…) whatever the magnitude. */
function niceScale(max) {
  if (max <= 0) return { top: 1, ticks: [0, 1] }
  const rough = max / 3
  const pow = 10 ** Math.floor(Math.log10(rough))
  const step = [1, 2, 5, 10].map((m) => m * pow).find((s) => s >= rough)
  const top = Math.ceil(max / step) * step
  const ticks = []
  for (let tick = 0; tick <= top + step / 2; tick += step) ticks.push(tick)
  return { top, ticks }
}

/**
 * Single-series column chart (one hue, no legend box: the title names the series), with a hover/focus tooltip,
 * the peak labelled, and a table view. items: [{ key, short, long, value, detail? }]
 */
export function ColumnChart({ items, format, formatTick = format, valueLabel, label }) {
  const [hovered, setHovered] = useState(null)
  const { top, ticks } = niceScale(Math.max(0, ...items.map((item) => item.value)))
  const peak = items.reduce((best, item) => (item.value > (best?.value ?? 0) ? item : best), null)

  return (
    <figure className="chart" aria-label={label}>
      <div className="chart-plot chart-plot-wide">
        <div className="chart-grid" aria-hidden="true">
          {ticks.map((tick) => (
            <div key={tick} className="chart-gridline" style={{ bottom: `${(tick / top) * 100}%` }}>
              <span>{formatTick(tick)}</span>
            </div>
          ))}
        </div>
        <div className="chart-bars" style={{ gridTemplateColumns: `repeat(${items.length}, 1fr)` }}>
          {items.map((item, i) => {
            const height = (item.value / top) * 100
            const edge = i < 2 ? 'start' : i > items.length - 3 ? 'end' : 'center'
            return (
              <div key={item.key} className="chart-col">
                <button
                  type="button"
                  className="chart-hit"
                  aria-label={`${item.long}: ${format(item.value)} ${valueLabel}`}
                  onPointerEnter={() => setHovered(i)}
                  onPointerLeave={() => setHovered(null)}
                  onFocus={() => setHovered(i)}
                  onBlur={() => setHovered(null)}
                >
                  <span className="chart-bar" style={{ height: `${height}%` }} />
                </button>
                {item === peak && hovered !== i && item.value > 0 && (
                  <span className="chart-peak" style={{ bottom: `calc(${height}% + 4px)` }} aria-hidden="true">
                    {format(item.value)}
                  </span>
                )}
                {hovered === i && (
                  <div className={`chart-tooltip chart-tooltip-${edge}`} role="tooltip" style={{ bottom: `calc(${height}% + 18px)` }}>
                    <strong>{format(item.value)}</strong>
                    <span>{valueLabel}</span>
                    {item.detail && <span>{item.detail}</span>}
                    <span className="chart-tooltip-date">{item.long}</span>
                  </div>
                )}
              </div>
            )
          })}
        </div>
      </div>
      <div className="chart-x chart-x-wide" style={{ gridTemplateColumns: `repeat(${items.length}, 1fr)` }} aria-hidden="true">
        {items.map((item, i) => (
          <span key={item.key}>{items.length <= 14 || i % Math.ceil(items.length / 10) === 0 ? item.short : ''}</span>
        ))}
      </div>
      <details className="chart-table">
        <summary>Ver como tabela</summary>
        <table className="table">
          <thead>
            <tr>
              <th>Dia</th>
              <th className="numeric">{valueLabel}</th>
            </tr>
          </thead>
          <tbody>
            {items.map((item) => (
              <tr key={item.key}>
                <td>{item.long}</td>
                <td className="numeric">{format(item.value)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
    </figure>
  )
}

/** Ranked horizontal bars for a short list (≤ 8): the value is printed at the end of each row, like a table. */
export function RankBars({ items, format, label, empty = 'Sem dados no período.' }) {
  const max = Math.max(0, ...items.map((item) => item.value))
  if (items.length === 0 || max <= 0) return <p className="muted small">{empty}</p>
  return (
    <ul className="rank-bars" aria-label={label}>
      {items.map((item) => (
        <li key={item.key} title={item.detail}>
          <span className="rank-bars-label">{item.label}</span>
          <span className="rank-bars-track" aria-hidden="true">
            <span className="rank-bars-fill" style={{ width: item.value > 0 ? `${Math.max(2, (item.value / max) * 100)}%` : 0 }} />
          </span>
          <span className="rank-bars-value">{item.display ?? format(item.value)}</span>
        </li>
      ))}
    </ul>
  )
}

/** Hero number with its label; never a chart when the job is a single headline. */
export function StatTile({ label, value, hint, tone }) {
  return (
    <div className={`stat-tile ${tone ? `stat-tile-${tone}` : ''}`}>
      <span className="stat-tile-label">{label}</span>
      <strong className="stat-tile-value">{value}</strong>
      {hint && <span className="stat-tile-hint">{hint}</span>}
    </div>
  )
}
