import { useState } from 'react'
import { api } from '../api.js'
import { useApi } from '../hooks/useApi.js'
import { Icon } from './Icon.jsx'
import { ErrorState } from './States.jsx'

const POLL_MS = 2000
const SUGGESTIONS = ['Qual é a causa mais provável?', 'O que mudou antes do incidente?', 'Quais clientes ou fluxos são afetados?']

/** Renders the little Markdown the model uses (bold and "- " bullets) without injecting HTML. */
function Inline({ text }) {
  return text.split(/(\*\*[^*]+\*\*)/g).map((part, i) =>
    part.startsWith('**') && part.endsWith('**') ? <strong key={i}>{part.slice(2, -2)}</strong> : part)
}

function MessageText({ text }) {
  const blocks = []
  for (const line of text.split('\n')) {
    const bullet = line.match(/^\s*[-*•]\s+(.*)$/)
    const last = blocks.at(-1)
    if (bullet) {
      if (last?.type === 'list') last.items.push(bullet[1])
      else blocks.push({ type: 'list', items: [bullet[1]] })
    } else if (line.trim()) {
      blocks.push({ type: 'p', text: line.replace(/^#+\s*/, '') })
    }
  }
  return (
    <div className="assistant-text">
      {blocks.map((block, i) => block.type === 'list'
        ? <ul key={i}>{block.items.map((item, j) => <li key={j}><Inline text={item} /></li>)}</ul>
        : <p key={i}><Inline text={block.text} /></p>)}
    </div>
  )
}

/** Follow-up questions about the incident; the assistant remembers the conversation of this incident. */
export default function IncidentAssistant({ incidentId }) {
  const path = `/incidents/${incidentId}/assistant/messages`
  const [pollMs, setPollMs] = useState(0)
  const conversation = useApi(path, pollMs)
  const [question, setQuestion] = useState('')
  const [sending, setSending] = useState(false)
  const [sendError, setSendError] = useState(null)

  const messages = conversation.data ?? []
  const waiting = messages.some((message) => message.status === 'PENDING')
  if ((waiting ? POLL_MS : 0) !== pollMs) setPollMs(waiting ? POLL_MS : 0)

  async function ask(text) {
    const value = text.trim()
    if (!value) return
    setSending(true)
    setSendError(null)
    try {
      await api.post(path, { question: value })
      setQuestion('')
      conversation.reload()
    } catch (e) {
      setSendError(e.message)
    } finally {
      setSending(false)
    }
  }

  return (
    <section className="card assistant" aria-labelledby="assistant-title">
      <h2 id="assistant-title"><Icon name="sparkle" size={18} /> Pergunte ao Argos</h2>
      {conversation.error && <ErrorState error={conversation.error} onRetry={conversation.reload} />}

      {messages.length === 0 ? (
        <div className="assistant-empty">
          <p className="muted small">Tire dúvidas sobre este incidente. O assistente consulta os mesmos dados da investigação.</p>
          <div className="chips">
            {SUGGESTIONS.map((suggestion) => (
              <button key={suggestion} type="button" className="toggle" disabled={sending} onClick={() => ask(suggestion)}>
                {suggestion}
              </button>
            ))}
          </div>
        </div>
      ) : (
        <ol className="assistant-messages" aria-live="polite">
          {messages.map((message) => (
            <li key={message.id} className={`assistant-message from-${message.role.toLowerCase()} is-${message.status.toLowerCase()}`}>
              {message.status === 'PENDING' ? (
                <span className="assistant-thinking"><span className="spinner" aria-hidden="true" /> Analisando os dados…</span>
              ) : (
                message.role === 'ASSISTANT' ? <MessageText text={message.content} /> : <p className="prose">{message.content}</p>
              )}
              {message.guardrail === 'BLOQUEADA' && <span className="badge badge-warning">Bloqueada pelo guardrail</span>}
            </li>
          ))}
        </ol>
      )}

      <form className="assistant-form" onSubmit={(event) => { event.preventDefault(); ask(question) }}>
        <textarea
          value={question}
          onChange={(event) => setQuestion(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter' && !event.shiftKey) {
              event.preventDefault()
              ask(question)
            }
          }}
          placeholder="Ex.: o serviço de pagamento também foi afetado?"
          rows={2}
          maxLength={2000}
          aria-label="Pergunta para o assistente"
          disabled={waiting}
        />
        <button className="btn btn-primary" disabled={sending || waiting || !question.trim()}>Perguntar</button>
      </form>
      {sendError && <p className="form-error" role="alert">{sendError}</p>}
    </section>
  )
}
