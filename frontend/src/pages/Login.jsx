import { useState } from 'react'
import { Navigate } from 'react-router-dom'
import { useAuth } from '../auth/useAuth.js'
import { Logo } from '../components/Logo.jsx'

export default function Login() {
  const { status, login, register } = useAuth()
  const [mode, setMode] = useState('login')
  const [error, setError] = useState(null)
  const [submitting, setSubmitting] = useState(false)

  if (status === 'authenticated') return <Navigate to="/" replace />

  async function submit(event) {
    event.preventDefault()
    const form = Object.fromEntries(new FormData(event.currentTarget))
    setSubmitting(true)
    setError(null)
    try {
      if (mode === 'login') await login(form.email, form.password)
      else await register(form)
    } catch (e) {
      setError(e.message)
      setSubmitting(false)
    }
  }

  const isLogin = mode === 'login'

  return (
    <div className="auth-page">
      <form className="auth-card" onSubmit={submit}>
        <Logo large />
        <p className="auth-tagline">Observabilidade que fala a língua do negócio</p>
        <h1>{isLogin ? 'Entrar na sua conta' : 'Criar sua organização'}</h1>

        {!isLogin && (
          <>
            <label>
              Nome da organização
              <input name="organizationName" required maxLength={120} autoComplete="organization" />
            </label>
            <label>
              Seu nome
              <input name="userName" required maxLength={120} autoComplete="name" />
            </label>
          </>
        )}
        <label>
          E-mail
          <input name="email" type="email" required autoComplete="email" />
        </label>
        <label>
          Senha
          <input name="password" type="password" required minLength={isLogin ? undefined : 8} autoComplete={isLogin ? 'current-password' : 'new-password'} />
        </label>

        {error && <p className="form-error" role="alert">{error}</p>}

        <button className="btn btn-primary btn-block" disabled={submitting}>
          {submitting ? 'Aguarde…' : isLogin ? 'Entrar' : 'Criar conta'}
        </button>
        <button
          type="button"
          className="link-button"
          onClick={() => {
            setMode(isLogin ? 'register' : 'login')
            setError(null)
          }}
        >
          {isLogin ? 'Primeira vez aqui? Criar conta' : 'Já tenho conta'}
        </button>
      </form>
    </div>
  )
}
