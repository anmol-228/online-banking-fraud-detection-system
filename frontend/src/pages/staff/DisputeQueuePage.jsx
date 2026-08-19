import { useEffect, useState } from 'react';
import api from '../../services/bankingService.js';
import { readError, readFieldErrors } from '../../api/client.js';
import { formatAmount, formatDateTime } from '../../utils/format.js';
import Badge from '../../components/Badge.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import Loader from '../../components/Loader.jsx';
import Message from '../../components/Message.jsx';

/** Complaint queue for the operations officer (FR-17). */
export default function DisputeQueuePage() {
  const [disputes, setDisputes] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [fieldErrors, setFieldErrors] = useState({});
  const [selected, setSelected] = useState(null);
  const [status, setStatus] = useState('UNDER_REVIEW');
  const [resolution, setResolution] = useState('');
  const [saving, setSaving] = useState(false);

  async function load() {
    try {
      const { data } = await api.disputeQueue();
      setDisputes(data);
    } catch (err) {
      setError(readError(err));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load();
  }, []);

  function startWorking(dispute) {
    setSelected(dispute);
    setStatus(dispute.status === 'OPEN' ? 'UNDER_REVIEW' : 'RESOLVED');
    setResolution(dispute.resolution || '');
    setError('');
    setSuccess('');
    setFieldErrors({});
  }

  async function submit(event) {
    event.preventDefault();
    setSaving(true);
    setError('');
    setFieldErrors({});
    try {
      await api.resolveDispute(selected.reference, { status, resolution });
      setSuccess(`Complaint ${selected.reference} has been updated to ${status}.`);
      setSelected(null);
      setResolution('');
      await load();
    } catch (err) {
      setError(readError(err));
      setFieldErrors(readFieldErrors(err));
    } finally {
      setSaving(false);
    }
  }

  const openCount = disputes.filter(
    (dispute) => dispute.status === 'OPEN' || dispute.status === 'UNDER_REVIEW'
  ).length;

  return (
    <div className="page">
      <header className="page__header">
        <div>
          <h1 className="page__title">Complaint queue</h1>
          <p className="page__subtitle">
            Customer complaints about transactions. Recording an outcome notifies the customer.
          </p>
        </div>
      </header>

      <Message tone="error" onDismiss={() => setError('')}>
        {error}
      </Message>
      <Message tone="success" onDismiss={() => setSuccess('')}>
        {success}
      </Message>

      <section className="stat-grid">
        <div className="stat-card">
          <p className="stat-card__label">Total complaints</p>
          <p className="stat-card__value">{disputes.length}</p>
        </div>
        <div className="stat-card stat-card--warning">
          <p className="stat-card__label">Needing attention</p>
          <p className="stat-card__value">{openCount}</p>
        </div>
      </section>

      {selected ? (
        <section className="card card--highlight">
          <div className="card__header">
            <h2 className="card__title">Work on complaint {selected.reference}</h2>
            <button type="button" className="card__action" onClick={() => setSelected(null)}>
              Cancel
            </button>
          </div>

          <dl className="detail-grid">
            <div>
              <dt>Customer</dt>
              <dd>{selected.customerName}</dd>
            </div>
            <div>
              <dt>Transaction</dt>
              <dd className="table__mono">{selected.transactionReference}</dd>
            </div>
            <div>
              <dt>Amount</dt>
              <dd>{formatAmount(selected.transactionAmount)}</dd>
            </div>
            <div>
              <dt>Subject</dt>
              <dd>{selected.subject}</dd>
            </div>
          </dl>
          <p className="card__note">
            <strong>Customer description: </strong>
            {selected.description}
          </p>

          <form onSubmit={submit} noValidate>
            <div className="form-grid">
              <label className="field">
                <span className="field__label">New status</span>
                <select
                  className="field__input"
                  value={status}
                  onChange={(event) => setStatus(event.target.value)}
                >
                  <option value="UNDER_REVIEW">Under review</option>
                  <option value="RESOLVED">Resolved</option>
                  <option value="REJECTED">Rejected</option>
                </select>
              </label>

              <label className="field field--wide">
                <span className="field__label">Outcome notes sent to the customer</span>
                <textarea
                  className={
                    fieldErrors.resolution ? 'field__input field__input--error' : 'field__input'
                  }
                  rows={3}
                  value={resolution}
                  onChange={(event) => setResolution(event.target.value)}
                  maxLength={1000}
                  required
                />
                {fieldErrors.resolution ? (
                  <span className="field__error">{fieldErrors.resolution}</span>
                ) : null}
              </label>
            </div>

            <button
              type="submit"
              className="button button--primary"
              disabled={saving || resolution.trim().length === 0}
            >
              {saving ? 'Saving...' : 'Update complaint'}
            </button>
          </form>
        </section>
      ) : null}

      <section className="card">
        {loading ? (
          <Loader label="Loading complaints" />
        ) : disputes.length === 0 ? (
          <EmptyState
            title="The queue is empty"
            description="Complaints raised by customers will appear here."
          />
        ) : (
          <div className="table-wrapper">
            <table className="table">
              <thead>
                <tr>
                  <th>Reference</th>
                  <th>Customer</th>
                  <th>Transaction</th>
                  <th className="table__number">Amount</th>
                  <th>Subject</th>
                  <th>Status</th>
                  <th>Handled by</th>
                  <th>Raised on</th>
                  <th aria-label="Actions" />
                </tr>
              </thead>
              <tbody>
                {disputes.map((dispute) => (
                  <tr key={dispute.id}>
                    <td className="table__mono">{dispute.reference}</td>
                    <td>{dispute.customerName}</td>
                    <td className="table__mono">{dispute.transactionReference}</td>
                    <td className="table__number">{formatAmount(dispute.transactionAmount)}</td>
                    <td>{dispute.subject}</td>
                    <td>
                      <Badge value={dispute.status} />
                    </td>
                    <td>{dispute.handledBy || <span className="table__muted">-</span>}</td>
                    <td>{formatDateTime(dispute.createdAt)}</td>
                    <td>
                      {dispute.status === 'RESOLVED' || dispute.status === 'REJECTED' ? (
                        <span className="table__muted">Closed</span>
                      ) : (
                        <button
                          type="button"
                          className="button button--small button--secondary"
                          onClick={() => startWorking(dispute)}
                        >
                          Work on it
                        </button>
                      )}
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
