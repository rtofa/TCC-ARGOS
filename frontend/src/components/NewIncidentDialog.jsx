import { useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api.js'
import { closeOnCancel, useModal } from '../hooks/useModal.js'
import { SEVERITIES, SEVERITY_LABEL } from '../format.js'

export default function NewIncidentDialog({ onClose }) {
  const dialog = useRef(null)
  useModal(dialog)
  const navigate = useNavigate()
  const [error, setError] = useState(null)
  const [submitting, setSubmitting] = useState(false)

  async function submit(event) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    const services = form
      .get('services')
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean)
    setSubmitting(true)
    setError(null)
    try {
      const created = await api.post('/incidents', {
        title: form.get('title'),
        description: form.get('description') || null,
        severity: form.get('severity'),
        affectedServices: services,
      })
      navigate(`/incidents/${created.id}`)
    } catch (e) {
      setError(e.message)
      setSubmitting(false)
    }
  }

  return (
    <dialog ref={dialog} className="dialog" onCancel={closeOnCancel(onClose)}>
      <form onSubmit={submit}>
        <h2>Novo incidente</h2>
        <label>
          Título
          <input name="title" required maxLength={200} autoFocus />
        </label>
        <label>
          Severidade
          <select name="severity" defaultValue="SEV3">
            {SEVERITIES.map((severity) => (
              <option key={severity} value={severity}>{SEVERITY_LABEL[severity]}</option>
            ))}
          </select>
        </label>
        <label>
          Serviços afetados <small className="muted">(separe por vírgula)</small>
          <input name="services" placeholder="checkout-api, orders-db" />
        </label>
        <label>
          Descrição
          <textarea name="description" rows={4} maxLength={10000} />
        </label>
        {error && <p className="form-error" role="alert">{error}</p>}
        <div className="dialog-actions">
          <button type="button" className="btn" onClick={onClose}>Cancelar</button>
          <button className="btn btn-primary" disabled={submitting}>{submitting ? 'Criando…' : 'Criar incidente'}</button>
        </div>
      </form>
    </dialog>
  )
}
