import { useEffect, useRef, useState } from 'react'
import { api } from '../api.js'
import { useApi } from '../hooks/useApi.js'
import { Icon } from './Icon.jsx'
import AnswerText from './AnswerText.jsx'
import { formatDateTime } from '../format.js'

const SUGGESTIONS = [
  'Como conecto minha aplicação com OpenTelemetry?',
  'Quando o Quality Gate bloqueia um deploy?',
  'Como configuro notificações no Slack?',
  'Como é calculado o prejuízo de um incidente?',
]

const MAX_HISTORY = 6

/**
 * Lia, the support assistant, on every page: a floating question-mark bubble that opens a chat answered from the
 * official documentation (streamed). It lives in the layout, so the conversation survives
 * navigation.
 */
export default function HelpAssistant({ isAdmin }) {
  const [open, setOpen] = useState(false)
  const [view, setView] = useState('chat') // 'chat' | 'knowledge'
  const [messages, setMessages] = useState([])
  const [question, setQuestion] = useState('')
  const [busy, setBusy] = useState(false)
  const abort = useRef(null)
  const nextId = useRef(1)
  const scroller = useRef(null)
  const input = useRef(null)
  const launcher = useRef(null)

  useEffect(() => {
    const element = scroller.current
    if (element) element.scrollTop = element.scrollHeight
  }, [messages, open, view])

  useEffect(() => {
    if (open && view === 'chat') input.current?.focus()
  }, [open, view])

  useEffect(() => () => abort.current?.abort(), [])

  useEffect(() => {
    if (!open) return undefined
    const onKey = (event) => {
      if (event.key === 'Escape') {
        setOpen(false)
        launcher.current?.focus()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open])

  function close() {
    setOpen(false)
    launcher.current?.focus()
  }

  const update = (id, change) => setMessages((all) => all.map((m) => (m.id === id ? change(m) : m)))

  async function send(text) {
    const asked = text.trim()
    if (!asked || busy) return
    const history = messages
      .filter((m) => m.status === 'done' && m.text)
      .slice(-MAX_HISTORY)
      .map((m) => ({ role: m.role === 'user' ? 'USER' : 'ASSISTANT', content: m.text.slice(0, 4000) }))
    const userId = nextId.current++
    const id = nextId.current++
    setMessages((all) => [
      ...all,
      { id: userId, role: 'user', text: asked, status: 'done' },
      { id, role: 'assistant', text: '', status: 'streaming' },
    ])
    setQuestion('')
    setBusy(true)
    const controller = new AbortController()
    abort.current = controller
    try {
      await api.stream(
        '/support/ask/stream',
        { question: asked, history },
        (event, data) =>
          update(id, (m) => {
            switch (event) {
              case 'token':
                return { ...m, text: m.text + data.text }
              case 'done':
                return { ...m, status: 'done', text: data.answer }
              case 'error':
                return { ...m, status: 'error', error: data.message }
              default:
                return m
            }
          }),
        { signal: controller.signal },
      )
      update(id, (m) => (m.status === 'streaming' ? { ...m, status: 'error', error: 'A resposta foi interrompida.' } : m))
    } catch (error) {
      if (error.name === 'AbortError') {
        update(id, (m) => ({ ...m, status: 'done', text: m.text || 'Tudo bem, parei por aqui.' }))
      } else {
        update(id, (m) => ({ ...m, status: 'error', error: error.message }))
      }
    } finally {
      setBusy(false)
      abort.current = null
    }
  }

  function onKeyDown(event) {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      send(question)
    }
  }

  return (
    <>
      {open && (
        <section className="lia-panel" role="dialog" aria-label="Lia, assistente do Argos">
          <header className="lia-head">
            {view === 'knowledge' ? (
              <button type="button" className="lia-icon-btn" onClick={() => setView('chat')} aria-label="Voltar à conversa" title="Voltar à conversa">
                <Icon name="back" size={18} />
              </button>
            ) : (
              <LiaAvatar size="md" />
            )}
            <div className="lia-title">
              <strong>{view === 'knowledge' ? 'Base de conhecimento' : 'Lia'}</strong>
              <small>
                {view === 'knowledge' ? (
                  'Documentos usados nas respostas'
                ) : (
                  <>
                    <i className="lia-online" aria-hidden="true" />
                    Assistente do Argos
                  </>
                )}
              </small>
            </div>
            {view === 'chat' && messages.length > 0 && !busy && (
              <button type="button" className="lia-icon-btn" onClick={() => setMessages([])} aria-label="Nova conversa" title="Nova conversa">
                <Icon name="refresh" size={17} />
              </button>
            )}
            {view === 'chat' && isAdmin && (
              <button type="button" className="lia-icon-btn" onClick={() => setView('knowledge')} aria-label="Base de conhecimento" title="Base de conhecimento">
                <Icon name="info" size={18} />
              </button>
            )}
            <button type="button" className="lia-icon-btn" onClick={close} aria-label="Fechar" title="Fechar (Esc)">
              <Icon name="close" size={18} />
            </button>
          </header>

          {view === 'knowledge' ? (
            <KnowledgePanel />
          ) : (
            <>
              <div className="lia-messages" ref={scroller} aria-live="polite">
                {messages.length === 0 ? (
                  <div className="lia-welcome">
                    <LiaAvatar size="lg" />
                    <p className="lia-welcome-title">Oi, eu sou a Lia!</p>
                    <p className="lia-welcome-text">
                      Posso te ajudar com dúvidas sobre o Argos: incidentes, alertas, deploys, telemetria, integrações e muito mais.
                    </p>
                    <div className="lia-suggestions">
                      {SUGGESTIONS.map((suggestion) => (
                        <button key={suggestion} type="button" className="lia-suggestion" onClick={() => send(suggestion)}>
                          <Icon name="arrow" size={14} />
                          <span>{suggestion}</span>
                        </button>
                      ))}
                    </div>
                  </div>
                ) : (
                  messages.map((message) =>
                    message.role === 'user' ? (
                      <div key={message.id} className="lia-row lia-row-user">
                        <div className="lia-bubble lia-bubble-user">{message.text}</div>
                      </div>
                    ) : (
                      <LiaMessage key={message.id} message={message} />
                    ),
                  )
                )}
              </div>

              <form
                className="lia-composer"
                onSubmit={(event) => {
                  event.preventDefault()
                  send(question)
                }}
              >
                <textarea
                  ref={input}
                  value={question}
                  onChange={(event) => setQuestion(event.target.value)}
                  onKeyDown={onKeyDown}
                  placeholder="Pergunte para a Lia…"
                  rows={1}
                  maxLength={1000}
                  aria-label="Pergunta para a Lia"
                />
                {busy ? (
                  <button type="button" className="lia-send is-stop" onClick={() => abort.current?.abort()} aria-label="Parar resposta" title="Parar">
                    <span className="lia-stop-square" aria-hidden="true" />
                  </button>
                ) : (
                  <button className="lia-send" disabled={!question.trim()} aria-label="Enviar pergunta" title="Enviar">
                    <Icon name="arrow" size={18} strokeWidth={2.2} />
                  </button>
                )}
              </form>
              <p className="lia-footnote">A Lia responde com base na documentação oficial do Argos.</p>
            </>
          )}
        </section>
      )}

      <button
        ref={launcher}
        type="button"
        className={`lia-launcher ${open ? 'is-open' : ''}`}
        onClick={() => (open ? close() : setOpen(true))}
        aria-expanded={open}
        aria-label={open ? 'Fechar a Lia' : 'Dúvidas? Fale com a Lia'}
      >
        <Icon name={open ? 'close' : 'ask'} size={26} />
        {!open && <span className="lia-launcher-tip" aria-hidden="true">Dúvidas? Fale com a Lia</span>}
      </button>
    </>
  )
}

function LiaAvatar({ size }) {
  return (
    <span className={`lia-avatar lia-avatar-${size}`} aria-hidden="true">
      L
    </span>
  )
}

function LiaMessage({ message }) {
  const thinking = message.status === 'streaming' && !message.text
  const streaming = message.status === 'streaming' && Boolean(message.text)

  return (
    <div className="lia-row lia-row-assistant">
      <LiaAvatar size="sm" />
      <div className={`lia-bubble lia-bubble-assistant ${streaming ? 'is-streaming' : ''}`}>
        {thinking ? (
          <div className="lia-thinking" role="status" aria-label="A Lia está escrevendo">
            <span className="lia-typing" aria-hidden="true">
              <i />
              <i />
              <i />
            </span>
            <span className="lia-shimmer" aria-hidden="true">
              <i />
              <i />
            </span>
          </div>
        ) : message.status === 'error' ? (
          <p className="lia-error" role="alert">
            {message.error || 'Algo deu errado.'} Tente de novo em instantes.
          </p>
        ) : (
          <AnswerText text={message.text} />
        )}
      </div>
    </div>
  )
}

function KnowledgePanel() {
  const status = useApi('/support/knowledge', 30000)
  const [reindexing, setReindexing] = useState(false)
  const [result, setResult] = useState(null)
  const data = status.data
  const capabilities = data?.capabilities

  async function reindex() {
    setReindexing(true)
    setResult(null)
    try {
      const report = await api.post('/support/knowledge/reindex')
      setResult({ ok: true, text: `${report.indexed} documento(s) indexado(s), ${report.unchanged} sem mudança, ${report.removed} removido(s).` })
      status.reload()
    } catch (error) {
      setResult({ ok: false, text: error.message })
    } finally {
      setReindexing(false)
    }
  }

  if (!data) {
    return <p className="lia-knowledge muted small">{status.error ? status.error.message : 'Carregando…'}</p>
  }
  return (
    <div className="lia-knowledge">
      {!data.storeAvailable && <p className="lia-warning">A base de conhecimento está indisponível no momento.</p>}
      <dl className="lia-facts">
        <div>
          <dt>Documentos</dt>
          <dd>{data.documents.length}</dd>
        </div>
        <div>
          <dt>Trechos com embedding</dt>
          <dd>{data.chunks}</dd>
        </div>
        <div>
          <dt>Busca</dt>
          <dd>{capabilities.semanticSearch ? 'Híbrida (textual + vetorial)' : 'Somente textual'}</dd>
        </div>
        <div>
          <dt>Reranking</dt>
          <dd>{capabilities.reranking ? capabilities.rerankModel : 'Desligado'}</dd>
        </div>
      </dl>
      <p className="lia-label">Modelos de linguagem</p>
      {capabilities.models.length ? (
        <ol className="lia-list">
          {capabilities.models.map((model) => (
            <li key={model}>{model}</li>
          ))}
        </ol>
      ) : (
        <p className="muted small">Nenhum modelo configurado: a Lia mostra os trechos da documentação.</p>
      )}
      {data.documents.length > 0 && (
        <>
          <p className="lia-label">Documentos indexados</p>
          <ul className="lia-list">
            {data.documents.map((doc) => (
              <li key={doc.docId} title={`Indexado em ${formatDateTime(doc.indexedAt)}`}>
                {doc.title} <span className="muted">· {doc.chunks} trechos</span>
              </li>
            ))}
          </ul>
        </>
      )}
      <div className="lia-reindex">
        <button type="button" className="btn btn-small" onClick={reindex} disabled={reindexing || data.indexing}>
          {reindexing || data.indexing ? 'Indexando…' : 'Reindexar documentos'}
        </button>
        {result && <p className={result.ok ? 'muted small' : 'form-error'}>{result.text}</p>}
      </div>
    </div>
  )
}
