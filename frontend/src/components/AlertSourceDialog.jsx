import { useRef, useState } from 'react'
import { api } from '../api.js'
import { closeOnCancel, useModal } from '../hooks/useModal.js'
import { SEVERITIES, SEVERITY_LABEL } from '../format.js'

const EMPTY = {
  name: '',
  itemsPath: '',
  titlePath: '',
  dedupKeyPath: '',
  statusPath: '',
  severityPath: '',
  servicePath: '',
  descriptionPath: '',
  statusMap: {},
  severityMap: {},
  defaultSeverity: 'SEV3',
}

const PRESETS = {
  Grafana: {
    itemsPath: '$.alerts',
    titlePath: '$.annotations.summary',
    dedupKeyPath: '$.fingerprint',
    statusPath: '$.status',
    severityPath: '$.labels.severity',
    servicePath: '$.labels.service',
    descriptionPath: '$.annotations.description',
    statusMap: { firing: 'firing', resolved: 'resolved' },
    severityMap: { critical: 'SEV1', warning: 'SEV3' },
    defaultSeverity: 'SEV3',
  },
  Prometheus: {
    itemsPath: '$.alerts',
    titlePath: '$.labels.alertname',
    dedupKeyPath: '$.fingerprint',
    statusPath: '$.status',
    severityPath: '$.labels.severity',
    servicePath: '$.labels.service',
    descriptionPath: '$.annotations.description',
    statusMap: { firing: 'firing', resolved: 'resolved' },
    severityMap: { critical: 'SEV1', warning: 'SEV3', info: 'SEV4' },
    defaultSeverity: 'SEV3',
  },
}

const PATHS = [
  ['titlePath', 'Título do alerta', true],
  ['dedupKeyPath', 'Identificador único (deduplicação)', true],
  ['itemsPath', 'Lista de alertas (se vierem vários por chamada)'],
  ['statusPath', 'Status (disparado / resolvido)'],
  ['severityPath', 'Severidade'],
  ['servicePath', 'Serviço afetado'],
  ['descriptionPath', 'Descrição'],
]

const mapToText = (map) =>
  Object.entries(map ?? {})
    .map(([key, value]) => `${key}=${value}`)
    .join('\n')

/** "critical=SEV1" per line -> { critical: "SEV1" }; malformed lines are reported, not dropped. */
function textToMap(text, field) {
  const map = {}
  for (const raw of text.split('\n')) {
    const line = raw.trim()
    if (!line) continue
    const at = line.indexOf('=')
    if (at <= 0) throw new Error(`${field}: use uma linha "valor=RESULTADO" por item (linha "${line}")`)
    map[line.slice(0, at).trim()] = line.slice(at + 1).trim()
  }
  return map
}

export default function AlertSourceDialog({ source, onClose, onSaved }) {
  const dialog = useRef(null)
  useModal(dialog)
  const [form, setForm] = useState(() => ({ ...EMPTY, ...source }))
  const [maps, setMaps] = useState(() => ({
    statusMap: mapToText(source?.statusMap),
    severityMap: mapToText(source?.severityMap),
  }))
  const [error, setError] = useState(null)
  const [submitting, setSubmitting] = useState(false)

  const set = (field) => (event) => setForm((current) => ({ ...current, [field]: event.target.value }))

  function applyPreset(name) {
    const preset = PRESETS[name]
    setForm((current) => ({ ...current, ...preset, name: current.name || name }))
    setMaps({ statusMap: mapToText(preset.statusMap), severityMap: mapToText(preset.severityMap) })
  }

  async function submit(event) {
    event.preventDefault()
    setError(null)
    let body
    try {
      body = {
        ...Object.fromEntries(Object.keys(EMPTY).map((field) => [field, form[field]])),
        statusMap: textToMap(maps.statusMap, 'Tradução de status'),
        severityMap: textToMap(maps.severityMap, 'Tradução de severidade'),
      }
    } catch (e) {
      setError(e.message)
      return
    }
    setSubmitting(true)
    try {
      const saved = source ? await api.put(`/alert-sources/${source.id}`, body) : await api.post('/alert-sources', body)
      onSaved(saved)
    } catch (e) {
      setError(e.message)
      setSubmitting(false)
    }
  }

  return (
    <dialog ref={dialog} className="dialog dialog-wide" onCancel={closeOnCancel(onClose)}>
      <form onSubmit={submit}>
        <h2>{source ? `Editar ${source.name}` : 'Nova fonte de alerta'}</h2>
        {!source && (
          <p className="muted presets">
            Começar de um modelo:
            {Object.keys(PRESETS).map((name) => (
              <button key={name} type="button" className="btn btn-small" onClick={() => applyPreset(name)}>
                {name}
              </button>
            ))}
          </p>
        )}
        <label>
          Nome
          <input value={form.name} onChange={set('name')} required maxLength={100} />
        </label>

        <fieldset className="fieldset">
          <legend>Onde está cada campo no JSON recebido (JSONPath)</legend>
          <div className="form-grid">
            {PATHS.map(([field, label, required]) => (
              <label key={field}>
                {label}
                {required && <span className="required"> *</span>}
                <input
                  value={form[field] ?? ''}
                  onChange={set(field)}
                  required={required}
                  maxLength={200}
                  placeholder={required ? '$.campo' : 'opcional'}
                  className="mono"
                />
              </label>
            ))}
          </div>
        </fieldset>

        <div className="form-grid">
          <label>
            Tradução de status <small className="muted">(valor=firing ou resolved)</small>
            <textarea
              rows={3}
              className="mono"
              value={maps.statusMap}
              onChange={(e) => setMaps((m) => ({ ...m, statusMap: e.target.value }))}
              placeholder={'alerting=firing\nok=resolved'}
            />
          </label>
          <label>
            Tradução de severidade <small className="muted">(valor=SEV1…SEV4)</small>
            <textarea
              rows={3}
              className="mono"
              value={maps.severityMap}
              onChange={(e) => setMaps((m) => ({ ...m, severityMap: e.target.value }))}
              placeholder={'critical=SEV1\nwarning=SEV3'}
            />
          </label>
        </div>
        <label>
          Severidade quando o alerta não informa
          <select value={form.defaultSeverity} onChange={set('defaultSeverity')}>
            {SEVERITIES.map((severity) => (
              <option key={severity} value={severity}>{SEVERITY_LABEL[severity]}</option>
            ))}
          </select>
        </label>

        {error && <p className="form-error" role="alert">{error}</p>}
        <div className="dialog-actions">
          <button type="button" className="btn" onClick={onClose}>Cancelar</button>
          <button className="btn btn-primary" disabled={submitting}>{submitting ? 'Salvando…' : 'Salvar'}</button>
        </div>
      </form>
    </dialog>
  )
}
