import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { publicApiUrl, query } from '../api.js'
import { useApi } from '../hooks/useApi.js'
import { Loading, ErrorState } from '../components/States.jsx'
import { CodeBlock } from '../components/Code.jsx'
import { CommitLink, EnvironmentBadge } from '../components/DeployList.jsx'
import { formatDateTime, timeAgo } from '../format.js'

const PERIOD_HOURS = { '24h': 24, '7d': 24 * 7, '30d': 24 * 30 }
const PAGE_SIZE = 20

const currentMinute = () => Math.floor(Date.now() / 60000) * 60000

const githubActionsStep = () => `- name: Quality Gate no Argos (bloqueia se houver SEV1/alerta crítico)
  run: |
    curl -sf "\${{ vars.ARGOS_API_URL }}/webhooks/deploys/quality-gate?service=checkout-api&strict=true" \\
      -H "X-API-Key: \${{ secrets.ARGOS_API_KEY }}"

- name: Registrar deploy no Argos
  if: success()
  run: |
    curl -sf -X POST "\${{ vars.ARGOS_API_URL }}/webhooks/deploys" \\
      -H "X-API-Key: \${{ secrets.ARGOS_API_KEY }}" \\
      -H "Content-Type: application/json" \\
      -d '{
        "service": "checkout-api",
        "version": "\${{ github.ref_name }}",
        "environment": "production",
        "commitSha": "\${{ github.sha }}",
        "author": "\${{ github.actor }}",
        "url": "\${{ github.server_url }}/\${{ github.repository }}/actions/runs/\${{ github.run_id }}"
      }'`

const curlExample = () => `# 1. Quality Gate antes do deploy (retorna HTTP 409 se bloqueado)
curl -f "${publicApiUrl()}/webhooks/deploys/quality-gate?service=checkout-api&strict=true" \\
  -H "X-API-Key: <sua-chave>"

# 2. Notificar deploy concluído
curl -X POST ${publicApiUrl()}/webhooks/deploys \\
  -H "X-API-Key: <sua-chave>" \\
  -H "Content-Type: application/json" \\
  -d '{"service":"checkout-api","version":"2.3.0","environment":"production"}'`

