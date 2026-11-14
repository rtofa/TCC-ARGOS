import { useState } from 'react'
import { api } from '../api.js'
import { useApi } from '../hooks/useApi.js'
import { Icon, StatusIcon } from './Icon.jsx'
import { ErrorState, Loading } from './States.jsx'
import { formatDateTime, formatMs } from '../format.js'

const ACTIVE = new Set(['PENDING', 'RUNNING'])
const POLL_MS = 2500

const CONFIDENCE = {
  ALTA: { label: 'Confiança alta', status: 'good' },
  MEDIA: { label: 'Confiança média', status: 'warning' },
  BAIXA: { label: 'Confiança baixa', status: 'critical' },
}

const EVIDENCE_LABEL = {
  DEPLOY: 'Deploy',
  METRICA: 'Métrica',
  TRACE: 'Trace',
  LOG: 'Log',
  TOPOLOGIA: 'Topologia',
  ALERTA: 'Alerta',
  HISTORICO: 'Histórico',
}

const HYPOTHESIS = {
  CONFIRMADA: { label: 'Confirmada', status: 'good' },
  DESCARTADA: { label: 'Descartada', status: 'critical' },
  EM_ABERTO: { label: 'Em aberto', status: 'warning' },
}

const TOOL_LABEL = {
  saude_do_servico: 'Saúde do serviço',
  deploys_recentes: 'Deploys recentes',
  dependencias_do_servico: 'Dependências',
  traces_com_erro: 'Traces com erro',
  detalhe_do_trace: 'Detalhe do trace',
  logs_de_erro: 'Logs de erro',
  incidentes_anteriores: 'Incidentes anteriores',
}

const GUARDRAIL_LABEL = {
  PII_MASCARADA: 'Dados pessoais mascarados',
  PII_NA_RESPOSTA: 'Dados pessoais removidos da resposta',
  INJECAO_NEUTRALIZADA: 'Tentativa de injeção neutralizada',
  INJECAO_BLOQUEADA: 'Tentativa de injeção bloqueada',
  FORMATO_INVALIDO: 'Resposta fora do formato, refeita',
  RELATORIO_CORRIGIDO: 'Relatório corrigido para citar só dados reais',
  EVIDENCIAS_REMOVIDAS: 'Evidências sem lastro removidas',
}

const usd = (value) => `US$ ${Number(value ?? 0).toFixed(4)}`
const tokens = (n) => (n >= 1000 ? `${(n / 1000).toFixed(1)} mil` : String(n ?? 0))

/** AI-01 / AI-02 / AI-05: the automatic investigation of the incident, polled while it runs. */
export default function InvestigationCard({ incidentId, editable }) {
  const path = `/incidents/${incidentId}/investigation`
  const [pollMs, setPollMs] = useState(0)
  const investigation = useApi(path, pollMs)
  const [requesting, setRequesting] = useState(false)
  const [requestError, setRequestError] = useState(null)
  const [view, setView] = useState('executive')

  const data = investigation.data
  const active = ACTIVE.has(data?.status)
  // Poll only while the investigation is queued or running (adjusting state during render, not in an effect).
  if ((active ? POLL_MS : 0) !== pollMs) setPollMs(active ? POLL_MS : 0)

  async function request() {
    setRequesting(true)
    setRequestError(null)
    try {
      await api.post(path)
      investigation.reload()
    } catch (e) {
      setRequestError(e.message)
    } finally {
      setRequesting(false)
    }
  }

  return (
    <section className="card ai-card" aria-labelledby="ai-card-title">
      <header className="ai-card-header">
        <h2 id="ai-card-title">
          <Icon name="sparkle" size={18} /> Investigação automática
        </h2>
        {data?.mode && (
          <span className={`badge ${data.mode === 'AI' ? 'badge-ai' : ''}`}>
            {data.mode === 'AI' ? 'IA' : 'Heurística (sem IA)'}
          </span>
        )}
        {editable && (
          <button type="button" className="btn btn-small" disabled={requesting || active} onClick={request}>
            {data ? 'Investigar novamente' : 'Investigar agora'}
          </button>
        )}
      </header>

      {requestError && <p className="form-error" role="alert">{requestError}</p>}
      {investigation.error && <ErrorState error={investigation.error} onRetry={investigation.reload} />}
      {data === undefined && !investigation.error && <Loading />}
      {data === null && (
        <p className="muted">
          Este incidente ainda não foi investigado. A investigação roda sozinha quando um incidente abre e cruza
          deploys, métricas, traces e logs para apontar a causa provável.
        </p>
      )}
      {data && <InvestigationBody data={data} view={view} onView={setView} />}
    </section>
  )
}

