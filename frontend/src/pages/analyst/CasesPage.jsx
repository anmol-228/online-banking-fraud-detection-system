import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import api from '../../services/bankingService.js';
import { readError } from '../../api/client.js';
import { formatAmount, formatDateTime } from '../../utils/format.js';
import Badge from '../../components/Badge.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import Loader from '../../components/Loader.jsx';
import Message from '../../components/Message.jsx';

/** Fraud case list (FR-18). */
export default function CasesPage() {
  const [cases, setCases] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    let cancelled = false;
    api
      .fraudCases()
      .then(({ data }) => {
        if (!cancelled) {
          setCases(data);
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
  }, []);

  const openCases = cases.filter(
    (item) => item.status === 'OPEN' || item.status === 'UNDER_REVIEW'
  ).length;

  return (
    <div className="page">
      <header className="page__header">
        <div>
          <h1 className="page__title">Fraud cases</h1>
          <p className="page__subtitle">
            Investigations opened from a fraud alert. Resolving a case releases or blocks the held
            transfer.
          </p>
        </div>
      </header>

      <Message tone="error" onDismiss={() => setError('')}>
        {error}
      </Message>

      <section className="stat-grid">
        <div className="stat-card">
          <p className="stat-card__label">Total cases</p>
          <p className="stat-card__value">{cases.length}</p>
        </div>
        <div className="stat-card stat-card--warning">
          <p className="stat-card__label">Awaiting a decision</p>
          <p className="stat-card__value">{openCases}</p>
        </div>
      </section>

      <section className="card">
        {loading ? (
          <Loader label="Loading fraud cases" />
        ) : cases.length === 0 ? (
          <EmptyState
            title="No fraud cases"
            description="A case is opened automatically when a transfer is classified HIGH risk, or after repeated verification failures."
          />
        ) : (
          <div className="table-wrapper">
            <table className="table">
              <thead>
                <tr>
                  <th>Case</th>
                  <th>Customer</th>
                  <th>Transaction</th>
                  <th className="table__number">Amount</th>
                  <th>Risk</th>
                  <th>Case status</th>
                  <th>Assigned to</th>
                  <th>Opened</th>
                  <th aria-label="Actions" />
                </tr>
              </thead>
              <tbody>
                {cases.map((fraudCase) => (
                  <tr key={fraudCase.id}>
                    <td className="table__mono">{fraudCase.reference}</td>
                    <td>{fraudCase.customerName}</td>
                    <td>
                      <span className="table__mono">{fraudCase.transactionReference}</span>
                      <br />
                      <Badge value={fraudCase.transactionStatus} />
                    </td>
                    <td className="table__number">{formatAmount(fraudCase.amount)}</td>
                    <td>
                      <Badge value={fraudCase.riskLevel} />
                      <div className="table__muted">Score {fraudCase.riskScore}</div>
                    </td>
                    <td>
                      <Badge value={fraudCase.status} />
                    </td>
                    <td>{fraudCase.assignedTo || <span className="table__muted">Unassigned</span>}</td>
                    <td>{formatDateTime(fraudCase.createdAt)}</td>
                    <td>
                      <Link
                        to={`/fraud/cases/${fraudCase.reference}`}
                        className="button button--small button--secondary"
                      >
                        Review
                      </Link>
                    </td>
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