function QualityGateBanner({ gate }) {
  if (!gate) return null
  const blocked = !gate.allowed
  return (
    <section
      className="card"
      aria-label="Quality Gate de Deploy"
      style={{
        borderLeft: `4px solid ${blocked ? '#dc2626' : '#16a34a'}`,
        background: blocked ? 'rgba(220, 38, 38, 0.06)' : 'rgba(22, 163, 74, 0.06)',
      }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 12 }}>
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <span
              className="badge"
              style={{
                background: blocked ? '#dc2626' : '#16a34a',
                color: '#fff',
                fontWeight: 700,
              }}
            >
              QUALITY GATE: {blocked ? 'BLOQUEADO' : 'LIBERADO'}
            </span>
            {gate.service && <span className="chip">{gate.service}</span>}
          </div>
          <p className="small" style={{ marginTop: 6, marginBottom: 0 }}>
            {gate.reason}
          </p>
        </div>
        <div style={{ display: 'flex', gap: 16, fontSize: 13 }}>
          <span><strong>{gate.openSev1Incidents}</strong> SEV1 aberto(s)</span>
          <span><strong>{gate.openCriticalAlerts}</strong> alerta(s) crítico(s)</span>
        </div>
      </div>
      {blocked && gate.blockingIncidents?.length > 0 && (
        <ul className="plain-list" style={{ marginTop: 10, display: 'grid', gap: 6 }}>
          {gate.blockingIncidents.map((inc) => (
            <li key={inc.id} style={{ fontSize: 13 }}>
              <Link to={`/incidents/${inc.id}`}>
                <strong>[{inc.severity}] {inc.title}</strong>
              </Link>{' '}
              <span className="muted">({inc.status} · {inc.source})</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function Instructions() {
  return (
    <section className="card">
      <h2>Como registrar deploys</h2>
      <p>
        Adicione um passo no fim do pipeline que avisa o Argos a cada publicação. Use uma chave de{' '}
        <Link to="/settings/api-keys">Chaves de API</Link> guardada como segredo{' '}
        <code className="mono">ARGOS_API_KEY</code> e o endereço da API na variável{' '}
        <code className="mono">ARGOS_API_URL</code> do repositório.
      </p>
      <div className="grid-2 no-margin">
        <CodeBlock title="GitHub Actions" code={githubActionsStep()} />
        <CodeBlock title="Qualquer pipeline (curl)" code={curlExample()} />
      </div>
    </section>
  )
}

export default function Deploys() {
  const [params, setParams] = useSearchParams()
  const [now, setNow] = useState(currentMinute)
  const [serviceInput, setServiceInput] = useState(params.get('service') ?? '')

  const service = params.get('service') ?? ''
  const environment = params.get('environment') ?? ''
  const period = params.get('period') ?? ''
  const page = Number(params.get('page') ?? 0)

  function update(changes) {
    const next = new URLSearchParams(params)
    for (const [key, value] of Object.entries({ page: '', ...changes })) {
      if (value === '' || value === undefined) next.delete(key)
      else next.set(key, value)
    }
    setNow(currentMinute())
    setParams(next, { replace: true })
  }

  const from = PERIOD_HOURS[period] ? new Date(now - PERIOD_HOURS[period] * 3600e3).toISOString() : undefined
  const deploys = useApi(`/deploys${query({ service, environment, from, page, size: PAGE_SIZE })}`)
  const qualityGate = useApi(`/deploys/quality-gate${query({ service })}`)
  const filtered = service || environment || period
  const data = deploys.data

  return (
    <>
      <header className="page-header">
        <div>
          <h1>Deploys</h1>
          <p className="muted">O que mudou, onde e quando: a primeira pergunta durante um incidente</p>
        </div>
      </header>

      <QualityGateBanner gate={qualityGate.data} />

      <section className="card filters" aria-label="Filtros">
        <form
          className="filter-group"
          onSubmit={(e) => {
            e.preventDefault()
            update({ service: serviceInput.trim() })
          }}
        >
          <span className="filter-label">Serviço</span>
          <input value={serviceInput} onChange={(e) => setServiceInput(e.target.value)} placeholder="checkout-api" className="filter-input" />
          <button className="btn btn-small">Filtrar</button>
        </form>
        <label className="filter-group">
          <span className="filter-label">Ambiente</span>
          <select value={environment} onChange={(e) => update({ environment: e.target.value })}>
            <option value="">Todos</option>
            <option value="production">production</option>
            <option value="staging">staging</option>
            <option value="development">development</option>
          </select>
        </label>
        <label className="filter-group">
          <span className="filter-label">Período</span>
          <select value={period} onChange={(e) => update({ period: e.target.value })}>
            <option value="">Qualquer data</option>
            <option value="24h">Últimas 24 h</option>
            <option value="7d">Últimos 7 dias</option>
            <option value="30d">Últimos 30 dias</option>
          </select>
        </label>
        {filtered && (
          <button
            type="button"
            className="link-button"
            onClick={() => {
              setServiceInput('')
              update({ service: '', environment: '', period: '' })
            }}
          >
            Limpar filtros
          </button>
        )}
      </section>

      {deploys.error && <ErrorState error={deploys.error} onRetry={deploys.reload} />}
      {!data && !deploys.error && <Loading />}
      {data && (
        <section className={`card ${deploys.loading ? 'is-refreshing' : ''}`}>
          {data.content.length === 0 ? (
            <p className="empty">
              {filtered ? 'Nenhum deploy com esses filtros.' : 'Nenhum deploy registrado ainda. Veja abaixo como conectar seu pipeline.'}
            </p>
          ) : (
            <div className="table-scroll">
              <table className="table">
                <thead>
                  <tr>
                    <th>Quando</th>
                    <th>Serviço</th>
                    <th>Versão</th>
                    <th>Ambiente</th>
                    <th>Commit</th>
                    <th>Autor</th>
                  </tr>
                </thead>
                <tbody>
                  {data.content.map((deploy) => (
                    <tr key={deploy.id}>
                      <td className="nowrap" title={formatDateTime(deploy.deployedAt)}>{timeAgo(deploy.deployedAt)}</td>
                      <td><span className="chip">{deploy.service}</span></td>
                      <td>
                        <code className="mono">{deploy.version}</code>
                        {deploy.description && <p className="muted small no-margin">{deploy.description}</p>}
                      </td>
                      <td><EnvironmentBadge environment={deploy.environment} /></td>
                      <td><CommitLink deploy={deploy} /></td>
                      <td className="muted">{deploy.author ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {data.totalPages > 1 && (
            <footer className="pagination">
              <span className="muted">{data.totalElements} deploys</span>
              <span>
                <button type="button" className="btn" disabled={page <= 0} onClick={() => update({ service, environment, period, page: String(page - 1) })}>
                  Anterior
                </button>
                <span className="page-indicator">{page + 1} / {data.totalPages}</span>
                <button type="button" className="btn" disabled={page + 1 >= data.totalPages} onClick={() => update({ service, environment, period, page: String(page + 1) })}>
                  Próxima
                </button>
              </span>
            </footer>
          )}
        </section>
      )}

      <Instructions />
    </>
  )
}
