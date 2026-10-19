import { SEVERITY_LABEL, STATUS_LABEL } from '../format.js'

export function SeverityBadge({ severity }) {
  return (
    <span className={`badge sev sev-${severity}`} title={SEVERITY_LABEL[severity]}>
      {severity}
    </span>
  )
}

export function StatusBadge({ status }) {
  return (
    <span className={`badge status status-${status}`}>
      <i aria-hidden="true" />
      {STATUS_LABEL[status]}
    </span>
  )
}

export function ServiceChips({ services }) {
  if (!services?.length) return <span className="muted">—</span>
  return (
    <span className="chips">
      {services.map((service) => (
        <span className="chip" key={service}>
          {service}
        </span>
      ))}
    </span>
  )
}
