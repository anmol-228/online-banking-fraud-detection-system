import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import api from '../services/bankingService.js';
import { readError } from '../api/client.js';
import { formatAmount, formatDateTime } from '../utils/format.js';
import Badge from '../components/Badge.jsx';
import EmptyState from '../components/EmptyState.jsx';
import Loader from '../components/Loader.jsx';
import Message from '../components/Message.jsx';

/** Transaction history (FR-10, FR-22). */
export default function TransactionsPage() {
  const [page, setPage] = useState(0);
  const [result, setResult] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    api
      .transactions(page, 10)
      .then(({ data }) => {
        if (!cancelled) {
          setResult(data);
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
  }, [page]);

  return (
    <div className="page">
      <header className="page__header">
        <div>
          <h1 className="page__title">Transaction history</h1>
          <p className="page__subtitle">
            Every transfer you have made, with its current status and risk classification.
          </p>
        </div>
        <Link to="/transfer" className="button button--primary">
          New transfer
        </Link>
      </header>

      <Message tone="error" onDismiss={() => setError('')}>
        {error}
      </Message>

      <section className="card">
        {loading ? (
          <Loader label="Loading transactions" />
        ) : !result || result.content.length === 0 ? (
          <EmptyState
            title="No transactions to show"
            description="Transfers you make will be listed here with their status."
            action={
              <Link to="/transfer" className="button button--primary">
                Make your first transfer
              </Link>
            }
          />
        ) : (
          <>
            <div className="table-wrapper">
              <table className="table">
                <thead>
                  <tr>
                    <th>Reference</th>
                    <th>From</th>
                    <th>To</th>
                    <th className="table__number">Amount</th>
                    <th>Status</th>
                    <th>Risk</th>
                    <th>Date</th>
                  </tr>
                </thead>
                <tbody>
                  {result.content.map((transaction) => (
                    <tr key={transaction.reference}>
                      <td className="table__mono">
                        <Link to={`/transactions/${transaction.reference}`}>
                          {transaction.reference}
                        </Link>
                      </td>
                      <td className="table__mono">{transaction.sourceAccountNumber}</td>
                      <td>
                        {transaction.destinationName}
                        <br />
                        <span className="table__muted table__mono">
                          {transaction.destinationAccountNumber}
                        </span>
                      </td>
                      <td className="table__number">
                        {formatAmount(transaction.amount, transaction.currency)}
                      </td>
                      <td>
                        <Badge value={transaction.status} />
                        {transaction.verificationRequired ? (
                          <div className="table__muted">Verification needed</div>
                        ) : null}
                      </td>
                      <td>
                        <Badge value={transaction.riskLevel} />
                        <div className="table__muted">Score {transaction.riskScore}</div>
                      </td>
                      <td>{formatDateTime(transaction.createdAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <div className="pagination">
              <button
                type="button"
                className="button button--ghost button--small"
                onClick={() => setPage((current) => Math.max(0, current - 1))}
                disabled={page === 0}
              >
                Previous
              </button>
              <span className="pagination__label">
                Page {result.page + 1} of {Math.max(result.totalPages, 1)} ({result.totalElements}{' '}
                transactions)
              </span>
              <button
                type="button"
                className="button button--ghost button--small"
                onClick={() => setPage((current) => current + 1)}
                disabled={result.last}
              >
                Next
              </button>
            </div>
          </>
        )}
      </section>
    </div>
  );
}
