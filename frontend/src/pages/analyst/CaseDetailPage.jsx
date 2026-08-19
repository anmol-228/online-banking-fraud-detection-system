import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import api from '../../services/bankingService.js';
import { readError, readFieldErrors } from '../../api/client.js';
import { formatAmount, formatDateTime, humanise } from '../../utils/format.js';
import Badge from '../../components/Badge.jsx';
import Loader from '../../components/Loader.jsx';
import Message from '../../components/Message.jsx';

/**
 * Fraud case review and decision (FR-15, FR-18).
 *
 * <p>The analyst reads why the transfer was flagged and then either releases it or blocks it.
 * Remarks are mandatory because the decision is written to the audit trail.</p>
 */
export default function CaseDetailPage() {
  const { reference } = useParams();

  const [fraudCase, setFraudCase] = useState(null);
  const [remarks, setRemarks] = useState('');
  const [loading, setLoading] = useState(true);
  const [working, setWorking] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [fieldErrors, setFieldErrors] = useState({});

  const load = useCallback(async () => {
    try {
      const { data } = await api.fraudCase(reference);
      setFraudCase(data);
      setRemarks(data.remarks || '');
    } catch (err) {
      setError(readError(err));
    } finally {
      setLoading(false);
    }
  }, [reference]);

  useEffect(() => {
    load();
  }, [load]);

  async function assign() {
    setError('');
    setWorking(true);
    try {
      const { data } = await api.assignCase(reference);
      setFraudCase(data);
      setSuccess('This case is now assigned to you.');
    } catch (err) {
      setError(readError(err));
    } finally {
      setWorking(false);
    }
  }

  async function decide(decision) {
    const label = decision === 'APPROVE' ? 'release' : 'block';
    const confirmed = window.confirm(
      `Are you sure you want to ${label} transfer ${fraudCase.transactionReference}? This decision is final and is recorded in the audit trail.`
    );
    if (!confirmed) {
      return;
    }

    setError('');
    setSuccess('');
    setFieldErrors({});
    setWorking(true);
    try {
      const { data } = await api.decideCase(reference, { decision, remarks });
      setFraudCase(data);
      setSuccess(
        decision === 'APPROVE'
          ? 'The transfer has been released and the customer has been notified.'
          : 'The transfer has been blocked and the customer has been notified.'
      );
    } catch (err) {
      setError(readError(err));
      setFieldErrors(readFieldErrors(err));
    } finally {
      setWorking(false);
    }
  }

  if (loading) {
    return <Loader label="Loading case" />;
  }

  if (!fraudCase) {
    return (
      <div className="page">
        <Message tone="error">{error || 'This case could not be found.'}</Message>
        <Link to="/fraud/cases" className="button button--secondary">
          Back to cases
        </Link>
      </div>
    );
  }

  const resolved =
    fraudCase.status === 'RESOLVED_APPROVED' || fraudCase.status === 'RESOLVED_BLOCKED';

  return (
    <div className="page">
      <header className="page__header">
        <div>
          <h1 className="page__title">Case {fraudCase.reference}</h1>
          <p className="page__subtitle">
            Raised from alert {fraudCase.alertReference} on {formatDateTime(fraudCase.createdAt)}
          </p>
        </div>
        <Link to="/fraud/cases" className="button button--ghost">
          Back to cases
        </Link>
      </header>

      <Message tone="error" onDismiss={() => setError('')}>
        {error}
      </Message>
      <Message tone="success" onDismiss={() => setSuccess('')}>
        {success}
      </Message>

      <section className="card">
        <div className="card__header">
          <h2 className="card__title">Transaction under review</h2>
          <Badge value={fraudCase.transactionStatus} />
        </div>
        <dl className="detail-grid">
          <div>
            <dt>Transaction reference</dt>
            <dd className="table__mono">{fraudCase.transactionReference}</dd>
          </div>
          <div>
            <dt>Customer</dt>
            <dd>{fraudCase.customerName}</dd>
          </div>
          <div>
            <dt>Amount</dt>
            <dd className="account-card__amount">{formatAmount(fraudCase.amount)}</dd>
          </div>
          <div>
            <dt>Risk classification</dt>
            <dd>
              <Badge value={fraudCase.riskLevel} /> score {fraudCase.riskScore}
            </dd>
          </div>
        </dl>
        <p className="card__note">
          <strong>Why this was flagged: </strong>
          {fraudCase.riskReason}
        </p>
      </section>

      <section className="card">
        <div className="card__header">
          <h2 className="card__title">Case status</h2>
          <Badge value={fraudCase.status} />
        </div>
        <dl className="detail-grid">
          <div>
            <dt>Status</dt>
            <dd>{humanise(fraudCase.status)}</dd>
          </div>
          <div>
            <dt>Assigned to</dt>
            <dd>{fraudCase.assignedTo || 'Unassigned'}</dd>
          </div>
          <div>
            <dt>Decided by</dt>
            <dd>{fraudCase.decidedBy || '-'}</dd>
          </div>
          <div>
            <dt>Closed at</dt>
            <dd>{formatDateTime(fraudCase.closedAt)}</dd>
          </div>
        </dl>
        {fraudCase.remarks ? (
          <p className="card__note">
            <strong>Remarks: </strong>
            {fraudCase.remarks}
          </p>
        ) : null}
      </section>

      {resolved ? (
        <Message tone="info">
          This case has been resolved as {humanise(fraudCase.status)} and can no longer be changed.
        </Message>
      ) : (
        <section className="card card--highlight">
          <div className="card__header">
            <h2 className="card__title">Record your decision</h2>
            {!fraudCase.assignedTo ? (
              <button
                type="button"
                className="button button--small button--secondary"
                onClick={assign}
                disabled={working}
              >
                Assign to me
              </button>
            ) : null}
          </div>

          <label className="field field--wide">
            <span className="field__label">Remarks (recorded in the audit trail)</span>
            <textarea
              className={fieldErrors.remarks ? 'field__input field__input--error' : 'field__input'}
              rows={3}
              value={remarks}
              onChange={(event) => setRemarks(event.target.value)}
              maxLength={500}
              placeholder="Explain the checks you carried out and what you concluded."
            />
            {fieldErrors.remarks ? <span className="field__error">{fieldErrors.remarks}</span> : null}
          </label>

          <div className="button-row">
            <button
              type="button"
              className="button button--primary"
              onClick={() => decide('APPROVE')}
              disabled={working || remarks.trim().length === 0}
            >
              Approve and release transfer
            </button>
            <button
              type="button"
              className="button button--danger"
              onClick={() => decide('BLOCK')}
              disabled={working || remarks.trim().length === 0}
            >
              Block transfer
            </button>
          </div>
          {remarks.trim().length === 0 ? (
            <p className="card__disclaimer">Enter remarks before recording a decision.</p>
          ) : null}
        </section>
      )}
    </div>
  );
}
