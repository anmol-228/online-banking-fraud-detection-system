import { useEffect, useState } from 'react';
import api from '../../services/bankingService.js';
import { readError } from '../../api/client.js';
import { formatDateTime, humanise } from '../../utils/format.js';
import Badge from '../../components/Badge.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import Loader from '../../components/Loader.jsx';
import Message from '../../components/Message.jsx';

const ACTIONS = [
  '',
  'LOGIN_SUCCESS',
  'LOGIN_FAILURE',
  'REGISTER',
  'BENEFICIARY_ADDED',
  'BENEFICIARY_REMOVED',
  'TRANSFER_INITIATED',
  'TRANSFER_REJECTED',
  'RISK_EVALUATED',
  'FRAUD_ALERT_RAISED',
  'VERIFICATION_REQUESTED',
  'VERIFICATION_SUCCESS',
  'VERIFICATION_FAILED',
  'TRANSACTION_APPROVED',
  'TRANSACTION_BLOCKED',
  'FRAUD_CASE_OPENED',
  'FRAUD_CASE_DECIDED',
  'DISPUTE_SUBMITTED',
  'DISPUTE_RESOLVED',
  'USER_ROLES_UPDATED',
  'USER_STATUS_UPDATED',
  'REPORT_GENERATED',
];

/** Audit trail viewer (FR-20, NFR-10). */
export default function AuditPage() {
  const [entries, setEntries] = useState(null);
  const [username, setUsername] = useState('');
  const [action, setAction] = useState('');
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    const params = { page, size: 25 };
    if (username.trim()) {
      params.username = username.trim();
    }
    if (action) {
      params.action = action;
    }

    api
      .audit(params)
      .then(({ data }) => {
        if (!cancelled) {
          setEntries(data);
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
  }, [page, username, action]);

  function applyUsername(event) {
    event.preventDefault();
    setPage(0);
  }

  return (
    <div className="page">
      <header className="page__header">
        <div>
          <h1 className="page__title">Audit logs</h1>
          <p className="page__subtitle">
            Every login, transfer, risk decision, verification, fraud decision and administrative
            change is recorded here.
          </p>
        </div>
      </header>

      <Message tone="error" onDismiss={() => setError('')}>
        {error}
      </Message>

      <section className="card">
        <form className="filter-bar" onSubmit={applyUsername}>
          <label className="field">
            <span className="field__label">Username</span>
            <input
              className="field__input"
              value={username}
              onChange={(event) => {
                setUsername(event.target.value);
                setPage(0);
              }}
              placeholder="Filter by username"
            />
          </label>

          <label className="field">
            <span className="field__label">Action</span>
            <select
              className="field__input"
              value={action}
              onChange={(event) => {
                setAction(event.target.value);
                setPage(0);
              }}
            >
              {ACTIONS.map((item) => (
                <option key={item || 'all'} value={item}>
                  {item ? humanise(item) : 'All actions'}
                </option>
              ))}
            </select>
          </label>
        </form>
      </section>

      <section className="card">
        {loading ? (
          <Loader label="Loading audit entries" />
        ) : !entries || entries.content.length === 0 ? (
          <EmptyState
            title="No audit entries match this filter"
            description="Try clearing the username or choosing a different action."
          />
        ) : (
          <>
            <div className="table-wrapper">
              <table className="table">
                <thead>
                  <tr>
                    <th>When</th>
                    <th>User</th>
                    <th>Roles</th>
                    <th>Action</th>
                    <th>Entity</th>
                    <th>Reference</th>
                    <th>Details</th>
                    <th>Outcome</th>
                  </tr>
                </thead>
                <tbody>
                  {entries.content.map((entry) => (
                    <tr key={entry.id}>
                      <td>{formatDateTime(entry.occurredAt)}</td>
                      <td className="table__mono">{entry.username}</td>
                      <td className="table__muted">{entry.roles || '-'}</td>
                      <td>{humanise(entry.action)}</td>
                      <td>{entry.entityType || '-'}</td>
                      <td className="table__mono">{entry.entityReference || '-'}</td>
                      <td className="table__reason">{entry.details || '-'}</td>
                      <td>
                        <Badge value={entry.outcome} />
                      </td>
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
                Page {entries.page + 1} of {Math.max(entries.totalPages, 1)} ({entries.totalElements}{' '}
                entries)
              </span>
              <button
                type="button"
                className="button button--ghost button--small"
                onClick={() => setPage((current) => current + 1)}
                disabled={entries.last}
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
