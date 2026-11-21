/**
 * Renders an assistant answer: paragraphs, bulleted and numbered lists, code blocks, **bold**, `code` and citations
 * [n] as small buttons that point to the sources. Built from React elements only (no HTML injection).
 */
export default function AnswerText({ text, onCite }) {
  return <div className="answer-text">{blocks(text).map((block, i) => renderBlock(block, i, onCite))}</div>
}

function blocks(text) {
  const result = []
  const lines = text.replace(/\r\n/g, '\n').split('\n')
  let current = null
  let fence = null
  for (const line of lines) {
    if (fence) {
      if (/^\s*```/.test(line)) {
        result.push(fence)
        fence = null
      } else {
        fence.lines.push(line)
      }
      continue
    }
    if (/^\s*```/.test(line)) {
      current = null
      fence = { type: 'code', lines: [] }
      continue
    }
    const bullet = /^\s*[-*•]\s+(.*)$/.exec(line)
    const numbered = /^\s*(\d+)[.)]\s+(.*)$/.exec(line)
    if (bullet || numbered) {
      const type = bullet ? 'ul' : 'ol'
      if (!current || current.type !== type) {
        current = { type, lines: [] }
        result.push(current)
      }
      current.lines.push(bullet ? bullet[1] : numbered[2])
    } else if (!line.trim()) {
      current = null
    } else {
      const heading = /^#{1,6}\s+(.*)$/.exec(line)
      if (heading) {
        result.push({ type: 'heading', lines: [heading[1]] })
        current = null
      } else if (current && current.type === 'p') {
        current.lines.push(line)
      } else if (current && (current.type === 'ul' || current.type === 'ol')) {
        // continuation of the previous list item
        current.lines[current.lines.length - 1] += ` ${line.trim()}`
      } else {
        current = { type: 'p', lines: [line] }
        result.push(current)
      }
    }
  }
  if (fence) result.push(fence)
  return result
}

function renderBlock(block, key, onCite) {
  switch (block.type) {
    case 'code':
      return (
        <pre key={key} className="answer-code">
          <code>{block.lines.join('\n')}</code>
        </pre>
      )
    case 'ul':
    case 'ol': {
      const List = block.type
      return (
        <List key={key}>
          {block.lines.map((item, i) => (
            <li key={i}>{inline(item, onCite)}</li>
          ))}
        </List>
      )
    }
    case 'heading':
      return (
        <p key={key} className="answer-heading">
          {inline(block.lines[0], onCite)}
        </p>
      )
    default:
      return <p key={key}>{inline(block.lines.join(' '), onCite)}</p>
  }
}

const INLINE = /(\*\*[^*]+\*\*|`[^`]+`|\[\d{1,2}\])/g

function inline(text, onCite) {
  return text.split(INLINE).filter(Boolean).map((part, i) => {
    if (part.startsWith('**') && part.endsWith('**')) return <strong key={i}>{part.slice(2, -2)}</strong>
    if (part.startsWith('`') && part.endsWith('`')) return <code key={i} className="mono">{part.slice(1, -1)}</code>
    const citation = /^\[(\d{1,2})\]$/.exec(part)
    if (citation) {
      const index = Number(citation[1])
      return (
        <button key={i} type="button" className="citation" onClick={() => onCite?.(index)} title={`Ver fonte ${index}`}>
          {index}
        </button>
      )
    }
    return part
  })
}
