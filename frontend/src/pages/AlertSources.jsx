import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api, publicApiUrl } from '../api.js'
import { useApi } from '../hooks/useApi.js'
import { useAuth, canWrite } from '../auth/useAuth.js'
import { Loading, ErrorState } from '../components/States.jsx'
import { SeverityBadge } from '../components/Badges.jsx'
import { CodeBlock, CopyButton } from '../components/Code.jsx'
import AlertSourceDialog from '../components/AlertSourceDialog.jsx'
import { formatDateTime } from '../format.js'

const DEFAULT_FORMAT_EXAMPLE = `{
  "title": "CPU alta no checkout-api",
  "dedupKey": "cpu-checkout",
  "status": "firing",
  "severity": "critical",
  "service": "checkout-api",
  "description": "CPU média acima de 90% por 5 minutos"
}`

/** Strips the leading /api that the backend includes in webhookPath. */
const webhookUrl = (path) => `${publicApiUrl()}${path.replace(/^\/api/, '')}`

export default function AlertSources() {
  const { session } = useAuth()
  const editable = canWrite(session)
  const sources = useApi('/alert-sources')
  const [editing, setEditing] = useState(null) // null = closed, {} = new, source = edit
  const [error, setError] = useState(null)

  async function remove(source) {
    if (!window.confirm(`Remover a fonte "${source.name}"? Alertas enviados para ela passarão a ser recusados.`)) return
    setError(null)
    try {
      await api.delete(`/alert-sources/${source.id}`)
      sources.reload()
    } catch (e) {
      setError(e.message)
    }
  }

  return (
    <>
      <header className="page-header">
        <div>
          <h1>Fontes de alerta</h1>
          <p className="muted">Receba alertas de qualquer ferramenta e abra incidentes automaticamente</p>
        </div>
        {editable && (
          <button type="button" className="btn btn-primary" onClick={() => setEditing({})}>
            Nova fonte
          </button>
        )}
      </header>

      {error && <p className="form-error" role="alert">{error}</p>}
      {sources.error && <ErrorState error={sources.error} onRetry={sources.reload} />}
      {!sources.data && !sources.error && <Loading />}
      {sources.data && (
        <section className={`card ${sources.loading ? 'is-refreshing' : ''}`}>
          <h2>Fontes configuradas</h2>
          {sources.data.length === 0 ? (
            <p className="empty">
              Nenhuma fonte configurada. Crie uma para traduzir o formato da sua ferramenta, ou use o formato padrão abaixo.
            </p>
          ) : (
            <div className="table-scroll">
              <table className="table">
                <thead>
                  <tr>
                    <th>Nome</th>
                    <th>Endereço do webhook</th>
                    <th>Severidade padrão</th>
                    <th>Atualizada</th>
                    {editable && <th aria-label="Ações" />}
                  </tr>
                </thead>
                <tbody>
                  {sources.data.map((source) => (
                    <tr key={source.id}>
                      <td>{source.name}</td>
                      <td>
                        <span className="url-cell">
                          <code className="mono">{source.webhookPath}</code>
                          <CopyButton text={webhookUrl(source.webhookPath)} label="Copiar URL" />
                        </span>
                      </td>
                      <td><SeverityBadge severity={source.defaultSeverity} /></td>
                      <td className="nowrap muted">{formatDateTime(source.updatedAt)}</td>
                      {editable && (
                        <td className="actions-cell">
                          <button type="button" className="link-button" onClick={() => setEditing(source)}>Editar</button>
                          <button type="button" className="link-button danger" onClick={() => remove(source)}>Remover</button>
                        </td>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}

      <section className="card">
        <h2>Como enviar alertas</h2>
        <ol className="steps">
          <li>
            Gere uma chave em <Link to="/settings/api-keys">Chaves de API</Link> (somente administradores).
          </li>
          <li>
            Na ferramenta de monitoramento, configure um webhook <strong>POST</strong> para o endereço da fonte com o
            cabeçalho <code className="mono">X-API-Key</code>.
          </li>
          <li>
            Alertas com o mesmo identificador atualizam o mesmo incidente, e um alerta resolvido encerra o incidente.
          </li>
        </ol>
        <div className="grid-2 no-margin">
          <CodeBlock title="Formato padrão do Argos (sem configurar fonte)" code={DEFAULT_FORMAT_EXAMPLE} />
          <CodeBlock
            title="Testar pelo terminal"
            code={`curl -X POST ${publicApiUrl()}/webhooks/alerts \\\n  -H "X-API-Key: <sua-chave>" \\\n  -H "Content-Type: application/json" \\\n  -d '{"title":"CPU alta","dedupKey":"cpu-1","status":"firing","severity":"critical","service":"checkout-api"}'`}
          />
        </div>
      </section>

      {editing && (
        <AlertSourceDialog
          source={editing.id ? editing : null}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null)
            sources.reload()
          }}
        />
      )}
    </>
  )
}
