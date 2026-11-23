import { useState } from 'react'
import { api } from '../api.js'
import { useApi } from '../hooks/useApi.js'
import { useAuth } from '../auth/useAuth.js'
import { Loading, ErrorState } from '../components/States.jsx'
import PageGuide from '../components/PageGuide.jsx'
import { ROLE_LABEL, formatDateTime, timeAgo } from '../format.js'

const ROLES = [
  { id: 'ADMIN', label: 'Administrador', desc: 'Acesso total: gerencia usuários, chaves de API, integrações e incidentes.' },
  { id: 'EDITOR', label: 'Editor', desc: 'Opera incidentes, configura alertas/regras SLO, canais e KPIs.' },
  { id: 'VIEWER', label: 'Leitor', desc: 'Visualiza incidentes, telemetria e métricas sem alterar configurações.' },
  { id: 'EXECUTIVE', label: 'Executivo', desc: 'Foco em dashboards executivos, impacto financeiro e métricas DORA.' },
]

export default function Users() {
  const { session } = useAuth()
  const users = useApi('/users')

  const [showForm, setShowForm] = useState(false)
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [role, setRole] = useState('EDITOR')
  const [search, setSearch] = useState('')
  const [saving, setSaving] = useState(false)
  const [feedback, setFeedback] = useState(null)

  const [resetTarget, setResetTarget] = useState(null)
  const [newPassword, setNewPassword] = useState('')

  if (session.role !== 'ADMIN') {
    return (
      <section className="card">
        <h1>Acesso restrito</h1>
        <p className="muted">Somente administradores podem gerenciar usuários e papéis da organização.</p>
      </section>
    )
  }

  async function createUser(e) {
    e.preventDefault()
    setSaving(true)
    setFeedback(null)
    try {
      await api.post('/users', { name: name.trim(), email: email.trim(), password, role })
      setName('')
      setEmail('')
      setPassword('')
      setRole('EDITOR')
      setShowForm(false)
      setFeedback({ ok: true, text: 'Usuário criado e habilitado na organização.' })
      users.reload()
    } catch (err) {
      setFeedback({ ok: false, text: err.message })
    } finally {
      setSaving(false)
    }
  }

  async function changeRole(user, nextRole) {
    setFeedback(null)
    try {
      await api.patch(`/users/${user.id}/role`, { role: nextRole })
      setFeedback({ ok: true, text: `Papel de ${user.name} alterado para ${ROLE_LABEL[nextRole]}.` })
      users.reload()
    } catch (err) {
      setFeedback({ ok: false, text: err.message })
    }
  }

  async function toggleStatus(user) {
    setFeedback(null)
    try {
      await api.patch(`/users/${user.id}/status`, { active: !user.active })
      setFeedback({
        ok: true,
        text: user.active ? `Acesso de ${user.name} desativado.` : `Acesso de ${user.name} reativado.`,
      })
      users.reload()
    } catch (err) {
      setFeedback({ ok: false, text: err.message })
    }
  }

  async function submitResetPassword(e) {
    e.preventDefault()
    if (!resetTarget) return
    setFeedback(null)
    try {
      await api.post(`/users/${resetTarget.id}/reset-password`, { password: newPassword })
      setFeedback({ ok: true, text: `Senha de ${resetTarget.name} atualizada.` })
      setResetTarget(null)
      setNewPassword('')
    } catch (err) {
      setFeedback({ ok: false, text: err.message })
    }
  }

  const list = (users.data ?? []).filter((u) => {
    const q = search.trim().toLowerCase()
    if (!q) return true
    return u.name.toLowerCase().includes(q) || u.email.toLowerCase().includes(q)
  })

  return (
    <>
      <header className="page-header">
        <div>
          <h1>Usuários e Acessos</h1>
          <p className="muted">Controle quem acessa o Argos e quais permissões cada membro possui na sua organização</p>
        </div>
        <button type="button" className="btn btn-primary" onClick={() => setShowForm(!showForm)}>
          {showForm ? 'Fechar formulário' : '+ Novo usuário'}
        </button>
      </header>

      <PageGuide
        id="users"
        summary="Aqui o Administrador gerencia todos os membros da organização com isolamento multi-tenant garantido no banco (RLS)."
        steps={[
          'Cadastre novos membros com um papel adequado ao perfil (Editor, Leitor, Executivo ou Admin).',
          'Altere permissões em tempo real diretamente na tabela conforme o papel na equipe.',
          'Desative imediatamente contas que não precisam mais de acesso ou redefina senhas temporárias.',
        ]}
      />

      {feedback && (
        <p
          role="status"
          className="card small"
          style={{
            padding: '10px 14px',
            borderLeft: `4px solid ${feedback.ok ? 'var(--ok)' : 'var(--alert)'}`,
            color: feedback.ok ? 'var(--ok-text)' : 'var(--alert-text)',
          }}
        >
          {feedback.text}
        </p>
      )}

      {showForm && (
        <section className="card">
          <h2>Adicionar usuário à organização</h2>
          <form onSubmit={createUser} style={{ display: 'grid', gap: 12 }}>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: 12 }}>
              <label className="field">
                <span>Nome completo</span>
                <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Ana Souza" required maxLength={120} />
              </label>
              <label className="field">
                <span>E-mail corporativo</span>
                <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="ana@empresa.com" required maxLength={200} />
              </label>
              <label className="field">
                <span>Senha inicial (mín. 6 caracteres)</span>
                <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} minLength={6} required maxLength={120} />
              </label>
              <label className="field">
                <span>Papel (RBAC)</span>
                <select value={role} onChange={(e) => setRole(e.target.value)}>
                  {ROLES.map((r) => (
                    <option key={r.id} value={r.id}>{r.label}</option>
                  ))}
                </select>
              </label>
            </div>
            <div className="form-actions">
              <button type="button" className="btn" onClick={() => setShowForm(false)}>Cancelar</button>
              <button className="btn btn-primary" disabled={saving}>{saving ? 'Criando…' : 'Criar usuário'}</button>
            </div>
          </form>
        </section>
      )}

      {resetTarget && (
        <section className="card">
          <h2>Redefinir senha de {resetTarget.name}</h2>
          <form onSubmit={submitResetPassword} style={{ display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'end' }}>
            <label className="field" style={{ flex: '1 1 240px' }}>
              <span>Nova senha (mín. 6 caracteres)</span>
              <input
                type="password"
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
                minLength={6}
                required
                placeholder="Digite a nova senha temporária"
              />
            </label>
            <button type="submit" className="btn btn-primary btn-small">Salvar senha</button>
            <button type="button" className="btn btn-small" onClick={() => { setResetTarget(null); setNewPassword('') }}>
              Cancelar
            </button>
          </form>
        </section>
      )}

      <section className="card">
        <div className="card-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 12 }}>
          <h2>Membros da organização ({list.length})</h2>
          <input
            type="search"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Filtrar por nome ou e-mail…"
            className="filter-input"
            style={{ minWidth: 0, width: '100%', maxWidth: 280 }}
          />
        </div>

        {users.error && <ErrorState error={users.error} onRetry={users.reload} />}
        {!users.data && !users.error && <Loading />}
        {users.data && (
          <div className="table-scroll">
            <table className="table">
              <thead>
                <tr>
                  <th>Usuário</th>
                  <th>Papel</th>
                  <th>Status</th>
                  <th>Último acesso</th>
                  <th>Ações</th>
                </tr>
              </thead>
              <tbody>
                {list.map((u) => (
                  <tr key={u.id}>
                    <td>
                      <strong>{u.name}</strong> {u.self && <span className="chip">Você</span>}
                      <div className="muted small">{u.email}</div>
                    </td>
                    <td>
                      <select
                        aria-label={`Papel de ${u.name}`}
                        value={u.role}
                        onChange={(e) => changeRole(u, e.target.value)}
                      >
                        {ROLES.map((r) => (
                          <option key={r.id} value={r.id}>{r.label}</option>
                        ))}
                      </select>
                    </td>
                    <td>
                      <span className={`badge status ${u.active ? 'status-RESOLVED' : 'status-OPEN'}`}>
                        <i aria-hidden="true" />
                        {u.active ? 'Ativo' : 'Desativado'}
                      </span>
                    </td>
                    <td className="muted small" title={formatDateTime(u.lastLoginAt)}>
                      {u.lastLoginAt ? timeAgo(u.lastLoginAt) : 'Nunca acessou'}
                    </td>
                    <td className="nowrap" style={{ display: 'flex', gap: 8 }}>
                      <button
                        type="button"
                        className="btn btn-small"
                        onClick={() => { setResetTarget(u); setNewPassword('') }}
                      >
                        Redefinir senha
                      </button>
                      {!u.self && (
                        <button type="button" className="btn btn-small" onClick={() => toggleStatus(u)}>
                          {u.active ? 'Desativar' : 'Ativar'}
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

      <section className="card">
        <h2>Permissões por papel (RBAC)</h2>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 12 }}>
          {ROLES.map((r) => (
            <div key={r.id} className="stat-tile">
              <strong style={{ marginBottom: 4 }}>{r.label}</strong>
              <span className="small muted">{r.desc}</span>
            </div>
          ))}
        </div>
      </section>
    </>
  )
}
