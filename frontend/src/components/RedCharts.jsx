import { Link } from 'react-router-dom'
import { formatDateTime, formatMs } from '../format.js'

const W = 720
const H = 165
const PAD = { top: 18, right: 20, bottom: 26, left: 46 }
const PLOT_W = W - PAD.left - PAD.right
const PLOT_H = H - PAD.top - PAD.bottom

function formatTimeShort(iso) {
  return new Date(iso).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' })
}

function clamp01(val) {
  return Math.max(0, Math.min(1, val))
}

function buildPolyline(points, getVal, tMin, tSpan, yMax) {
  if (!points.length) return ''
  return points
    .map((p) => {
      const t = Date.parse(p.bucket)
      const x = PAD.left + clamp01((t - tMin) / tSpan) * PLOT_W
      const y = PAD.top + PLOT_H - clamp01(getVal(p) / yMax) * PLOT_H
      return `${x.toFixed(1)},${y.toFixed(1)}`
    })
    .join(' ')
}

function buildArea(points, getVal, tMin, tSpan, yMax) {
  if (!points.length) return ''
  const line = buildPolyline(points, getVal, tMin, tSpan, yMax)
  const firstX = (PAD.left + clamp01((Date.parse(points[0].bucket) - tMin) / tSpan) * PLOT_W).toFixed(1)
  const lastX = (PAD.left + clamp01((Date.parse(points[points.length - 1].bucket) - tMin) / tSpan) * PLOT_W).toFixed(1)
  const baseY = (PAD.top + PLOT_H).toFixed(1)
  return `${firstX},${baseY} ${line} ${lastX},${baseY}`
}

function EventMarkers({ markers, tMin, tSpan }) {
  return markers.map((m) => {
    const x = PAD.left + clamp01((m.at - tMin) / tSpan) * PLOT_W
    const isDeploy = m.type === 'deploy'
    const color = isDeploy ? '#1b4f8f' : '#d63f3f'
    return (
      <g key={m.id} className="red-marker">
        <line
          x1={x}
          y1={PAD.top}
          x2={x}
          y2={PAD.top + PLOT_H}
          stroke={color}
          strokeWidth="1.5"
          strokeDasharray={isDeploy ? '4 3' : '2 2'}
        />
        <circle cx={x} cy={PAD.top + 4} r="4" fill={color}>
          <title>{`${m.badge}: ${m.label} (${formatDateTime(m.iso)})`}</title>
        </circle>
      </g>
    )
  })
}

function SeriesCard({ title, subtitle, unit, maxVal, buckets, tMin, tSpan, series, markers }) {
  const safeMax = maxVal > 0 ? maxVal * 1.12 : 1
  const ticks = [0, 0.5, 1].map((f) => ({
    y: PAD.top + PLOT_H - f * PLOT_H,
    label: unit === 'ms' ? Math.round(f * safeMax) : (f * safeMax).toFixed(safeMax >= 10 ? 0 : 1),
  }))

  return (
    <section className="card red-card">
      <div className="card-header">
        <div>
          <h2>{title}</h2>
          <p className="muted small no-margin">{subtitle}</p>
        </div>
        <div className="red-series-legend">
          {series.map((s) => (
            <span key={s.label} className="red-series-tag">
              <i style={{ background: s.color }} aria-hidden="true" />
              {s.label}
            </span>
          ))}
        </div>
      </div>

      <svg className="red-svg" viewBox={`0 0 ${W} ${H}`} role="img" aria-label={title}>
        {ticks.map((t, idx) => (
          <g key={idx}>
            <line x1={PAD.left} y1={t.y} x2={PAD.left + PLOT_W} y2={t.y} stroke="#e6ebf2" strokeWidth="1" />
            <text x={PAD.left - 6} y={t.y + 4} textAnchor="end" className="red-axis-text">
              {t.label}
            </text>
          </g>
        ))}

        {series.map((s) => (
          <g key={s.label}>
            {s.fill && buckets.length > 1 && (
              <polygon points={buildArea(buckets, s.get, tMin, tSpan, safeMax)} fill={s.fill} />
            )}
            {buckets.length > 1 && (
              <polyline
                fill="none"
                stroke={s.color}
                strokeWidth="2.2"
                points={buildPolyline(buckets, s.get, tMin, tSpan, safeMax)}
              />
            )}
            {buckets.map((b) => {
              const x = PAD.left + clamp01((Date.parse(b.bucket) - tMin) / tSpan) * PLOT_W
              const y = PAD.top + PLOT_H - clamp01(s.get(b) / safeMax) * PLOT_H
              return (
                <circle key={b.bucket} cx={x} cy={y} r="3" fill={s.color}>
                  <title>{`${formatTimeShort(b.bucket)} — ${s.label}: ${s.format(s.get(b))}`}</title>
                </circle>
              )
            })}
          </g>
        ))}

        <EventMarkers markers={markers} tMin={tMin} tSpan={tSpan} />

        <text x={PAD.left} y={H - 6} className="red-axis-text">
          {formatTimeShort(new Date(tMin).toISOString())}
        </text>
        <text x={PAD.left + PLOT_W / 2} y={H - 6} textAnchor="middle" className="red-axis-text">
          {formatTimeShort(new Date(tMin + tSpan / 2).toISOString())}
        </text>
        <text x={PAD.left + PLOT_W} y={H - 6} textAnchor="end" className="red-axis-text">
          {formatTimeShort(new Date(tMin + tSpan).toISOString())}
        </text>
      </svg>
    </section>
  )
}

