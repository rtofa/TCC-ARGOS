import { useEffect, useLayoutEffect, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/useAuth.js'
import { markTourCompleted } from '../tour.js'

function buildSteps(role) {
  const steps = [
    {
      route: '/',
      selector: '[data-tour="kpi-strip"]',
      title: '1. O que é o Argos e o Painel ao Vivo',
      body: 'O Argos unifica telemetria OpenTelemetry, alertas, deploys e gestão de incidentes em uma única sala de guerra. Estes 4 cartões resumem o estado atual: incidentes ativos, perda financeira estimada, rapidez na resolução (MTTR) e último deploy.',
    },
    {
      route: '/',
      selector: '[data-tour="service-map"]',
      title: '2. Descoberta de Serviços e Causa-Raiz',
      body: 'Assim que sua aplicação envia traces OTLP, o Argos desenha automaticamente o mapa de dependências entre microsserviços — destacando em vermelho o serviço onde a falha nasceu e em âmbar os serviços afetados em cascata.',
    },
    {
      route: '/',
      selector: '[data-tour="nav-connect"]',
      title: '3. Conecte sua Aplicação em Minutos',
      body: 'Use o assistente "Conectar App" para escolher sua stack (Docker/OTel, Java, Node.js, Python ou Go), copiar a configuração pronta com sua chave de API e validar em tempo real a chegada dos primeiros spans.',
    },
    {
      route: '/',
      selector: '[data-tour="nav-telemetry"]',
      title: '4. Telemetria: Traces, Logs LGPD e Gráficos RED',
      body: 'Explore requisições distribuídas em cascata (waterfall), gráficos RED (taxa, erros e latência p50/p95/p99) e logs com mascaramento automático de dados pessoais (CPF, e-mail, cartões e tokens).',
    },
    {
      route: '/',
      selector: '[data-tour="nav-incidents"]',
      title: '5. Incidentes, Alertas Agrupados e IA',
      body: 'Quando erros ou regras SLO disparam, alertas do mesmo serviço são agrupados em um único incidente. Na sala do incidente, você vê o deploy suspeito, as evidências de telemetria e o impacto financeiro estimado.',
    },
    {
      route: '/',
      selector: '[data-tour="nav-integrations"]',
      title: '6. Integrações (Slack, Discord, E-mail e CI/CD)',
      body: 'No Catálogo de Integrações você configura avisos automáticos para Slack, Discord e e-mail na abertura de incidentes, regras de Quality Gate para bloquear deploys em SEV1 e webhooks externos.',
    },
  ]

  if (role === 'ADMIN') {
    steps.push({
      route: '/',
      selector: '[data-tour="nav-users"]',
      title: '7. Controle de Usuários e Acessos (ADMIN)',
      body: 'Como Administrador, você pode adicionar membros da equipe, alterar papéis (Administrador, Editor, Leitor, Executivo), redefinir senhas e ativar ou desativar acessos da sua organização.',
    })
  }
  return steps
}

/**
 * Tour guiado com máscara escura (Spotlight): escurece toda a interface deixando
 * apenas o elemento relevante iluminado e exibe um cartão explicativo ao lado.
 */
export default function OnboardingTour({ open, onClose }) {
  const { session } = useAuth()
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const steps = buildSteps(session?.role)

  const [index, setIndex] = useState(0)
  const [rect, setRect] = useState(null)

  const current = steps[Math.min(index, steps.length - 1)]
  const targetRoute = current?.route
  const targetSelector = current?.selector

  function finish() {
    markTourCompleted(session?.userId)
    setIndex(0)
    onClose()
  }

  useEffect(() => {
    if (!open || !targetRoute) return
    if (pathname !== targetRoute) {
      navigate(targetRoute)
    }
  }, [open, targetRoute, pathname, navigate])

  useLayoutEffect(() => {
    if (!open || !targetSelector) return
    function measure() {
      const el = document.querySelector(targetSelector)
      if (!el) {
        setRect(null)
        return
      }
      const initialBox = el.getBoundingClientRect()
      if (initialBox.width === 0 && initialBox.height === 0) {
        setRect(null)
        return
      }
      el.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'smooth' })
      const box = el.getBoundingClientRect()
      const pad = 8
      setRect({
        top: Math.max(4, box.top - pad),
        left: Math.max(4, box.left - pad),
        width: box.width + pad * 2,
        height: box.height + pad * 2,
      })
    }
    const t1 = setTimeout(measure, 60)
    const t2 = setTimeout(measure, 250)
    window.addEventListener('resize', measure)
    window.addEventListener('scroll', measure, true)
    return () => {
      clearTimeout(t1)
      clearTimeout(t2)
      window.removeEventListener('resize', measure)
      window.removeEventListener('scroll', measure, true)
    }
  }, [open, index, pathname, targetSelector])

  useEffect(() => {
    if (!open) return
    const total = steps.length
    const userId = session?.userId
    function onKey(e) {
      if (e.key === 'Escape') {
        markTourCompleted(userId)
        setIndex(0)
        onClose()
      } else if (e.key === 'ArrowRight') {
        setIndex((i) => (i + 1 >= total ? i : i + 1))
      } else if (e.key === 'ArrowLeft') {
        setIndex((i) => Math.max(0, i - 1))
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, steps.length, session?.userId, onClose])

  if (!open || !current) return null

  const vw = typeof window !== 'undefined' ? window.innerWidth : 1200
  const vh = typeof window !== 'undefined' ? window.innerHeight : 800
  const cardWidth = Math.min(390, vw - 24)

  let cardStyle
  if (!rect) {
    cardStyle = {
      top: '50%',
      left: '50%',
      transform: 'translate(-50%, -50%)',
      width: cardWidth,
    }
  } else if (vw < 640) {
    const spotInBottom = rect.top + rect.height / 2 > vh * 0.52
    cardStyle = spotInBottom
      ? { top: 12, left: 12, width: vw - 24 }
      : { bottom: 12, left: 12, width: vw - 24 }
  } else {
    const placeRight = rect.left + rect.width + 18 + cardWidth < vw
    const placeBelow = rect.top + rect.height + 240 < vh
    const left = placeRight
      ? rect.left + rect.width + 18
      : Math.max(12, Math.min(vw - cardWidth - 12, rect.left))
    const top = placeRight
      ? Math.max(12, Math.min(vh - 250, rect.top))
      : placeBelow
        ? rect.top + rect.height + 14
        : Math.max(12, rect.top - 220)
    cardStyle = { top, left, width: cardWidth }
  }

  const isLast = index === steps.length - 1

  return (
    <div className="tour-root" role="dialog" aria-modal="true" aria-label={current.title}>
      <svg className="tour-backdrop" width="100%" height="100%" aria-hidden="true" onClick={finish}>
        <defs>
          <mask id="argos-tour-spotlight">
            <rect x="0" y="0" width="100%" height="100%" fill="white" />
            {rect && (
              <rect
                x={rect.left}
                y={rect.top}
                width={rect.width}
                height={rect.height}
                rx="12"
                ry="12"
                fill="black"
              />
            )}
          </mask>
        </defs>
        <rect
          x="0"
          y="0"
          width="100%"
          height="100%"
          fill="rgba(7, 13, 26, 0.74)"
          mask="url(#argos-tour-spotlight)"
        />
      </svg>

      {rect && (
        <div
          className="tour-highlight-ring"
          style={{
            top: rect.top,
            left: rect.left,
            width: rect.width,
            height: rect.height,
          }}
          aria-hidden="true"
        />
      )}

      <div className="tour-card" style={cardStyle}>
        <div className="tour-card-top">
          <span className="tour-badge">Tour guiado · Passo {index + 1} de {steps.length}</span>
          <button type="button" className="link-button small" onClick={finish}>
            Pular tour
          </button>
        </div>
        <h3>{current.title}</h3>
        <p>{current.body}</p>
        <div className="tour-card-foot">
          <div className="tour-dots" aria-hidden="true">
            {steps.map((s, i) => (
              <span key={s.title} className={`tour-dot ${i === index ? 'is-active' : ''}`} />
            ))}
          </div>
          <div className="tour-actions">
            {index > 0 && (
              <button type="button" className="btn btn-small" onClick={() => setIndex((i) => i - 1)}>
                Anterior
              </button>
            )}
            <button
              type="button"
              className="btn btn-small btn-primary"
              onClick={() => (isLast ? finish() : setIndex((i) => i + 1))}
            >
              {isLast ? 'Concluir' : 'Próximo'}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
