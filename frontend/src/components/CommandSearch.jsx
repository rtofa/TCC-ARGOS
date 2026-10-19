import { useEffect, useId, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Icon } from './Icon.jsx'

const GROUP_LIMIT = 5

const normalize = (text) =>
  text
    .normalize('NFD')
    .replace(/\p{Diacritic}/gu, '')
    .toLowerCase()

/** Groups in a fixed order; with no query, only pages are suggested. */
function search(items, queryText) {
  const q = normalize(queryText.trim())
  const matches = q
    ? items.filter((item) => normalize(`${item.label} ${item.hint ?? ''}`).includes(q))
    : items.filter((item) => item.group === 'Páginas')
  const groups = []
  for (const item of matches) {
    let group = groups.find((g) => g.name === item.group)
    if (!group) {
      group = { name: item.group, items: [] }
      groups.push(group)
    }
    if (group.items.length < GROUP_LIMIT) group.items.push(item)
  }
  return groups
}

/**
 * AWS-console-style search over pages, incidents and services. "/" focuses it from anywhere.
 * items: [{ group, label, hint, to, icon }]
 */
export default function CommandSearch({ items }) {
  const navigate = useNavigate()
  const input = useRef(null)
  const listId = useId()
  const [query, setQuery] = useState('')
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(0)

  useEffect(() => {
    function onKey(event) {
      const typing = ['INPUT', 'TEXTAREA', 'SELECT'].includes(document.activeElement?.tagName)
      if (event.key === '/' && !typing && !event.ctrlKey && !event.metaKey) {
        event.preventDefault()
        input.current?.focus()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  const groups = search(items, query)
  const flat = groups.flatMap((group) => group.items)
  const current = Math.min(active, Math.max(flat.length - 1, 0))

  function go(item) {
    setOpen(false)
    setQuery('')
    input.current?.blur()
    navigate(item.to)
  }

  function onKeyDown(event) {
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      setOpen(true)
      setActive((current + 1) % Math.max(flat.length, 1))
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      setActive((current - 1 + flat.length) % Math.max(flat.length, 1))
    } else if (event.key === 'Enter' && flat[current]) {
      event.preventDefault()
      go(flat[current])
    } else if (event.key === 'Escape') {
      setOpen(false)
      input.current?.blur()
    }
  }

  const optionId = (index) => `${listId}-option-${index}`
  // Position of each group's first item in the flat, keyboard-navigable list.
  const offsets = groups.map((_, g) => groups.slice(0, g).reduce((sum, group) => sum + group.items.length, 0))

  return (
    <div className="command-search">
      <Icon name="search" size={18} className="command-search-icon" />
      <input
        ref={input}
        role="combobox"
        aria-expanded={open}
        aria-controls={listId}
        aria-activedescendant={open && flat.length ? optionId(current) : undefined}
        aria-label="Buscar páginas, incidentes e serviços"
        placeholder="Buscar páginas, incidentes e serviços"
        value={query}
        onChange={(e) => {
          setQuery(e.target.value)
          setActive(0)
          setOpen(true)
        }}
        onFocus={() => setOpen(true)}
        onBlur={() => setOpen(false)}
        onKeyDown={onKeyDown}
      />
      <kbd className="command-search-kbd" aria-hidden="true">/</kbd>

      {open && (
        <div className="command-results" id={listId} role="listbox" aria-label="Resultados">
          {flat.length === 0 && <p className="command-empty">Nada encontrado para “{query}”.</p>}
          {groups.map((group, g) => (
            <div key={group.name} role="group" aria-label={group.name}>
              <p className="command-group">{group.name}</p>
              {group.items.map((item, i) => {
                const position = offsets[g] + i
                return (
                  <div
                    key={`${group.name}-${item.to}-${item.label}`}
                    id={optionId(position)}
                    role="option"
                    aria-selected={position === current}
                    className="command-option"
                    onMouseDown={(e) => e.preventDefault()}
                    onMouseEnter={() => setActive(position)}
                    onClick={() => go(item)}
                  >
                    <Icon name={item.icon} size={16} />
                    <span className="command-label">{item.label}</span>
                    {item.hint && <span className="command-hint">{item.hint}</span>}
                    <Icon name="arrow" size={14} className="command-go" />
                  </div>
                )
              })}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