function InvestigationBody({ data, view, onView }) {
  if (data.status === 'PENDING') {
    const later = data.runAfter && new Date(data.runAfter) > new Date(data.createdAt)
    return (
      <div className="ai-progress" role="status">
        <span className="spinner" aria-hidden="true" />
        {later ? 'Aguardando os dados do incidente chegarem para começar a investigação…' : 'Na fila, começa em instantes…'}
      </div>
    )
  }
  if (data.status === 'RUNNING') {
    return (
      <div className="ai-progress" role="status">
        <span className="spinner" aria-hidden="true" />
        Investigando: consultando deploys, métricas, traces e logs…
      </div>
    )
  }
  if (data.status === 'FAILED') {
    return <p className="form-error">A investigação falhou: {data.error || 'erro desconhecido'}.</p>
  }

  const report = data.report
  const confidence = CONFIDENCE[report.rootCause?.confidence] ?? CONFIDENCE.BAIXA
  return (
    <>
      <div className="ai-root-cause">
        <div className="ai-root-cause-label">
          <span className="eyebrow">Causa provável</span>
          <span className="confidence">
            <StatusIcon status={confidence.status} size={16} /> {confidence.label}
          </span>
          {report.rootCause?.service && <span className="chip">{report.rootCause.service}</span>}
        </div>
        <p>{report.rootCause?.summary}</p>
      </div>

      <div className="ai-summary">
        <div className="segmented" role="tablist" aria-label="Resumo">
          <button type="button" role="tab" aria-selected={view === 'executive'} className="toggle"
            aria-pressed={view === 'executive'} onClick={() => onView('executive')}>Para gestores</button>
          <button type="button" role="tab" aria-selected={view === 'technical'} className="toggle"
            aria-pressed={view === 'technical'} onClick={() => onView('technical')}>Técnico</button>
        </div>
        <p className="prose">{view === 'executive' ? report.executiveSummary : report.technicalSummary}</p>
      </div>

      {report.evidence?.length > 0 && (
        <>
          <h3>Evidências</h3>
          <ul className="ai-evidence">
            {report.evidence.map((item, index) => (
              <li key={index}>
                <span className="badge">{EVIDENCE_LABEL[item.kind] ?? item.kind}</span>
                <span>{item.description}</span>
                {item.reference && <code className="mono">{item.reference}</code>}
              </li>
            ))}
          </ul>
        </>
      )}

      {report.alertExplanations?.length > 0 && (
        <>
          <h3>Alertas explicados</h3>
          <ul className="ai-alerts">
            {report.alertExplanations.map((alert, index) => (
              <li key={index}>
                <code className="mono">{alert.alert}</code>
                <p>{alert.explanation}</p>
                {alert.whatToCheck && <p className="muted small">O que verificar: {alert.whatToCheck}</p>}
              </li>
            ))}
          </ul>
        </>
      )}

      {report.nextSteps?.length > 0 && (
        <>
          <h3>Próximos passos</h3>
          <ol className="ai-steps-list">
            {report.nextSteps.map((step, index) => <li key={index}>{step}</li>)}
          </ol>
        </>
      )}

      {report.hypotheses?.length > 0 && (
        <details className="ai-details">
          <summary>Hipóteses avaliadas ({report.hypotheses.length})</summary>
          <ul className="ai-hypotheses">
            {report.hypotheses.map((hypothesis, index) => {
              const status = HYPOTHESIS[hypothesis.status] ?? HYPOTHESIS.EM_ABERTO
              return (
                <li key={index}>
                  <StatusIcon status={status.status} size={15} /> <strong>{status.label}:</strong> {hypothesis.description}
                </li>
              )
            })}
          </ul>
        </details>
      )}

      {data.steps?.length > 0 && (
        <details className="ai-details">
          <summary>Como o agente chegou aqui ({data.steps.length} consultas)</summary>
          <ol className="ai-trace">
            {data.steps.map((step) => (
              <li key={step.order} className={step.error ? 'has-error' : ''}>
                <strong>{TOOL_LABEL[step.tool] ?? step.tool}</strong>
                <span className="mono muted">{step.arguments}</span>
                <span className="muted small">{step.summary} · {formatMs(step.durationMs)}</span>
              </li>
            ))}
          </ol>
        </details>
      )}

      <InvestigationMeta data={data} />
    </>
  )
}

function InvestigationMeta({ data }) {
  const duration = data.startedAt && data.finishedAt ? new Date(data.finishedAt) - new Date(data.startedAt) : null
  const cacheShare = data.inputTokens + data.cacheReadTokens > 0
    ? Math.round((data.cacheReadTokens / (data.inputTokens + data.cacheReadTokens + data.cacheWriteTokens)) * 100)
    : 0
  const guardrails = data.guardrailEvents ?? []
  return (
    <footer className="ai-meta">
      <span>{formatDateTime(data.finishedAt)}</span>
      {duration !== null && <span>{formatMs(duration)}</span>}
      {data.model && <span className="mono">{data.model}</span>}
      {data.fallbackUsed && <span className="badge badge-warning">Modelo reserva usado</span>}
      {data.mode === 'AI' && (
        <>
          <span>{tokens(data.inputTokens + data.cacheReadTokens + data.cacheWriteTokens)} tokens de entrada ({cacheShare}% do cache)</span>
          <span>{tokens(data.outputTokens)} de saída</span>
          <span>{Number(data.costUsd) > 0 ? usd(data.costUsd) : 'custo zero (modelo gratuito)'}</span>
        </>
      )}
      {data.error && <span className="muted">{data.error}</span>}
      {guardrails.length > 0 && (
        <details className="ai-guardrails">
          <summary>Guardrails acionados ({guardrails.length})</summary>
          <ul>
            {guardrails.map((event, index) => (
              <li key={index}><strong>{GUARDRAIL_LABEL[event.type] ?? event.type}</strong> <span className="muted">{event.detail}</span></li>
            ))}
          </ul>
        </details>
      )}
    </footer>
  )
}
