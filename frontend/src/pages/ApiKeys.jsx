import { useState } from 'react'
import { api, publicApiUrl } from '../api.js'
import { useApi } from '../hooks/useApi.js'
import { useAuth } from '../auth/useAuth.js'
import { Loading, ErrorState } from '../components/States.jsx'
import { CodeBlock, CopyButton } from '../components/Code.jsx'
import { formatDateTime, timeAgo } from '../format.js'

function NewKeyRevealed({ created, onDone }) {
  return (
    <section className="card reveal" role="status">
      <h2>Chave criada: {created.name}</h2>
      <p>
        <strong>Copie agora.</strong> Por segurança, o Argos guarda só um resumo da chave e não consegue mostrá-la de
        novo.
      </p>
      <div className="reveal-key">
        <code>{created.key}</code>
        <CopyButton text={created.key} />
      </div>
      <CodeBlock
        title="Teste a chave"
        code={`curl ${publicApiUrl()}/webhooks/ping \\\n  -H "X-API-Key: ${created.key}"`}
      />
      <div className="dialog-actions">
        <button type="button" className="btn btn-primary" onClick={onDone}>Já copiei</button>
      </div>
    </section>
  )
}

export default function ApiKeys() {
  const { session } = useAuth()
  const isAdmin = session.role === 'ADMIN'
  const keys = useApi(isAdmin ? '/api-keys' : null)
  const [name, setName] = useState('')
  const [created, setCreated] = useState(null)
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  async function run(action) {
    setBusy(true)
    setError(null)
    try {
      await action()
      keys.reload()
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  const create = (event) => {
    event.preventDefault()
    run(async () => {
      setCreated(await api.post('/api-keys', { name }))
      setName('')
    })
  }

  const revoke = (key) => {
    if (!window.confirm(`Revogar a chave "${key.name}"? Os sistemas que a usam deixarão de enviar dados.`)) return
    run(() => api.post(`/api-keys/${key.id}/revoke`))
  }

  return (
    <>
      <header className="page-header">
        <div>
          <h1>Chaves de API</h1>
          <p className="muted">Usadas por ferramentas externas para enviar alertas e deploys ao Argos</p>
        </div>
      </header>

      {!isAdmin ? (
        <section className="card">
          <p className="empty">Somente administradores gerenciam chaves de API.</p>
        </section>
      ) : (
        <>
          {created && <NewKeyRevealed created={created} onDone={() => setCreated(null)} />}

          <section className="card">
            <form className="inline-form" onSubmit={create}>
              <label>
                Nova chave
                <input
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder="Ex.: Grafana produção, GitHub Actions"
                  maxLength={100}
                  required
                />
              </label>
              <button className="btn btn-primary" disabled={busy || !name.trim()}>Gerar chave</button>
            </form>
            {error && <p className="form-error" role="alert">{error}</p>}
          </section>

          {keys.error && <ErrorState error={keys.error} onRetry={keys.reload} />}
          {!keys.data && !keys.error && <Loading />}
          {keys.data && (
            <section className={`card ${keys.loading ? 'is-refreshing' : ''}`}>
              {keys.data.length === 0 ? (
                <p className="empty">Nenhuma chave ainda. Gere uma para conectar sua primeira ferramenta.</p>
              ) : (
                <div className="table-scroll">
                  <table className="table">
                    <thead>
                      <tr>
                        <th>Nome</th>
                        <th>Chave</th>
                        <th>Status</th>
                        <th>Criada</th>
                        <th>Último uso</th>
                        <th aria-label="Ações" />
                      </tr>
                    </thead>
                    <tbody>
                      {keys.data.map((key) => (
                        <tr key={key.id}>
                          <td>{key.name}</td>
                          <td><code className="mono">{key.prefix}…</code></td>
                          <td>
                            {key.status === 'ACTIVE' ? (
                              <span className="badge status status-RESOLVED"><i aria-hidden="true" />Ativa</span>
                            ) : (
                              <span className="badge" title={`Revogada em ${formatDateTime(key.revokedAt)}`}>Revogada</span>
                            )}
                          </td>
                          <td className="nowrap muted">{formatDateTime(key.createdAt)}</td>
                          <td className="nowrap muted">{key.lastUsedAt ? timeAgo(key.lastUsedAt) : 'nunca'}</td>
                          <td className="actions-cell">
                            {key.status === 'ACTIVE' && (
                              <button type="button" className="link-button danger" disabled={busy} onClick={() => revoke(key)}>
                                Revogar
                              </button>
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </section>
          )}
        </>
      )}
    </>
  )
}
