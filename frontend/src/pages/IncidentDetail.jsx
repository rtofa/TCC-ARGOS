import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, query } from '../api.js'
import { useApi } from '../hooks/useApi.js'
import { useAuth, canWrite } from '../auth/useAuth.js'
import { Loading, ErrorState } from '../components/States.jsx'
import { SeverityBadge, StatusBadge, ServiceChips } from '../components/Badges.jsx'
import { DeployList } from '../components/DeployList.jsx'
import { SEVERITIES, SEVERITY_LABEL, STATUSES, STATUS_LABEL, formatDateTime, formatDuration, timeAgo } from '../format.js'

/** Deploys up to this long before the incident opened are shown as possible causes. */
const DEPLOY_LOOKBACK_HOURS = 24

function incidentDeploysPath(incident) {
  if (!incident || incident.affectedServices.length === 0) return null
  const from = new Date(Date.parse(incident.openedAt) - DEPLOY_LOOKBACK_HOURS * 3600e3).toISOString()
  return `/deploys${query({ service: incident.affectedServices, from, to: incident.resolvedAt, size: 10 })}`
}

const relativeToOpening = (openedAt) => (deploy) => {
  const seconds = (Date.parse(openedAt) - Date.parse(deploy.deployedAt)) / 1000
  return seconds >= 0
    ? `${formatDuration(seconds)} antes da abertura`
    : `${formatDuration(-seconds)} depois da abertura`
}

function AlertsCard({ alerts }) {
  if (!alerts.data?.length) return null
  return (
    <section className="card">
      <h2>Alertas recebidos</h2>
      <ul className="alert-list">
        {alerts.data.map((alert) => (
          <li key={`${alert.sourceId}-${alert.dedupKey}-${alert.firstSeenAt}`}>
            <p>
              <strong>{alert.sourceName}</strong>{' '}
              <span className={`badge ${alert.closedAt ? 'status status-RESOLVED' : 'status status-OPEN'}`}>
                <i aria-hidden="true" />
                {alert.closedAt ? 'Resolvido' : 'Disparado'}
              </span>
            </p>
            <p className="muted small">
              <code className="mono">{alert.dedupKey}</code> · {alert.occurrences}× · último {timeAgo(alert.lastSeenAt)}
            </p>
          </li>
        ))}
      </ul>
    </section>
  )
}

function eventText(event) {
  switch (event.type) {
    case 'OPENED':
      return 'abriu o incidente'
    case 'REOPENED':
      return 'reabriu o incidente'
    case 'STATUS_CHANGED':
      return `alterou o status de ${STATUS_LABEL[event.oldValue] ?? event.oldValue} para ${STATUS_LABEL[event.newValue] ?? event.newValue}`
    case 'SEVERITY_CHANGED':
      return `alterou a severidade de ${event.oldValue} para ${event.newValue}`
    case 'ASSIGNEE_CHANGED':
      return event.newValue ? 'atribuiu o incidente' : 'removeu o responsável'
    case 'ALERT_TRIGGERED':
      return 'recebeu um alerta disparado'
    case 'ALERT_RESOLVED':
      return 'recebeu a resolução do alerta'
    case 'COMMENT':
      return 'comentou'
    default:
      return event.type
  }
}

function Timeline({ events, userId }) {
  if (!events.length) return <p className="empty">Sem eventos ainda.</p>
  return (
    <ol className="timeline">
      {[...events].reverse().map((event) => {
        const author = event.actorType === 'SYSTEM' ? 'Argos (automático)' : event.actorId === userId ? 'Você' : `Usuário ${event.actorId.slice(0, 8)}`
        return (
          <li key={event.id} className={`event event-${event.type}`}>
            <i className="event-dot" aria-hidden="true" />
            <div>
              <p>
                <strong>{author}</strong> {eventText(event)}
                <time className="muted" dateTime={event.occurredAt} title={formatDateTime(event.occurredAt)}>
                  {' · '}{timeAgo(event.occurredAt)}
                </time>
              </p>
              {event.body && <p className="event-body">{event.body}</p>}
            </div>
          </li>
        )
      })}
    </ol>
  )
}

function Fact({ label, children }) {
  return (
    <div className="fact">
      <dt>{label}</dt>
      <dd>{children}</dd>
    </div>
  )
}

