import { StatusIcon } from './Icon.jsx'
import { formatMs } from '../format.js'

const NODE_W = 186
const NODE_H = 68
const COL_GAP = 128
const ROW_GAP = 26
const PAD_X = 28
const PAD_Y = 28

/**
 * Assigns each node to a left-to-right column (layer) using longest-path layering
 * from entry services (in-degree 0), gracefully handling cycles.
 */
function layoutTopology(nodes, edges) {
  if (!nodes.length) return { placed: [], links: [], width: 0, height: 0 }

  const byName = new Map(nodes.map((n) => [n.service, n]))
  const outgoing = new Map(nodes.map((n) => [n.service, []]))
  const indegree = new Map(nodes.map((n) => [n.service, 0]))

  for (const e of edges) {
    if (byName.has(e.source) && byName.has(e.target) && e.source !== e.target) {
      outgoing.get(e.source).push(e.target)
      indegree.set(e.target, (indegree.get(e.target) ?? 0) + 1)
    }
  }

  const layer = new Map()
  const queue = []
  for (const n of nodes) {
    if ((indegree.get(n.service) ?? 0) === 0) {
      layer.set(n.service, 0)
      queue.push(n.service)
    }
  }
  if (queue.length === 0) {
    layer.set(nodes[0].service, 0)
    queue.push(nodes[0].service)
  }

  const maxLayerAllowed = Math.min(6, nodes.length)
  while (queue.length > 0) {
    const u = queue.shift()
    const uLayer = layer.get(u) ?? 0
    for (const v of outgoing.get(u) ?? []) {
      const nextLayer = Math.min(maxLayerAllowed, uLayer + 1)
      if (!layer.has(v) || layer.get(v) < nextLayer) {
        layer.set(v, nextLayer)
        queue.push(v)
      }
    }
  }

  for (const n of nodes) {
    if (!layer.has(n.service)) layer.set(n.service, 0)
  }

  const columns = new Map()
  for (const n of nodes) {
    const col = layer.get(n.service)
    if (!columns.has(col)) columns.set(col, [])
    columns.get(col).push(n)
  }

  const sortedCols = [...columns.keys()].sort((a, b) => a - b)
  const maxRows = Math.max(1, ...sortedCols.map((c) => columns.get(c).length))
  const totalHeight = Math.max(180, PAD_Y * 2 + maxRows * NODE_H + (maxRows - 1) * ROW_GAP)
  const totalWidth = Math.max(420, PAD_X * 2 + sortedCols.length * NODE_W + Math.max(0, sortedCols.length - 1) * COL_GAP)

  const placedMap = new Map()
  sortedCols.forEach((colKey, colIdx) => {
    const colNodes = columns.get(colKey).sort((a, b) => {
      if (a.rootCause !== b.rootCause) return a.rootCause ? -1 : 1
      if (b.errorSpans !== a.errorSpans) return b.errorSpans - a.errorSpans
      return a.service.localeCompare(b.service)
    })
    const colHeight = colNodes.length * NODE_H + (colNodes.length - 1) * ROW_GAP
    const startY = Math.max(PAD_Y, (totalHeight - colHeight) / 2)
    const x = PAD_X + colIdx * (NODE_W + COL_GAP)

    colNodes.forEach((node, rowIdx) => {
      const y = startY + rowIdx * (NODE_H + ROW_GAP)
      placedMap.set(node.service, { ...node, x, y, colIdx })
    })
  })

  const links = edges
    .filter((e) => placedMap.has(e.source) && placedMap.has(e.target))
    .map((e) => ({
      ...e,
      from: placedMap.get(e.source),
      to: placedMap.get(e.target),
    }))

  return { placed: [...placedMap.values()], links, width: totalWidth, height: totalHeight }
}

function nodeTone(node) {
  if (node.rootCause) return { key: 'critical', label: 'Causa-raiz' }
  if (node.errorSpans > 0) return { key: 'warning', label: 'Efeito cascata' }
  return { key: 'good', label: 'Operacional' }
}

