import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api, publicApiUrl } from '../api.js'
import { useApi } from '../hooks/useApi.js'
import { useAuth, canWrite } from '../auth/useAuth.js'
import { Loading, ErrorState } from '../components/States.jsx'
import { SeverityBadge } from '../components/Badges.jsx'
import { CodeBlock } from '../components/Code.jsx'
import PageGuide from '../components/PageGuide.jsx'
import { SEVERITIES, formatDateTime, timeAgo } from '../format.js'

const CHANNEL_TYPES = [
  { id: 'SLACK', label: 'Slack (Webhook)', placeholder: 'https://hooks.slack.com/services/T000/B000/...' },
  { id: 'DISCORD', label: 'Discord (Webhook)', placeholder: 'https://discord.com/api/webhooks/...' },
  { id: 'EMAIL', label: 'E-mail (SMTP)', placeholder: 'plantao@empresa.com.br, sre@empresa.com.br' },
]

const CATEGORIES = [
  { id: 'all', label: 'Todas' },
  { id: 'notifications', label: 'Notificações' },
  { id: 'telemetry', label: 'Telemetria' },
  { id: 'alerting', label: 'Alertas & SLO' },
  { id: 'delivery', label: 'CI/CD' },
  { id: 'business', label: 'Negócio' },
]

const otelYamlSnippet = () => `exporters:
  otlphttp/argos:
    endpoint: ${publicApiUrl()}/webhooks/otlp
    encoding: json
    compression: none
    headers:
      X-API-Key: "<sua-chave-de-api>"`

