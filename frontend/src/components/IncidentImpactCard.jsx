import { Link } from 'react-router-dom'
import { useApi } from '../hooks/useApi.js'
import { ErrorState, Loading } from './States.jsx'
import { formatDuration, formatMoney, formatNumber } from '../format.js'

const METHOD = {
  TELEMETRIA: 'Calculado com a taxa de erro real medida na telemetria.',
  SEVERIDADE: 'Estimado pela severidade (sem telemetria suficiente do serviço).',
}

/** NEG-03: what this incident is costing the business; refreshes every minute while it is open. */
export default function IncidentImpactCard({ incident }) {
  const impact = useApi(`/incidents/${incident.id}/impact`, incident.resolvedAt ? 0 : 60000)
  const data = impact.data

  return (
    <section className="card impact-card">
      <h2>Impacto no negócio</h2>
      {impact.error && <ErrorState error={impact.error} onRetry={impact.reload} />}
      {!data && !impact.error && <Loading />}
      {data && data.method === 'SEM_KPI' && (
        <p className="muted small">
          Nenhum KPI está ligado a {incident.affectedServices.length ? incident.affectedServices.join(', ') : 'os serviços deste incidente'}.{' '}
          <Link to="/settings/business">Configure os KPIs de negócio</Link> para ver pedidos e receita em risco.
        </p>
      )}
      {data && data.method !== 'SEM_KPI' && (
        <>
          <p className="impact-total">
            <strong>{formatMoney(data.totalValue)}</strong>
            <span className="muted small">
              em {formatDuration(data.minutes * 60)}{data.ongoing ? ', e contando' : ''}
            </span>
          </p>
          <ul className="impact-kpis">
            {data.kpis.map((kpi) => (
              <li key={kpi.kpiId}>
                <span>{kpi.kpi}</span>
                <span className="numeric">
                  ~{formatNumber(kpi.lostUnits)} {kpi.unit}
                  {kpi.lostValue !== null && <span className="muted"> · {formatMoney(kpi.lostValue)}</span>}
                </span>
                <span className="muted small">{formatNumber(kpi.degradationPercent)}% do KPI afetado via {kpi.service}</span>
              </li>
            ))}
          </ul>
          <p className="muted small">{METHOD[data.method]}</p>
        </>
      )}
    </section>
  )
}