export default function ServiceTopologyGraph({ topology, onSelectService }) {
  const nodes = topology?.nodes ?? []
  const edges = topology?.edges ?? []

  if (!nodes.length) {
    return (
      <p className="empty">
        Nenhuma chamada entre microsserviços registrada no período. Assim que os spans OTLP chegarem, o grafo de
        dependências aparecerá automaticamente.
      </p>
    )
  }

  const { placed, links, width, height } = layoutTopology(nodes, edges)
  const rootCauses = placed.filter((n) => n.rootCause)

  return (
    <div className="topology-wrap">
      <div className="topology-toolbar">
        <ul className="status-legend" aria-label="Legenda de topologia">
          <li>
            <StatusIcon status="good" size={14} />
            Operacional
          </li>
          <li>
            <StatusIcon status="warning" size={14} />
            Efeito cascata (erro propagado de dependência)
          </li>
          <li>
            <StatusIcon status="critical" size={14} />
            Causa-raiz (onde a falha nasceu)
          </li>
        </ul>
        {rootCauses.length > 0 && (
          <span className="badge wf-badge">
            Causa-raiz identificada: {rootCauses.map((n) => n.service).join(', ')}
          </span>
        )}
      </div>

      <div className="topology-scroll">
        <svg
          className="topology-svg"
          viewBox={`0 0 ${width} ${height}`}
          width={width}
          height={height}
          role="img"
          aria-label="Grafo de dependência entre microsserviços"
        >
          <defs>
            <marker id="arrow-ok" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
              <path d="M 0 1 L 10 5 L 0 9 z" fill="#2f64ab" />
            </marker>
            <marker id="arrow-err" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
              <path d="M 0 1 L 10 5 L 0 9 z" fill="#d63f3f" />
            </marker>
          </defs>

          {links.map((link) => {
            const forward = link.from.x <= link.to.x
            const x1 = forward ? link.from.x + NODE_W : link.from.x
            const y1 = link.from.y + NODE_H / 2
            const x2 = forward ? link.to.x : link.to.x + NODE_W
            const y2 = link.to.y + NODE_H / 2
            const dx = Math.max(44, Math.abs(x2 - x1) * 0.45)
            const c1x = forward ? x1 + dx : x1 - dx
            const c2x = forward ? x2 - dx : x2 + dx
            const path = `M ${x1} ${y1} C ${c1x} ${y1}, ${c2x} ${y2}, ${x2} ${y2}`
            const midX = (x1 + x2) / 2
            const midY = (y1 + y2) / 2 - 7
            const hasErr = link.errors > 0
            return (
              <g key={`${link.source}->${link.target}`} className="topology-edge">
                <path
                  d={path}
                  fill="none"
                  stroke={hasErr ? '#d63f3f' : '#7b9ed0'}
                  strokeWidth={hasErr ? 2.4 : 1.7}
                  strokeDasharray={hasErr ? undefined : undefined}
                  markerEnd={hasErr ? 'url(#arrow-err)' : 'url(#arrow-ok)'}
                />
                <rect
                  x={midX - 46}
                  y={midY - 10}
                  width="92"
                  height="18"
                  rx="9"
                  fill={hasErr ? '#fdeaea' : '#f3f6fa'}
                  stroke={hasErr ? '#f2b8b5' : '#d6dfee'}
                />
                <text x={midX} y={midY + 3} textAnchor="middle" className={`topology-edge-label ${hasErr ? 'is-err' : ''}`}>
                  {link.calls} req · {formatMs(link.avgDurationMs)}
                </text>
              </g>
            )
          })}

          {placed.map((node) => {
            const tone = nodeTone(node)
            return (
              <g
                key={node.service}
                transform={`translate(${node.x}, ${node.y})`}
                className={`topology-node topology-node-${tone.key}`}
                onClick={() => onSelectService && onSelectService(node.service)}
                style={{ cursor: onSelectService ? 'pointer' : 'default' }}
              >
                <rect width={NODE_W} height={NODE_H} rx="8" className="topology-node-box" />
                <rect width="5" height={NODE_H} rx="2" className="topology-node-bar" />
                <text x="14" y="22" className="topology-node-title">
                  {node.service.length > 20 ? `${node.service.slice(0, 19)}…` : node.service}
                </text>
                <text x="14" y="40" className={`topology-node-state topology-state-${tone.key}`}>
                  {tone.label}
                  {node.errorSpans > 0 ? ` · ${node.errorRatePercent}% erro` : ''}
                </text>
                <text x="14" y="56" className="topology-node-meta">
                  {node.spans} spans · p95 {formatMs(node.p95DurationMs)}
                </text>
              </g>
            )
          })}
        </svg>
      </div>
    </div>
  )
}
