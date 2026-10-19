import { useState } from 'react'

export function CopyButton({ text, label = 'Copiar' }) {
  const [copied, setCopied] = useState(false)

  async function copy() {
    try {
      await navigator.clipboard.writeText(text)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      setCopied(false)
    }
  }

  return (
    <button type="button" className="btn btn-small" onClick={copy}>
      {copied ? 'Copiado!' : label}
    </button>
  )
}

export function CodeBlock({ code, title }) {
  return (
    <div className="code-block">
      <div className="code-block-header">
        <span>{title}</span>
        <CopyButton text={code} />
      </div>
      <pre>
        <code>{code}</code>
      </pre>
    </div>
  )
}
