import { useState } from 'react'

/**
 * Faixa contextual padronizada no topo de cada tela explicando em linguagem direta
 * para que a página serve e qual o fluxo de trabalho esperado.
 */
export default function PageGuide({ id, summary, steps = [], actions = null }) {
  const storageKey = `argos.guide.collapsed.${id}`
  const [collapsed, setCollapsed] = useState(() => {
    try {
      return localStorage.getItem(storageKey) === 'true'
    } catch {
      return false
    }
  })

  function toggle() {
    const next = !collapsed
    setCollapsed(next)
    try {
      localStorage.setItem(storageKey, String(next))
    } catch {
      // Ignore storage failures
    }
  }

  return (
    <section className={`page-guide ${collapsed ? 'is-collapsed' : ''}`} aria-label="Sobre esta tela">
      <div className="page-guide-head">
        <p className="page-guide-summary">
          <strong>Para que serve:</strong> {summary}
        </p>
        <button
          type="button"
          className="link-button small"
          onClick={toggle}
          aria-expanded={!collapsed}
        >
          {collapsed ? 'Mostrar guia' : 'Ocultar guia'}
        </button>
      </div>

      {!collapsed && (steps.length > 0 || actions) && (
        <div className="page-guide-body">
          {steps.length > 0 && (
            <ol className="page-guide-steps">
              {steps.map((step, i) => (
                <li key={step}>
                  <span className="page-guide-num">{i + 1}</span>
                  <span>{step}</span>
                </li>
              ))}
            </ol>
          )}
          {actions && <div className="page-guide-actions">{actions}</div>}
        </div>
      )}
    </section>
  )
}