function NotificationChannelsSection({ editable, selectedType, onTypeChange, channels, onChanged }) {
  const [editingId, setEditingId] = useState(null)
  const [name, setName] = useState('')
  const [target, setTarget] = useState('')
  const [minSeverity, setMinSeverity] = useState('SEV3')
  const [enabled, setEnabled] = useState(true)
  const [saving, setSaving] = useState(false)
  const [feedback, setFeedback] = useState(null)
  const [testingId, setTestingId] = useState(null)

  const currentTypeMeta = CHANNEL_TYPES.find((t) => t.id === selectedType) ?? CHANNEL_TYPES[0]

  function resetForm() {
    setEditingId(null)
    setName('')
    setTarget('')
    setMinSeverity('SEV3')
    setEnabled(true)
  }

  function startEdit(ch) {
    setEditingId(ch.id)
    onTypeChange(ch.type)
    setName(ch.name)
    setTarget('')
    setMinSeverity(ch.minSeverity)
    setEnabled(ch.enabled)
    setFeedback(null)
  }

  async function submitChannel(e) {
    e.preventDefault()
    setSaving(true)
    setFeedback(null)
    try {
      const payload = {
        name: name.trim(),
        type: selectedType,
        target: target.trim() || null,
        minSeverity,
        enabled,
      }
      if (editingId) {
        await api.put(`/notifications/channels/${editingId}`, payload)
        setFeedback({ ok: true, text: 'Canal atualizado com sucesso.' })
      } else {
        await api.post('/notifications/channels', payload)
        setFeedback({ ok: true, text: 'Canal de notificação criado com sucesso.' })
      }
      resetForm()
      onChanged()
    } catch (err) {
      setFeedback({ ok: false, text: err.message })
    } finally {
      setSaving(false)
    }
  }

  async function toggleEnabled(ch) {
    setFeedback(null)
    try {
      await api.put(`/notifications/channels/${ch.id}`, {
        name: ch.name,
        type: ch.type,
        target: null,
        minSeverity: ch.minSeverity,
        enabled: !ch.enabled,
      })
      onChanged()
    } catch (err) {
      setFeedback({ ok: false, text: err.message })
    }
  }

  async function testChannel(ch) {
    setTestingId(ch.id)
    setFeedback(null)
    try {
      const res = await api.post(`/notifications/channels/${ch.id}/test`)
      setFeedback({ ok: res.delivered, text: `[${ch.name}] ${res.message}` })
      onChanged()
    } catch (err) {
      setFeedback({ ok: false, text: err.message })
    } finally {
      setTestingId(null)
    }
  }

  async function deleteChannel(ch) {
    setFeedback(null)
    try {
      await api.delete(`/notifications/channels/${ch.id}`)
      if (editingId === ch.id) resetForm()
      onChanged()
    } catch (err) {
      setFeedback({ ok: false, text: err.message })
    }
  }

  return (
    <section id="notification-channels" className="card">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', flexWrap: 'wrap', gap: 12 }}>
        <div>
          <h2>Canais de notificação de incidentes (Slack, Discord e E-mail)</h2>
          <p className="muted small">
            Sempre que um incidente é aberto (manual, por alerta ou SLO OTLP), o Argos notifica os canais ativos cuja severidade mínima for atingida.
          </p>
        </div>
      </div>

      {feedback && (
        <p
          role="status"
          className="small"
          style={{
            padding: '8px 12px',
            borderRadius: 6,
            background: feedback.ok ? 'rgba(22, 163, 74, 0.1)' : 'rgba(220, 38, 38, 0.1)',
            color: feedback.ok ? '#15803d' : '#b91c1c',
          }}
        >
          {feedback.text}
        </p>
      )}

      {editable && (
        <form onSubmit={submitChannel} style={{ display: 'grid', gap: 12, marginTop: 12, marginBottom: 18 }}>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 10 }}>
            <label className="field">
              <span>Tipo</span>
              <select value={selectedType} onChange={(e) => onTypeChange(e.target.value)}>
                {CHANNEL_TYPES.map((t) => (
                  <option key={t.id} value={t.id}>{t.label}</option>
                ))}
              </select>
            </label>

            <label className="field">
              <span>Nome do canal</span>
              <input
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder={selectedType === 'EMAIL' ? 'Plantão SRE' : `Canal ${selectedType}`}
                required
                maxLength={100}
              />
            </label>

            <label className="field">
              <span>Severidade mínima</span>
              <select value={minSeverity} onChange={(e) => setMinSeverity(e.target.value)}>
                {SEVERITIES.map((s) => (
                  <option key={s} value={s}>{s} ou maior</option>
                ))}
              </select>
            </label>
          </div>

          <label className="field">
            <span>
              {selectedType === 'EMAIL' ? 'Destinatários (separados por vírgula)' : 'URL do Webhook'}
              {editingId && selectedType !== 'EMAIL' ? ' (deixe vazio para manter a URL atual)' : ''}
            </span>
            <input
              value={target}
              onChange={(e) => setTarget(e.target.value)}
              placeholder={currentTypeMeta.placeholder}
              required={!editingId}
              maxLength={500}
            />
          </label>

          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 10 }}>
            <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13 }}>
              <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
              Canal ativo para notificações automáticas
            </label>

            <div style={{ display: 'flex', gap: 8 }}>
              {editingId && (
                <button type="button" className="btn btn-small" onClick={resetForm}>
                  Cancelar edição
                </button>
              )}
              <button className="btn btn-primary btn-small" disabled={saving}>
                {saving ? 'Salvando…' : editingId ? 'Salvar canal' : 'Adicionar canal'}
              </button>
            </div>
          </div>
        </form>
      )}

      {channels.error && <ErrorState error={channels.error} onRetry={channels.reload} />}
      {!channels.data && !channels.error && <Loading />}
      {channels.data && (
        channels.data.length === 0 ? (
          <p className="empty">Nenhum canal de notificação configurado ainda.</p>
        ) : (
          <div className="table-scroll">
            <table className="table">
              <thead>
                <tr>
                  <th>Canal</th>
                  <th>Tipo</th>
                  <th>Destino</th>
                  <th>Severidade</th>
                  <th>Último envio</th>
                  <th>Status</th>
                  {editable && <th>Ações</th>}
                </tr>
              </thead>
              <tbody>
                {channels.data.map((ch) => (
                  <tr key={ch.id}>
                    <td><strong>{ch.name}</strong></td>
                    <td><span className="chip">{ch.type}</span></td>
                    <td><code className="mono small">{ch.targetPreview}</code></td>
                    <td><SeverityBadge severity={ch.minSeverity} /></td>
                    <td className="small muted" title={ch.lastError ?? formatDateTime(ch.lastNotifiedAt)}>
                      {ch.lastError
                        ? <span style={{ color: '#b91c1c' }}>Erro: {ch.lastError}</span>
                        : ch.lastNotifiedAt ? timeAgo(ch.lastNotifiedAt) : 'Nunca'}
                    </td>
                    <td>
                      <span className={`badge status ${ch.enabled ? 'status-RESOLVED' : 'status-MITIGATED'}`}>
                        <i aria-hidden="true" />
                        {ch.enabled ? 'Ativo' : 'Pausado'}
                      </span>
                    </td>
                    {editable && (
                      <td className="nowrap" style={{ display: 'flex', gap: 6 }}>
                        <button
                          type="button"
                          className="btn btn-small"
                          disabled={testingId === ch.id}
                          onClick={() => testChannel(ch)}
                        >
                          {testingId === ch.id ? 'Testando…' : 'Testar'}
                        </button>
                        <button type="button" className="btn btn-small" onClick={() => toggleEnabled(ch)}>
                          {ch.enabled ? 'Pausar' : 'Ativar'}
                        </button>
                        <button type="button" className="btn btn-small" onClick={() => startEdit(ch)}>
                          Editar
                        </button>
                        <button type="button" className="link-button" onClick={() => deleteChannel(ch)}>
                          Remover
                        </button>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )
      )}
    </section>
  )
}

export default function Integrations() {
  const { session } = useAuth()
  const editable = canWrite(session.role)
  const isAdmin = session.role === 'ADMIN'

  const [category, setCategory] = useState('all')
  const [selectedChannelType, setSelectedChannelType] = useState('SLACK')
  const [showOtelSnippet, setShowOtelSnippet] = useState(false)

  const channels = useApi('/notifications/channels')
  const sources = useApi('/alert-sources')
  const rules = useApi('/telemetry/rules')
  const services = useApi('/telemetry/services')
  const deploys = useApi('/deploys?size=1')
  const kpis = useApi('/business/kpis')

  const channelList = channels.data ?? []
  const countByType = (t) => channelList.filter((c) => c.type === t && c.enabled).length
  const activeSources = (sources.data ?? []).length
  const activeRules = (rules.data ?? []).filter((r) => r.enabled).length
  const activeServices = (services.data ?? []).length
  const deployCount = deploys.data?.totalElements ?? 0
  const kpiCount = (kpis.data ?? []).length

  function focusChannelForm(type) {
    setSelectedChannelType(type)
    const el = document.getElementById('notification-channels')
    if (el) el.scrollIntoView({ behavior: 'smooth' })
  }

  const catalog = [
    {
      id: 'slack',
      category: 'notifications',
      name: 'Slack',
      badge: 'Notificação',
      configured: countByType('SLACK') > 0,
      statusText: countByType('SLACK') > 0 ? `${countByType('SLACK')} canal(is) ativo(s)` : 'Não configurado',
      description: 'Envia alertas automáticos via Incoming Webhook com severidade, serviços afetados e link direto para o incidente.',
      action: <button type="button" className="btn btn-small btn-primary" onClick={() => focusChannelForm('SLACK')}>Configurar Slack</button>,
    },
    {
      id: 'discord',
      category: 'notifications',
      name: 'Discord',
      badge: 'Notificação',
      configured: countByType('DISCORD') > 0,
      statusText: countByType('DISCORD') > 0 ? `${countByType('DISCORD')} canal(is) ativo(s)` : 'Não configurado',
      description: 'Publica embeds coloridos por severidade em canais de War Room no Discord no momento da abertura do incidente.',
      action: <button type="button" className="btn btn-small btn-primary" onClick={() => focusChannelForm('DISCORD')}>Configurar Discord</button>,
    },
    {
      id: 'email',
      category: 'notifications',
      name: 'E-mail (SMTP)',
      badge: 'Notificação',
      configured: countByType('EMAIL') > 0,
      statusText: countByType('EMAIL') > 0 ? `${countByType('EMAIL')} canal(is) ativo(s)` : 'Não configurado',
      description: 'Dispara avisos de incidentes por e-mail para listas de plantão e SRE usando o servidor SMTP configurado.',
      action: <button type="button" className="btn btn-small btn-primary" onClick={() => focusChannelForm('EMAIL')}>Configurar E-mail</button>,
    },
    {
      id: 'otel',
      category: 'telemetry',
      name: 'OpenTelemetry (OTLP/HTTP)',
      badge: 'Telemetria',
      configured: activeServices > 0,
      statusText: activeServices > 0 ? `${activeServices} serviço(s) enviando` : 'Aguardando traces/logs',
      description: 'Ingestão nativa de traces, logs e métricas com mascaramento LGPD (SEC-04), Topologia automática e gráficos RED.',
      action: (
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <button type="button" className="btn btn-small" onClick={() => setShowOtelSnippet(!showOtelSnippet)}>
            {showOtelSnippet ? 'Ocultar YAML' : 'Ver config OTel'}
          </button>
          <Link to="/telemetry" className="btn btn-small">Abrir Telemetria</Link>
        </div>
      ),
    },
    {
      id: 'alert-webhooks',
      category: 'alerting',
      name: 'Grafana, Alertmanager & Webhooks',
      badge: 'Alertas',
      configured: activeSources > 0,
      statusText: activeSources > 0 ? `${activeSources} fonte(s) mapeada(s)` : 'Formato padrão ativo',
      description: 'Recebe alertas externos com extração JSONPath, deduplicação por dedupKey e agrupamento inteligente por serviço (MON-09).',
      action: <Link to="/alert-sources" className="btn btn-small">Gerenciar fontes</Link>,
    },
    {
      id: 'slo-rules',
      category: 'alerting',
      name: 'Monitor SLO OTLP (Erro & p95)',
      badge: 'Monitoramento',
      configured: activeRules > 0,
      statusText: activeRules > 0 ? `${activeRules} regra(s) ativa(s)` : 'Usando limite padrão',
      description: 'Avalia automaticamente taxa de erro (%) e latência p95 nos spans recebidos para abrir incidentes antes do usuário reclamar.',
      action: <Link to="/alert-sources" className="btn btn-small">Configurar regras</Link>,
    },
    {
      id: 'cicd',
      category: 'delivery',
      name: 'GitHub Actions & Quality Gate',
      badge: 'CI/CD & DORA',
      configured: deployCount > 0,
      statusText: deployCount > 0 ? `${deployCount} deploy(s) registrados` : 'Sem deploys registrados',
      description: 'Registra deploys para cálculo das 4 métricas DORA e bloqueia releases automaticamente se houver incidente SEV1 aberto.',
      action: <Link to="/deploys" className="btn btn-small">Abrir Deploys</Link>,
    },
    {
      id: 'business-kpis',
      category: 'business',
      name: 'KPIs de Negócio & Impacto',
      badge: 'Negócio',
      configured: kpiCount > 0,
      statusText: kpiCount > 0 ? `${kpiCount} KPI(s) cadastrados` : 'Não configurado',
      description: 'Mapeia pedidos/min e receita por serviço para estimar automaticamente o custo financeiro de cada incidente.',
      action: <Link to="/settings/business" className="btn btn-small">Configurar KPIs</Link>,
    },
    ...(isAdmin ? [{
      id: 'api-keys',
      category: 'delivery',
      name: 'Chaves de API (X-API-Key)',
      badge: 'Autenticação',
      configured: true,
      statusText: 'Disponível para máquinas',
      description: 'Emite credenciais com hash SHA-256 para autenticar coletores OpenTelemetry, webhooks de alerta e pipelines CI/CD.',
      action: <Link to="/settings/api-keys" className="btn btn-small">Gerenciar chaves</Link>,
    }] : []),
  ]

  const visibleCatalog = category === 'all' ? catalog : catalog.filter((item) => item.category === category)

  return (
    <>
      <header className="page-header">
        <div>
          <h1>Catálogo de Integrações</h1>
          <p className="muted">Conecte telemetria OpenTelemetry, alertas, pipelines CI/CD e canais de aviso (Slack, Discord, E-mail)</p>
        </div>
        <Link to="/connect" className="btn btn-primary">Assistente Conectar App</Link>
      </header>

      <PageGuide
        id="integrations"
        summary="Catálogo central de conectores da plataforma: veja o status de cada integração da organização e configure notificações automáticas quando incidentes abrirem."
        steps={[
          'Use o assistente Conectar App para plugar sua aplicação com OpenTelemetry em poucos minutos.',
          'Adicione canais de Slack, Discord ou E-mail abaixo para avisar a equipe em tempo real.',
          'Clique em Testar para validar a entrega instantaneamente.',
        ]}
      />

      <nav className="tabs" aria-label="Categorias de integrações">
        {CATEGORIES.map((c) => (
          <button
            key={c.id}
            type="button"
            className={`btn btn-small ${category === c.id ? 'btn-primary' : ''}`}
            onClick={() => setCategory(c.id)}
          >
            {c.label}
          </button>
        ))}
      </nav>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(290px, 1fr))', gap: 16, marginBottom: 20 }}>
        {visibleCatalog.map((item) => (
          <article key={item.id} className="card" style={{ display: 'flex', flexDirection: 'column', justifyContent: 'space-between', margin: 0 }}>
            <div>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8, marginBottom: 8 }}>
                <span className="chip">{item.badge}</span>
                <span className={`badge status ${item.configured ? 'status-RESOLVED' : 'status-OPEN'}`}>
                  <i aria-hidden="true" />
                  {item.statusText}
                </span>
              </div>
              <h3 style={{ margin: '4px 0 8px' }}>{item.name}</h3>
              <p className="small muted" style={{ marginBottom: 14 }}>{item.description}</p>
            </div>
            <div>{item.action}</div>
          </article>
        ))}
      </div>

      {showOtelSnippet && (
        <section className="card">
          <h2>Configuração do OpenTelemetry Collector</h2>
          <p className="small muted">
            Aponte o exporter <code className="mono">otlphttp</code> para a API do Argos usando uma chave gerada em{' '}
            <Link to="/settings/api-keys">Chaves de API</Link>. Todos os dados pessoais (CPF, e-mail, cartões e tokens) são mascarados automaticamente na entrada (LGPD).
          </p>
          <CodeBlock title="otelcol-config.yaml" code={otelYamlSnippet()} />
        </section>
      )}

      <NotificationChannelsSection
        editable={editable}
        selectedType={selectedChannelType}
        onTypeChange={setSelectedChannelType}
        channels={channels}
        onChanged={channels.reload}
      />
    </>
  )
}
