import { useState } from 'react'
import { api } from '../api.js'
import { useApi } from '../hooks/useApi.js'
import { useAuth, canWrite } from '../auth/useAuth.js'
import { ErrorState, Loading } from '../components/States.jsx'
import { formatMoney, formatNumber } from '../format.js'

const EMPTY = { name: '', description: '', unit: '', baselinePerMinute: '', valuePerUnit: '', services: [{ service: '', impactPercent: '100' }] }

const toForm = (kpi) => ({
  name: kpi.name,
  description: kpi.description ?? '',
  unit: kpi.unit,
  baselinePerMinute: String(kpi.baselinePerMinute),
  valuePerUnit: kpi.valuePerUnit === null ? '' : String(kpi.valuePerUnit),
  services: kpi.services.length
    ? kpi.services.map((s) => ({ service: s.service, impactPercent: String(s.impactPercent) }))
    : [{ service: '', impactPercent: '100' }],
})

const toRequest = (form) => ({
  name: form.name,
  description: form.description || null,
  unit: form.unit,
  baselinePerMinute: Number(form.baselinePerMinute),
  valuePerUnit: form.valuePerUnit === '' ? null : Number(form.valuePerUnit),
  services: form.services
    .filter((s) => s.service.trim())
    .map((s) => ({ service: s.service.trim(), impactPercent: Number(s.impactPercent) })),
})

