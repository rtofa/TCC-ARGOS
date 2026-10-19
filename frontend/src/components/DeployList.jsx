import { formatDateTime, timeAgo } from '../format.js'

export function EnvironmentBadge({ environment }) {
  return <span className={`badge env ${environment === 'production' ? 'env-production' : ''}`}>{environment}</span>
}

/** Short commit, linked to the pipeline run when the deploy has a URL. */
export function CommitLink({ deploy }) {
  const label = deploy.commitSha ? deploy.commitSha.slice(0, 7) : 'execução'
  if (!deploy.url) return deploy.commitSha ? <code className="mono">{label}</code> : <span className="muted">—</span>
  return (
    <a href={deploy.url} target="_blank" rel="noreferrer noopener" className="mono">
      {label} ↗
    </a>
  )
}

/** Compact list used on the home and on the incident page; `relativeTo` labels each deploy against a moment. */
export function DeployList({ deploys, relativeTo }) {
  return (
    <ul className="deploy-list">
      {deploys.map((deploy) => (
        <li key={deploy.id}>
          <i className="deploy-dot" aria-hidden="true" />
          <div>
            <p>
              <strong>{deploy.service}</strong> <code className="mono">{deploy.version}</code>{' '}
              <EnvironmentBadge environment={deploy.environment} />
            </p>
            <p className="muted small">
              <time dateTime={deploy.deployedAt} title={formatDateTime(deploy.deployedAt)}>
                {relativeTo ? relativeTo(deploy) : timeAgo(deploy.deployedAt)}
              </time>
              {deploy.author && ` · ${deploy.author}`}
              {(deploy.commitSha || deploy.url) && ' · '}
              <CommitLink deploy={deploy} />
            </p>
          </div>
        </li>
      ))}
    </ul>
  )
}