export default function RedCharts({ red, deploys = [], incidents = [], service }) {
  if (!red || !red.buckets?.length) {
    return (
      <section className="card">
        <p className="empty">Nenhuma métrica RED disponível na janela selecionada.</p>
      </section>
    )
  }

  const buckets = red.buckets
  const firstBucketMs = Date.parse(buckets[0].bucket)
  const lastBucketMs = Date.parse(buckets[buckets.length - 1].bucket)
  const tMin = Math.min(Date.parse(red.from), firstBucketMs)
  const tMax = Math.max(Date.parse(red.to), lastBucketMs + red.stepSeconds * 1000)
  const tSpan = Math.max(60000, tMax - tMin)

  const markers = [
    ...deploys
      .filter((d) => (!service || d.service === service) && Date.parse(d.deployedAt) >= tMin && Date.parse(d.deployedAt) <= tMax)
      .map((d) => ({
        id: `deploy-${d.id}`,
        type: 'deploy',
        at: Date.parse(d.deployedAt),
        iso: d.deployedAt,
        badge: 'Deploy',
        label: `${d.service} ${d.version}`,
        to: `/deploys?service=${encodeURIComponent(d.service)}`,
      })),
    ...incidents
      .filter(
        (i) =>
          (!service || i.affectedServices?.includes(service)) &&
          Date.parse(i.openedAt) >= tMin &&
          Date.parse(i.openedAt) <= tMax,
      )
      .map((i) => ({
        id: `inc-${i.id}`,
        type: 'incident',
        at: Date.parse(i.openedAt),
        iso: i.openedAt,
        badge: i.severity,
        label: i.title,
        to: `/incidents/${i.id}`,
      })),
  ].sort((a, b) => a.at - b.at)

  const maxRate = Math.max(1, ...buckets.map((b) => b.requestsPerMinute))
  const maxErr = Math.max(5, ...buckets.map((b) => b.errorRatePercent))
  const maxLat = Math.max(10, ...buckets.map((b) => Math.max(b.p50DurationMs, b.p95DurationMs, b.p99DurationMs)))

  return (
    <div className="red-dashboard">
      <div className="tiles">
        <section className="card tile">
          <h2>Taxa (Rate)</h2>
          <p className="tile-value">{red.requestsPerMinute} req/min</p>
          <p className="tile-hint">{red.totalRequests} requisições no período</p>
        </section>
        <section className={`card tile ${red.errorRatePercent > 0 ? 'tile-alert' : 'tile-ok'}`}>
          <h2>Erros (Errors)</h2>
          <p className="tile-value">{red.errorRatePercent}%</p>
          <p className="tile-hint">{red.totalErrors} spans com falha</p>
        </section>
        <section className="card tile">
          <h2>Latência p50</h2>
          <p className="tile-value">{formatMs(red.p50DurationMs)}</p>
          <p className="tile-hint">mediana das requisições</p>
        </section>
        <section className="card tile">
          <h2>Latência p95 / p99</h2>
          <p className="tile-value">{formatMs(red.p95DurationMs)}</p>
          <p className="tile-hint">p99: {formatMs(red.p99DurationMs)}</p>
        </section>
      </div>

      {markers.length > 0 && (
        <section className="card red-markers-bar">
          <strong>Eventos correlacionados nos gráficos:</strong>
          <div className="chips">
            {markers.map((m) => (
              <Link
                key={m.id}
                to={m.to}
                className={`badge ${m.type === 'incident' ? 'wf-badge' : 'chip'}`}
                title={formatDateTime(m.iso)}
              >
                {m.badge}: {m.label} ({formatTimeShort(m.iso)})
              </Link>
            ))}
          </div>
        </section>
      )}

      <div className="grid-2">
        <SeriesCard
          title="Taxa de requisições (Rate)"
          subtitle={`Vazão em requisições/min (janela de ${red.stepSeconds}s)`}
          unit="req/m"
          maxVal={maxRate}
          buckets={buckets}
          tMin={tMin}
          tSpan={tSpan}
          markers={markers}
          series={[
            {
              label: 'req/min',
              color: '#1b4f8f',
              fill: 'rgba(27, 79, 143, 0.12)',
              get: (b) => b.requestsPerMinute,
              format: (v) => `${v} req/min`,
            },
          ]}
        />

        <SeriesCard
          title="Taxa de erro (Errors)"
          subtitle="Percentual de spans com status ERROR (%)"
          unit="%"
          maxVal={maxErr}
          buckets={buckets}
          tMin={tMin}
          tSpan={tSpan}
          markers={markers}
          series={[
            {
              label: '% erro',
              color: '#d63f3f',
              fill: 'rgba(214, 63, 63, 0.14)',
              get: (b) => b.errorRatePercent,
              format: (v) => `${v}%`,
            },
          ]}
        />
      </div>

      <SeriesCard
        title="Duração / Latência p50 · p95 · p99 (Duration)"
        subtitle="Percentis de latência em milissegundos com marcadores verticais de Deploys (tracejado azul) e Incidentes (vermelho)"
        unit="ms"
        maxVal={maxLat}
        buckets={buckets}
        tMin={tMin}
        tSpan={tSpan}
        markers={markers}
        series={[
          { label: 'p50', color: '#1f9d55', get: (b) => b.p50DurationMs, format: (v) => formatMs(v) },
          { label: 'p95', color: '#ef7d1a', get: (b) => b.p95DurationMs, format: (v) => formatMs(v) },
          { label: 'p99', color: '#d63f3f', get: (b) => b.p99DurationMs, format: (v) => formatMs(v) },
        ]}
      />
    </div>
  )
}