export default function IncidentDetail() {
  const { id } = useParams()
  const { session } = useAuth()
  const incident = useApi(`/incidents/${id}`)
  const timeline = useApi(`/incidents/${id}/timeline`)
  const alerts = useApi(`/incidents/${id}/alerts`)
  const deploys = useApi(incidentDeploysPath(incident.data?.id === id ? incident.data : null))
  const [actionError, setActionError] = useState(null)
  const [busy, setBusy] = useState(false)
  const [comment, setComment] = useState('')
  const editable = canWrite(session)

  function refresh() {
    incident.reload()
    timeline.reload()
    alerts.reload()
  }

  async function run(action) {
    setBusy(true)
    setActionError(null)
    try {
      await action()
      refresh()
    } catch (e) {
      setActionError(e.message)
    } finally {
      setBusy(false)
    }
  }

  if (incident.error) {
    return (
      <>
        <Link to="/incidents">← Incidentes</Link>
        <ErrorState error={incident.error} onRetry={incident.reload} />
      </>
    )
  }
  if (!incident.data) return <Loading />
  const data = incident.data

  const patch = (changes) => run(() => api.patch(`/incidents/${id}`, changes))
  const assign = (assigneeId) => run(() => api.put(`/incidents/${id}/assignee`, { assigneeId }))
  const submitComment = (event) => {
    event.preventDefault()
    run(async () => {
      await api.post(`/incidents/${id}/comments`, { body: comment })
      setComment('')
    })
  }

  return (
    <>
      <Link to="/incidents">← Incidentes</Link>
      <header className="page-header detail-header">
        <div>
          <div className="detail-badges">
            <SeverityBadge severity={data.severity} />
            <StatusBadge status={data.status} />
            {data.source === 'ALERT' && <span className="badge">Via alerta</span>}
          </div>
          <h1>{data.title}</h1>
        </div>
        {editable && (
          <div className="detail-actions">
            <label>
              Status
              <select value={data.status} disabled={busy} onChange={(e) => patch({ status: e.target.value })}>
                {STATUSES.map((status) => (
                  <option key={status} value={status}>{STATUS_LABEL[status]}</option>
                ))}
              </select>
            </label>
            <label>
              Severidade
              <select value={data.severity} disabled={busy} onChange={(e) => patch({ severity: e.target.value })}>
                {SEVERITIES.map((severity) => (
                  <option key={severity} value={severity}>{SEVERITY_LABEL[severity]}</option>
                ))}
              </select>
            </label>
          </div>
        )}
      </header>

      {actionError && <p className="form-error" role="alert">{actionError}</p>}

      <div className="detail-grid">
        <div>
          <section className="card">
            <h2>Descrição</h2>
            <p className="prose">{data.description || <span className="muted">Sem descrição.</span>}</p>
          </section>

          <section className="card">
            <h2>Linha do tempo</h2>
            {editable && (
              <form className="comment-form" onSubmit={submitComment}>
                <textarea
                  value={comment}
                  onChange={(e) => setComment(e.target.value)}
                  placeholder="Registre uma atualização ou comentário…"
                  rows={3}
                  maxLength={5000}
                  aria-label="Novo comentário"
                />
                <button className="btn btn-primary" disabled={busy || !comment.trim()}>Comentar</button>
              </form>
            )}
            {timeline.error && <ErrorState error={timeline.error} onRetry={timeline.reload} />}
            {!timeline.data && !timeline.error && <Loading />}
            {timeline.data && <Timeline events={timeline.data} userId={session.userId} />}
          </section>
        </div>

        <aside className="detail-side">
          <section className="card">
            <h2>Detalhes</h2>
            <dl className="facts">
              <Fact label="Serviços afetados"><ServiceChips services={data.affectedServices} /></Fact>
              <Fact label="Responsável">
                {data.assigneeId ? (data.assigneeId === session.userId ? 'Você' : `Usuário ${data.assigneeId.slice(0, 8)}`) : <span className="muted">Ninguém</span>}
                {editable && (
                  <span className="inline-actions">
                    {data.assigneeId !== session.userId && (
                      <button type="button" className="link-button" disabled={busy} onClick={() => assign(session.userId)}>
                        Atribuir a mim
                      </button>
                    )}
                    {data.assigneeId && (
                      <button type="button" className="link-button" disabled={busy} onClick={() => assign(null)}>
                        Remover
                      </button>
                    )}
                  </span>
                )}
              </Fact>
              <Fact label="Aberto em">{formatDateTime(data.openedAt)}</Fact>
              <Fact label="Reconhecido em">{formatDateTime(data.acknowledgedAt)}</Fact>
              <Fact label="Resolvido em">{formatDateTime(data.resolvedAt)}</Fact>
              <Fact label="Tempo até reconhecer">{formatDuration(data.mttaSeconds)}</Fact>
              <Fact label="Tempo até resolver">{formatDuration(data.mttrSeconds)}</Fact>
            </dl>
          </section>

          <section className="card">
            <h2>O que mudou?</h2>
            {data.affectedServices.length === 0 ? (
              <p className="muted small">Informe os serviços afetados para ver os deploys relacionados.</p>
            ) : deploys.error ? (
              <ErrorState error={deploys.error} onRetry={deploys.reload} />
            ) : !deploys.data ? (
              <Loading />
            ) : deploys.data.content.length === 0 ? (
              <p className="muted small">
                Nenhum deploy de {data.affectedServices.join(', ')} nas {DEPLOY_LOOKBACK_HOURS} h antes da abertura.
              </p>
            ) : (
              <DeployList deploys={deploys.data.content} relativeTo={relativeToOpening(data.openedAt)} />
            )}
          </section>

          <AlertsCard alerts={alerts} />
        </aside>
      </div>
    </>
  )
}
