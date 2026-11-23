import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api, query } from '../api.js'
import { useApi } from '../hooks/useApi.js'
import { Loading, ErrorState } from '../components/States.jsx'
import { SeverityBadge, StatusBadge, ServiceChips } from '../components/Badges.jsx'
import DateCalendar from '../components/DateCalendar.jsx'
import { Icon } from '../components/Icon.jsx'
import PageGuide from '../components/PageGuide.jsx'
import { dateRangeToInstants, formatBrDate, formatShortBrDate } from '../dates.js'
import { formatDateTime, formatMs, timeAgo } from '../format.js'

const PERIODS = [
  ['15m', '15 min', 15 * 60e3],
  ['1h', '1 hora', 60 * 60e3],
  ['6h', '6 horas', 6 * 60 * 60e3],
  ['24h', '24 horas', 24 * 60 * 60e3],
  ['7d', '7 dias', 7 * 24 * 60 * 60e3],
  ['30d', '30 dias', 30 * 24 * 60 * 60e3],
]
const PERIOD_MS = Object.fromEntries(PERIODS.map(([id, , ms]) => [id, ms]))
const FORMATS = [
  { id: 'csv', label: 'Baixar CSV (Excel)', primary: true },
  { id: 'json', label: 'Baixar JSON' },
  { id: 'html', label: 'Baixar HTML' },
  { id: 'md', label: 'Markdown (.md)' },
]

const currentMinute = () => Math.floor(Date.now() / 60000) * 60000
const browserTz = () => Intl.DateTimeFormat().resolvedOptions().timeZone || 'America/Sao_Paulo'

function StatCard({ label, value, hint, tone }) {
  return (
    <section className={`card tile ${tone ? `tile-${tone}` : ''}`}>
      <h2 className="small muted no-margin">{label}</h2>
      <p className="tile-value">{value}</p>
      {hint && <p className="tile-hint">{hint}</p>}
    </section>
  )
}