/** NEG-01 / NEG-02: business KPIs and how much each service weighs on them. */
export default function BusinessKpis() {
  const { session } = useAuth()
  const editable = canWrite(session)
  const kpis = useApi('/business/kpis')
  const services = useApi('/telemetry/services')
  const [editing, setEditing] = useState(null) // null | 'new' | kpi id
  const [form, setForm] = useState(EMPTY)
  const [error, setError] = useState(null)
  const [saving, setSaving] = useState(false)

  function open(kpi) {
    setEditing(kpi ? kpi.id : 'new')
    setForm(kpi ? toForm(kpi) : EMPTY)
    setError(null)
  }

  async function save(event) {
    event.preventDefault()
    setSaving(true)
    setError(null)
    try {
      if (editing === 'new') await api.post('/business/kpis', toRequest(form))
      else await api.put(`/business/kpis/${editing}`, toRequest(form))
      setEditing(null)
      kpis.reload()
    } catch (e) {
      setError(e.message)
    } finally {
      setSaving(false)
    }
  }

  async function remove(kpi) {
    if (!window.confirm(`Excluir o KPI "${kpi.name}"? As estimativas de impacto deixam de usá-lo.`)) return
    try {
      await api.delete(`/business/kpis/${kpi.id}`)
      kpis.reload()
    } catch (e) {
      setError(e.message)
    }
  }

  const setField = (field) => (event) => setForm((current) => ({ ...current, [field]: event.target.value }))
  const setService = (index, field, value) =>
    setForm((current) => ({
      ...current,
      services: current.services.map((s, i) => (i === index ? { ...s, [field]: value } : s)),
    }))

  return (
    <>
      <header className="page-header">
        <div>
          <h1>KPIs de negócio</h1>
          <p className="muted">O que a operação significa em pedidos e receita: base do impacto de cada incidente</p>
        </div>
        {editable && editing === null && (
          <button type="button" className="btn btn-primary" onClick={() => open(null)}>Novo KPI</button>
        )}
      </header>

      {editing !== null && (
        <form className="card kpi-form" onSubmit={save}>
          <h2>{editing === 'new' ? 'Novo KPI' : 'Editar KPI'}</h2>
          <div className="form-grid">
            <label>
              Nome
              <input value={form.name} onChange={setField('name')} required maxLength={80} placeholder="Pedidos" />
            </label>
            <label>
              Unidade
              <input value={form.unit} onChange={setField('unit')} required maxLength={30} placeholder="pedidos" />
            </label>
            <label>
              Volume normal por minuto
              <input type="number" min="0" step="any" value={form.baselinePerMinute} onChange={setField('baselinePerMinute')} required placeholder="12" />
            </label>
            <label>
              Valor por unidade (R$, opcional)
              <input type="number" min="0" step="0.01" value={form.valuePerUnit} onChange={setField('valuePerUnit')} placeholder="180,00" />
            </label>
            <label className="form-grid-wide">
              Descrição
              <input value={form.description} onChange={setField('description')} maxLength={300} placeholder="Pedidos concluídos no checkout" />
            </label>
          </div>

          <fieldset className="kpi-services">
            <legend>Serviços que sustentam este KPI</legend>
            <p className="muted small">Peso: quanto do KPI se perde se o serviço parar totalmente (100% = o KPI zera sem ele).</p>
            <datalist id="known-services">
              {(services.data ?? []).map((s) => <option key={s.service} value={s.service} />)}
            </datalist>
            {form.services.map((row, index) => (
              <div key={index} className="kpi-service-row">
                <input list="known-services" value={row.service} onChange={(e) => setService(index, 'service', e.target.value)}
                  placeholder="checkout" aria-label="Serviço" maxLength={200} />
                <input type="number" min="1" max="100" step="any" value={row.impactPercent}
                  onChange={(e) => setService(index, 'impactPercent', e.target.value)} aria-label="Peso em %" />
                <span className="muted">%</span>
                <button type="button" className="link-button" aria-label="Remover serviço"
                  onClick={() => setForm((c) => ({ ...c, services: c.services.filter((_, i) => i !== index) }))}>Remover</button>
              </div>
            ))}
            <button type="button" className="btn btn-small"
              onClick={() => setForm((c) => ({ ...c, services: [...c.services, { service: '', impactPercent: '100' }] }))}>
              Adicionar serviço
            </button>
          </fieldset>

          {error && <p className="form-error" role="alert">{error}</p>}
          <div className="form-actions">
            <button type="button" className="btn" onClick={() => setEditing(null)}>Cancelar</button>
            <button className="btn btn-primary" disabled={saving}>{saving ? 'Salvando…' : 'Salvar'}</button>
          </div>
        </form>
      )}

      {kpis.error && <ErrorState error={kpis.error} onRetry={kpis.reload} />}
      {!kpis.data && !kpis.error && <Loading />}
      {kpis.data && kpis.data.length === 0 && editing === null && (
        <section className="card empty-state">
          <h2>Nenhum KPI cadastrado</h2>
          <p className="muted">
            Exemplo: <strong>Pedidos</strong>, 12 por minuto, R$ 180 cada, sustentado por <code className="mono">checkout</code> (100%)
            e <code className="mono">payment</code> (90%). A partir daí, cada incidente mostra quantos pedidos e quanto em reais estão em risco.
          </p>
        </section>
      )}
      {kpis.data && kpis.data.length > 0 && (
        <section className="card">
          <div className="table-scroll">
            <table className="table">
              <thead>
                <tr>
                  <th>KPI</th>
                  <th className="numeric">Volume normal</th>
                  <th className="numeric">Valor por unidade</th>
                  <th className="numeric">Valor por minuto</th>
                  <th>Serviços (peso)</th>
                  {editable && <th aria-label="Ações" />}
                </tr>
              </thead>
              <tbody>
                {kpis.data.map((kpi) => (
                  <tr key={kpi.id}>
                    <td>
                      <strong>{kpi.name}</strong>
                      {kpi.description && <div className="muted small">{kpi.description}</div>}
                    </td>
                    <td className="numeric">{formatNumber(kpi.baselinePerMinute)} {kpi.unit}/min</td>
                    <td className="numeric">{kpi.valuePerUnit === null ? '—' : formatMoney(kpi.valuePerUnit)}</td>
                    <td className="numeric">{kpi.valuePerUnit === null ? '—' : formatMoney(kpi.baselinePerMinute * kpi.valuePerUnit)}</td>
                    <td>
                      <div className="chips">
                        {kpi.services.map((s) => <span key={s.service} className="chip">{s.service} · {formatNumber(s.impactPercent)}%</span>)}
                        {kpi.services.length === 0 && <span className="muted small">nenhum</span>}
                      </div>
                    </td>
                    {editable && (
                      <td className="nowrap">
                        <button type="button" className="link-button" onClick={() => open(kpi)}>Editar</button>{' '}
                        <button type="button" className="link-button" onClick={() => remove(kpi)}>Excluir</button>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </>
  )
}
