import { useEffect, useState } from 'react';
import api from '../../services/bankingService.js';
import { readError } from '../../api/client.js';
import { formatAmount, formatDateTime, formatNumber } from '../../utils/format.js';
import Loader from '../../components/Loader.jsx';
import Message from '../../components/Message.jsx';

/** Basic operational and fraud reports (FR-21). */
export default function ReportsPage() {
  const [operational, setOperational] = useState(null);
  const [fraud, setFraud] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  async function load() {
    setLoading(true);
    setError('');
    try {
      const [operationalResponse, fraudResponse] = await Promise.all([
        api.operationalReport(),
        api.fraudReport(),
      ]);
      setOperational(operationalResponse.data);
      setFraud(fraudResponse.data);
    } catch (err) {
      setError(readError(err));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load();
  }, []);

  if (loading) {
    return <Loader label="Generating reports" />;
  }

  const riskTotal = fraud
    ? fraud.lowRiskTransactions + fraud.mediumRiskTransactions + fraud.highRiskTransactions
    : 0;

  function share(count) {
    if (riskTotal === 0) {
      return 0;
    }
    return Math.round((count / riskTotal) * 100);
  }

  return (
    <div className="page">
      <header className="page__header">
        <div>
          <h1 className="page__title">Reports</h1>
          <p className="page__subtitle">
            Operational and fraud figures counted directly from the stored transaction data.
          </p>
        </div>
        <button type="button" className="button button--secondary" onClick={load}>
          Refresh
        </button>
      </header>

      <Message tone="error" onDismiss={() => setError('')}>
        {error}
      </Message>

      {operational ? (
        <section className="card">
          <div className="card__header">
            <h2 className="card__title">Operational report</h2>
            <span className="table__muted">Generated {formatDateTime(operational.generatedAt)}</span>
          </div>
          <div className="stat-grid stat-grid--compact">
            <div className="stat-card">
              <p className="stat-card__label">Customers</p>
              <p className="stat-card__value">{formatNumber(operational.totalCustomers)}</p>
            </div>
            <div className="stat-card">
              <p className="stat-card__label">Accounts</p>
              <p className="stat-card__value">{formatNumber(operational.totalAccounts)}</p>
            </div>
            <div className="stat-card">
              <p className="stat-card__label">Transactions</p>
              <p className="stat-card__value">{formatNumber(operational.totalTransactions)}</p>
            </div>
            <div className="stat-card stat-card--success">
              <p className="stat-card__label">Approved</p>
              <p className="stat-card__value">{formatNumber(operational.approvedTransactions)}</p>
            </div>
            <div className="stat-card stat-card--warning">
              <p className="stat-card__label">Held for review</p>
              <p className="stat-card__value">{formatNumber(operational.pendingTransactions)}</p>
            </div>
            <div className="stat-card stat-card--warning">
              <p className="stat-card__label">Awaiting verification</p>
              <p className="stat-card__value">
                {formatNumber(operational.pendingVerificationTransactions)}
              </p>
            </div>
            <div className="stat-card stat-card--danger">
              <p className="stat-card__label">Blocked</p>
              <p className="stat-card__value">{formatNumber(operational.blockedTransactions)}</p>
            </div>
            <div className="stat-card stat-card--danger">
              <p className="stat-card__label">Failed</p>
              <p className="stat-card__value">{formatNumber(operational.failedTransactions)}</p>
            </div>
            <div className="stat-card">
              <p className="stat-card__label">Value approved</p>
              <p className="stat-card__value">{formatAmount(operational.totalApprovedAmount)}</p>
            </div>
            <div className="stat-card stat-card--warning">
              <p className="stat-card__label">Open complaints</p>
              <p className="stat-card__value">{formatNumber(operational.openDisputes)}</p>
            </div>
          </div>
        </section>
      ) : null}

      {fraud ? (
        <section className="card">
          <div className="card__header">
            <h2 className="card__title">Fraud report</h2>
            <span className="table__muted">Generated {formatDateTime(fraud.generatedAt)}</span>
          </div>

          <h3 className="card__subtitle">Risk mix</h3>
          <div className="bar-chart">
            {[
              { label: 'Low risk', value: fraud.lowRiskTransactions, tone: 'success' },
              { label: 'Medium risk', value: fraud.mediumRiskTransactions, tone: 'warning' },
              { label: 'High risk', value: fraud.highRiskTransactions, tone: 'danger' },
            ].map((row) => (
              <div key={row.label} className="bar-chart__row">
                <span className="bar-chart__label">{row.label}</span>
                <span className="bar-chart__track">
                  <span
                    className={`bar-chart__fill bar-chart__fill--${row.tone}`}
                    style={{ width: `${share(row.value)}%` }}
                  />
                </span>
                <span className="bar-chart__value">
                  {formatNumber(row.value)} ({share(row.value)}%)
                </span>
              </div>
            ))}
          </div>

          <div className="stat-grid stat-grid--compact">
            <div className="stat-card">
              <p className="stat-card__label">Alerts raised</p>
              <p className="stat-card__value">{formatNumber(fraud.totalAlerts)}</p>
            </div>
            <div className="stat-card stat-card--warning">
              <p className="stat-card__label">Open alerts</p>
              <p className="stat-card__value">{formatNumber(fraud.openAlerts)}</p>
            </div>
            <div className="stat-card">
              <p className="stat-card__label">Closed alerts</p>
              <p className="stat-card__value">{formatNumber(fraud.closedAlerts)}</p>
            </div>
            <div className="stat-card">
              <p className="stat-card__label">Cases</p>
              <p className="stat-card__value">{formatNumber(fraud.totalCases)}</p>
            </div>
            <div className="stat-card stat-card--warning">
              <p className="stat-card__label">Open cases</p>
              <p className="stat-card__value">{formatNumber(fraud.openCases)}</p>
            </div>
            <div className="stat-card stat-card--success">
              <p className="stat-card__label">Cases approved</p>
              <p className="stat-card__value">{formatNumber(fraud.casesApproved)}</p>
            </div>
            <div className="stat-card stat-card--danger">
              <p className="stat-card__label">Cases blocked</p>
              <p className="stat-card__value">{formatNumber(fraud.casesBlocked)}</p>
            </div>
            <div className="stat-card">
              <p className="stat-card__label">Alert rate</p>
              <p className="stat-card__value">{fraud.detectionRatePercent}%</p>
            </div>
          </div>

          <p className="card__disclaimer">
            The alert rate is the share of all transactions that raised an alert. It measures how
            often the monitoring rules fired, not how accurate they were. Fraud monitoring is
            intentionally simplified for demonstration and is not a production
            fraud-detection model.
          </p>
        </section>
      ) : null}
    </div>
  );
}