export default function Reports() {
  const [params, setParams] = useSearchParams()
  const [now, setNow] = useState(currentMinute)
  const [calendarOpen, setCalendarOpen] = useState(false)
  const [downloading, setDownloading] = useState('')
  const [notice, setNotice] = useState(null)

  const period = PERIOD_MS[params.get('period')] ? params.get('period') : '24h'
  const dateFrom = params.get('dateFrom') ?? ''
  const dateTo = params.get('dateTo') ?? ''
  const service = params.get('service') ?? ''

  function update(changes) {
    const next = new URLSearchParams(params)
    for (const [key, value] of Object.entries(changes)) {
      if (value === undefined || value === null || value === '') next.delete(key)
      else next.set(key, value)
    }
    setNow(currentMinute())
    setParams(next, { replace: true })
  }

  const customRange = dateRangeToInstants(dateFrom, dateTo)
  const fromIso = customRange.from ?? new Date(now - PERIOD_MS[period]).toISOString()
  const toIso = customRange.to ?? new Date(now).toISOString()

  const servicesQuery = useApi(`/telemetry/services${query({ from: fromIso, to: toIso })}`)
  const report = useApi(`/reports/microservices${query({ from: fromIso, to: toIso, service })}`)

  const serviceOptions = Array.from(
    new Set([
      ...(service ? [service] : []),
      ...(servicesQuery.data?.map((s) => s.service) ?? []),
      ...(report.data?.services?.map((s) => s.service) ?? []),
    ]),
  ).sort()

  async function handleDownload(format) {
    setDownloading(format)
    setNotice(null)
    try {
      const qs = query({ format, from: fromIso, to: toIso, service, tz: browserTz() })
      const filename = await api.download(`/reports/microservices/download${qs}`, `argos-relatorio.${format}`)
      setNotice({ kind: 'ok', text: `Relatório gerado e baixado: ${filename}` })
    } catch (err) {
      setNotice({ kind: 'alert', text: err.message || 'Falha ao gerar o relatório.' })
    } finally {
      setDownloading('')
    }
  }

  const dateLabel = dateFrom
    ? dateTo && dateTo !== dateFrom
      ? `${formatShortBrDate(dateFrom)} – ${formatShortBrDate(dateTo)}`
      : formatBrDate(dateFrom)
    : 'Calendário'

  const d = report.data

  return (
    <div className="reports-page">
      <header className="page-header">
        <div>
          <h1>Relatórios de Microsserviços</h1>
          <p className="muted">
            Consolide e baixe os dados enviados pelos microsserviços (traces, métricas RED, logs LGPD, dependências e incidentes)
          </p>
        </div>

        <div className="report-actions no-print">
          {FORMATS.map((f) => (
            <button
              key={f.id}
              type="button"
              className={`btn ${f.primary ? 'btn-primary' : ''} report-btn`}
              disabled={Boolean(downloading) || !d}
              onClick={() => handleDownload(f.id)}
            >
              <Icon name="download" size={15} />
              <span>{downloading === f.id ? 'Gerando…' : f.label}</span>
            </button>
          ))}
          <button type="button" className="btn report-btn" disabled={!d} onClick={() => window.print()}>
            <Icon name="printer" size={15} />
            <span>Imprimir / PDF</span>
          </button>
        </div>
      </header>

      <div className="no-print">
        <PageGuide
          id="reports-page"
          summary="Gera relatórios completos para auditoria, reuniões de SRE ou apresentação acadêmica diretamente a partir da telemetria OTLP recebida dos microsserviços."
          steps={[
            'Escolha a janela temporal (atalhos ou Calendário) e filtre todos os microsserviços ou um serviço específico.',
            'Confira na prévia ao vivo os indicadores de saúde, dependências, métricas OTLP, traces com erro e logs já mascarados (LGPD).',
            'Exporte no formato ideal: CSV (Excel/Sheets), JSON estruturado, HTML autônomo, Markdown (.md) ou PDF via impressão.',
          ]}
        />
      </div>

      {notice && (
        <div className={`banner ${notice.kind === 'ok' ? 'banner-ok' : 'banner-alert'} no-print`} role="status">
          {notice.text}
        </div>
      )}

      <section className="card filters no-print" aria-label="Filtros do relatório">
        <div className="filter-group">
          <span className="filter-label">Janela</span>
          <div className="segmented" role="group" aria-label="Período do relatório">
            {PERIODS.map(([id, label]) => (
              <button
                key={id}
                type="button"
                aria-pressed={!dateFrom && period === id}
                onClick={() => update({ period: id, dateFrom: '', dateTo: '' })}
              >
                {label}
              </button>
            ))}
          </div>
        </div>

        <div className="filter-group">
          <button
            type="button"
            className="toggle calendar-trigger"
            aria-pressed={calendarOpen || Boolean(dateFrom)}
            onClick={() => setCalendarOpen((v) => !v)}
          >
            <Icon name="calendar" size={14} />
            <span>{dateLabel}</span>
          </button>
          {dateFrom && (
            <button
              type="button"
              className="link-button small"
              onClick={() => update({ dateFrom: '', dateTo: '' })}
            >
              Limpar data
            </button>
          )}
        </div>

        <label className="filter-group">
          <span className="filter-label">Microsserviço</span>
          <select value={service} onChange={(e) => update({ service: e.target.value })}>
            <option value="">Todos os microsserviços</option>
            {serviceOptions.map((svc) => (
              <option key={svc} value={svc}>
                {svc}
              </option>
            ))}
          </select>
        </label>

        <button type="button" className="btn btn-small" onClick={() => setNow(currentMinute())}>
          <Icon name="refresh" size={14} />
        </button>
      </section>

      {calendarOpen && (
        <div className="no-print">
          <DateCalendar
            dateFrom={dateFrom}
            dateTo={dateTo}
            onChange={({ dateFrom: nextFrom, dateTo: nextTo }) => update({ dateFrom: nextFrom, dateTo: nextTo })}
            onClose={() => setCalendarOpen(false)}
          />
        </div>
      )}

      {report.error && <ErrorState error={report.error} onRetry={report.reload} />}
      {!d && !report.error && <Loading label="Consolidando dados dos microsserviços…" />}

      {d && (
        <div className={`reports-body ${report.loading ? 'is-refreshing' : ''}`}>
          <section className="card report-meta">
            <span>
              <strong>Escopo:</strong> {d.serviceFilter || 'Todos os microsserviços'}
            </span>
            <span>
              <strong>Período:</strong> {formatDateTime(d.from)} → {formatDateTime(d.to)}
            </span>
            <span className="muted">Gerado em {formatDateTime(d.generatedAt)}</span>
          </section>

          <div className="tiles">
            <StatCard label="Serviços ativos" value={d.summary.totalServices} hint="com sinal OTLP no período" />
            <StatCard
              label="Requisições (Spans)"
              value={d.summary.totalRequests}
              hint={`${d.summary.requestsPerMinute} req/min`}
            />
            <StatCard
              label="Taxa de erro"
              value={`${d.summary.errorRatePercent}%`}
              tone={d.summary.totalErrors > 0 ? 'alert' : 'ok'}
              hint={`${d.summary.totalErrors} spans com falha`}
            />
            <StatCard
              label="Latência p95"
              value={formatMs(d.summary.p95DurationMs)}
              hint={`p50 ${formatMs(d.summary.p50DurationMs)} · p99 ${formatMs(d.summary.p99DurationMs)}`}
            />
            <StatCard
              label="Logs & Métricas OTLP"
              value={`${d.summary.totalLogs} / ${d.summary.metricSeriesCount}`}
              hint={`${d.summary.totalErrorLogs} logs com erro`}
            />
            <StatCard
              label="Incidentes & Deploys"
              value={`${d.summary.incidentsCount} / ${d.summary.deploysCount}`}
              hint="correlacionados na janela"
            />
          </div>

          <section className="card">
            <div className="card-header">
              <h2>Saúde por Microsserviço ({d.services.length})</h2>
              <Link to="/telemetry" className="no-print">Abrir Telemetria</Link>
            </div>
            {d.services.length === 0 ? (
              <p className="empty">
                Nenhum microsserviço enviou dados nesta janela. Verifique a conexão em <Link to="/connect">Conectar App</Link>.
              </p>
            ) : (
              <div className="table-scroll">
                <table className="table">
                  <thead>
                    <tr>
                      <th>Microsserviço</th>
                      <th className="numeric">Spans</th>
                      <th className="numeric">Erros</th>
                      <th className="numeric">Taxa (%)</th>
                      <th className="numeric">Média</th>
                      <th className="numeric">p95</th>
                      <th className="numeric">Logs</th>
                      <th className="numeric">Logs erro</th>
                      <th>Diagnóstico</th>
                      <th>Último sinal</th>
                    </tr>
                  </thead>
                  <tbody>
                    {d.services.map((s) => (
                      <tr key={s.service}>
                        <td><strong>{s.service}</strong></td>
                        <td className="numeric">{s.spans}</td>
                        <td className={`numeric ${s.errorSpans ? 'text-danger' : ''}`}>{s.errorSpans}</td>
                        <td className="numeric">{s.errorRatePercent}%</td>
                        <td className="numeric">{formatMs(s.avgDurationMs)}</td>
                        <td className="numeric">{formatMs(s.p95DurationMs)}</td>
                        <td className="numeric">{s.logs}</td>
                        <td className={`numeric ${s.errorLogs ? 'text-danger' : ''}`}>{s.errorLogs}</td>
                        <td>
                          {s.rootCause ? (
                            <span className="badge status-OPEN">Origem de falha</span>
                          ) : (
                            <span className="badge">Monitorado</span>
                          )}
                        </td>
                        <td className="muted nowrap">{timeAgo(s.lastSeen)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          <div className="grid-2">
            <section className="card">
              <h2>Dependências entre Microsserviços ({d.dependencies.length})</h2>
              {d.dependencies.length === 0 ? (
                <p className="empty">Sem chamadas entre serviços mapeadas neste período.</p>
              ) : (
                <div className="table-scroll">
                  <table className="table">
                    <thead>
                      <tr>
                        <th>Origem → Destino</th>
                        <th className="numeric">Chamadas</th>
                        <th className="numeric">Erros</th>
                        <th className="numeric">p95</th>
                      </tr>
                    </thead>
                    <tbody>
                      {d.dependencies.slice(0, 15).map((dep) => (
                        <tr key={`${dep.source}-${dep.target}`}>
                          <td><strong>{dep.source}</strong> → {dep.target}</td>
                          <td className="numeric">{dep.calls}</td>
                          <td className={`numeric ${dep.errors ? 'text-danger' : ''}`}>{dep.errors} ({dep.errorRatePercent}%)</td>
                          <td className="numeric">{formatMs(dep.p95DurationMs)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </section>

            <section className="card">
              <h2>Métricas OTLP Recebidas ({d.metrics.length})</h2>
              {d.metrics.length === 0 ? (
                <p className="empty">Nenhuma métrica OTLP recebida nesta janela.</p>
              ) : (
                <div className="table-scroll">
                  <table className="table">
                    <thead>
                      <tr>
                        <th>Métrica</th>
                        <th>Serviço</th>
                        <th>Unidade</th>
                        <th className="numeric">Pontos</th>
                      </tr>
                    </thead>
                    <tbody>
                      {d.metrics.slice(0, 15).map((m) => (
                        <tr key={`${m.service}-${m.name}`}>
                          <td className="mono">{m.name}</td>
                          <td>{m.service}</td>
                          <td className="muted">{m.unit || '—'}</td>
                          <td className="numeric">{m.points}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </section>
          </div>

          <section className="card">
            <h2>Traces com Erro ({d.errorTraces.length})</h2>
            {d.errorTraces.length === 0 ? (
              <p className="empty">Nenhum trace com erro registrado na janela.</p>
            ) : (
              <div className="table-scroll">
                <table className="table">
                  <thead>
                    <tr>
                      <th>Operação / Trace ID</th>
                      <th>Serviço raiz</th>
                      <th>Serviços</th>
                      <th className="numeric">Duração</th>
                      <th className="numeric">Erros</th>
                      <th>Início</th>
                    </tr>
                  </thead>
                  <tbody>
                    {d.errorTraces.slice(0, 15).map((t) => (
                      <tr key={t.traceId}>
                        <td>
                          <Link to={`/telemetry?tab=traces&trace=${t.traceId}`}>{t.rootName}</Link>
                          <div className="muted small mono">{t.traceId}</div>
                        </td>
                        <td>{t.rootService}</td>
                        <td className="small">{t.services.join(', ')}</td>
                        <td className="numeric">{formatMs(t.durationMs)}</td>
                        <td className="numeric text-danger">{t.errorCount}</td>
                        <td className="muted nowrap">{formatDateTime(t.startTime)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          <div className="grid-2">
            <section className="card">
              <h2>Incidentes Correlacionados ({d.incidents.length})</h2>
              {d.incidents.length === 0 ? (
                <p className="empty">Nenhum incidente na janela selecionada.</p>
              ) : (
                <div className="table-scroll">
                  <table className="table">
                    <thead>
                      <tr>
                        <th>Incidente</th>
                        <th>Severidade</th>
                        <th>Status</th>
                        <th>Serviços</th>
                      </tr>
                    </thead>
                    <tbody>
                      {d.incidents.slice(0, 15).map((inc) => (
                        <tr key={inc.id}>
                          <td><Link to={`/incidents/${inc.id}`}>{inc.title}</Link></td>
                          <td><SeverityBadge severity={inc.severity} /></td>
                          <td><StatusBadge status={inc.status} /></td>
                          <td><ServiceChips services={inc.affectedServices} /></td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </section>

            <section className="card">
              <h2>Logs de Alerta/Erro Mascarados ({d.errorLogs.length})</h2>
              {d.errorLogs.length === 0 ? (
                <p className="empty">Nenhum log WARN/ERROR no período.</p>
              ) : (
                <div className="table-scroll">
                  <table className="table">
                    <thead>
                      <tr>
                        <th>Horário</th>
                        <th>Serviço</th>
                        <th>Nível</th>
                        <th>Mensagem (LGPD)</th>
                      </tr>
                    </thead>
                    <tbody>
                      {d.errorLogs.slice(0, 12).map((l, idx) => (
                        <tr key={`${l.occurredAt}-${idx}`}>
                          <td className="nowrap muted small">{formatDateTime(l.occurredAt)}</td>
                          <td className="nowrap">{l.service}</td>
                          <td><span className="badge">{l.severity}</span></td>
                          <td className="mono small">{l.body}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </section>
          </div>
        </div>
      )}
    </div>
  )
}
