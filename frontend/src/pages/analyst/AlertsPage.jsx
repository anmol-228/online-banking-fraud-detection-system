import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import api from '../../services/bankingService.js';
import { readError } from '../../api/client.js';
import { formatAmount, formatDateTime } from '../../utils/format.js';
import Badge from '../../components/Badge.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import Loader from '../../components/Loader.jsx';
import Message from '../../components/Message.jsx';

const FILTERS = [
  { value: '', label: 'All alerts' },
  { value: 'OPEN', label: 'Open' },
  { value: 'UNDER_REVIEW', label: 'Under review' },
  { value: 'CLOSED', label: 'Closed' },
];

/** Fraud alert dashboard (FR-13, FR-18). */
export default function AlertsPage() {
  const [alerts, setAlerts] = useState([]);
  const [status, setStatus] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    api
      .alerts(status)
      .then(({ data }) => {
        if (!cancelled) {
          setAlerts(data);
        }
      })
      .catch((err) => {
        if (!cancelled) {
          setError(readError(err));
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [status]);

  const highRisk = alerts.filter((alert) => alert.riskLevel === 'HIGH').length;
  const open = alerts.filter((alert) => alert.status === 'OPEN').length;

  return (
    <div className="page">
      <header className="page__header">
        <div>
          <h1 className="page__title">Fraud alerts</h1>
          <p className="page__subtitle">
            Transactions the monitoring rules classified as suspicious or high risk.
          </p>
        </div>
        <div className="filter-group">
          {FILTERS.map((filter) => (
            <button
              key={filter.value}
              type="button"
              className={
                status === filter.value
                  ? 'button button--small button--secondary'
                  : 'button button--small button--ghost'
              }
              onClick={() => setStatus(filter.value)}
            >
              {filter.label}
            </button>
          ))}
        </div>
      </header>

      <Message tone="error" onDismiss={() => setError('')}>
        {error}
      </Message>

      <section className="stat-grid">
        <div className="stat-card">
          <p className="stat-card__label">Alerts shown</p>
          <p className="stat-card__value">{alerts.length}</p>
        </div>
        <div className="stat-card">
          <p className="stat-card__label">Open</p>
          <p className="stat-card__value">{open}</p>
        </div>
        <div className="stat-card stat-card--danger">
          <p className="stat-card__label">High risk</p>
          <p className="stat-card__value">{highRisk}</p>
        </div>
      </section>

      <section className="card">
        {loading ? (
          <Loader label="Loading alerts" />
        ) : alerts.length === 0 ? (
          <EmptyState
            title="No alerts in this view"
            description="Alerts appear here when a transfer is classified as MEDIUM or HIGH risk."
          />
        ) : (
          <div className="table-wrapper">
            <table className="table">
              <thead>
                <tr>
                  <th>Alert</th>
                  <th>Customer</th>
                  <th>Transaction</th>
                  <th className="table__number">Amount</th>
                  <th>Risk</th>
                  <th>Reason</th>
                  <th>Alert status</th>
                  <th>Case</th>
                  <th>Raised at</th>
                </tr>
              </thead>
              <tbody>
                {alerts.map((alert) => (
                  <tr key={alert.id}>
                    <td className="table__mono">{alert.reference}</td>
                    <td>
                      {alert.customerName}
                      <br />
                      <span className="table__muted table__mono">{alert.customerNumber}</span>
                    </td>
                    <td>
                      <span className="table__mono">{alert.transactionReference}</span>
                      <br />
                      <Badge value={alert.transactionStatus} />
                    </td>
                    <td className="table__number">{formatAmount(alert.amount)}</td>
                    <td>
                      <Badge value={alert.riskLevel} />
                      <div className="table__muted">Score {alert.riskScore}</div>
                    </td>
                    <td className="table__reason">{alert.reason}</td>
                    <td>
                      <Badge value={alert.status} />
                    </td>
                    <td>
                      {alert.caseReference ? (
                        <Link to={`/fraud/cases/${alert.caseReference}`} className="table__mono">
                          {alert.caseReference}
                        </Link>
                      ) : (
                        <span className="table__muted">No case</span>
                      )}
                    </td>
                    <td>{formatDateTime(alert.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}
