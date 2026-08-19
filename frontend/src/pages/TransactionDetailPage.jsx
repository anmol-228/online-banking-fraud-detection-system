import { useCallback, useEffect, useState } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import api from '../services/bankingService.js';
import { readError } from '../api/client.js';
import { formatAmount, formatDateTime, humanise } from '../utils/format.js';
import Badge from '../components/Badge.jsx';
import Loader from '../components/Loader.jsx';
import Message from '../components/Message.jsx';

/**
 * Transaction details, status and the additional verification step (FR-14, FR-22).
 *
 * <p>When a transfer is waiting for verification the code entry form appears here. In this
 * simulation the code is delivered to the Notifications screen instead of by SMS.</p>
 */
export default function TransactionDetailPage() {
  const { reference } = useParams();
  const location = useLocation();

  const [transaction, setTransaction] = useState(null);
  const [verification, setVerification] = useState(null);
  const [code, setCode] = useState('');
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  const load = useCallback(async () => {
    try {
      const { data } = await api.transaction(reference);
      setTransaction(data);

      if (data.status === 'PENDING_VERIFICATION') {
        try {
          const status = await api.verificationStatus(reference);
          setVerification(status.data);
        } catch {
          // A missing verification record is not fatal; the panel simply is not shown.
          setVerification(null);
        }
      } else {
        setVerification(null);
      }
    } catch (err) {
      setError(readError(err));
    } finally {
      setLoading(false);
    }
  }, [reference]);

  useEffect(() => {
    load();
  }, [load]);

  async function handleVerify(event) {
    event.preventDefault();
    setError('');
    setSuccess('');
    setSubmitting(true);
    try {
      const { data } = await api.submitVerification(reference, code);
      setTransaction(data);
      setVerification(null);
      setCode('');
      setSuccess('Verification successful. Your transfer has been completed.');
    } catch (err) {
      setError(readError(err));
      setCode('');
      await load();
    } finally {
      setSubmitting(false);
    }
  }

  if (loading) {
    return <Loader label="Loading transaction" />;
  }

  if (!transaction) {
    return (
      <div className="page">
        <Message tone="error">{error || 'This transaction could not be found.'}</Message>
        <Link to="/transactions" className="button button--secondary">
          Back to transactions
        </Link>
      </div>
    );
  }

  return (
    <div className="page">
      <header className="page__header">
        <div>
          <h1 className="page__title">Transaction {transaction.reference}</h1>
          <p className="page__subtitle">
            Created {formatDateTime(transaction.createdAt)} by {transaction.initiatedBy}
          </p>
        </div>
        <Link to="/transactions" className="button button--ghost">
          Back to list
        </Link>
      </header>

      {location.state?.justCreated && transaction.status === 'APPROVED' ? (
        <Message tone="success">Your transfer was completed successfully.</Message>
      ) : null}

      <Message tone="error" onDismiss={() => setError('')}>
        {error}
      </Message>
      <Message tone="success" onDismiss={() => setSuccess('')}>
        {success}
      </Message>

      {transaction.status === 'PENDING' ? (
        <Message tone="info">
          This transfer has been held for a security review. A fraud analyst will decide whether it
          can proceed, and you will receive a notification with the outcome.
        </Message>
      ) : null}

      <section className="card">
        <div className="card__header">
          <h2 className="card__title">Transfer details</h2>
          <Badge value={transaction.status} />
        </div>
        <dl className="detail-grid">
          <div>
            <dt>Amount</dt>
            <dd className="account-card__amount">
              {formatAmount(transaction.amount, transaction.currency)}
            </dd>
          </div>
          <div>
            <dt>From account</dt>
            <dd className="table__mono">{transaction.sourceAccountNumber}</dd>
          </div>
          <div>
            <dt>To</dt>
            <dd>
              {transaction.destinationName}
              <br />
              <span className="table__mono table__muted">{transaction.destinationAccountNumber}</span>
            </dd>
          </div>
          <div>
            <dt>Status</dt>
            <dd>
              <Badge value={transaction.status} />
            </dd>
          </div>
          <div>
            <dt>Reference note</dt>
            <dd>{transaction.description || '-'}</dd>
          </div>
          <div>
            <dt>Completed at</dt>
            <dd>{formatDateTime(transaction.completedAt)}</dd>
          </div>
        </dl>
        {transaction.statusReason ? (
          <p className="card__note">{transaction.statusReason}</p>
        ) : null}
      </section>

      <section className="card">
        <div className="card__header">
          <h2 className="card__title">Risk assessment</h2>
          <Badge value={transaction.riskLevel} />
        </div>
        <dl className="detail-grid">
          <div>
            <dt>Risk classification</dt>
            <dd>{humanise(transaction.riskLevel)}</dd>
          </div>
          <div>
            <dt>Risk score</dt>
            <dd>{transaction.riskScore}</dd>
          </div>
        </dl>
        <p className="card__note">
          <strong>Why: </strong>
          {transaction.riskReason || 'No risk indicators were triggered.'}
        </p>
        <p className="card__disclaimer">
          Risk bands: LOW 0 to 29, MEDIUM 30 to 59, HIGH 60 and above. Fraud monitoring is
          intentionally simplified for demonstration and is not a production
          fraud-detection model.
        </p>
      </section>

      {transaction.status === 'PENDING_VERIFICATION' ? (
        <section className="card card--highlight">
          <div className="card__header">
            <h2 className="card__title">Additional verification required</h2>
          </div>
          <p className="card__note">
            A six digit verification code has been sent to your{' '}
            <Link to="/notifications">Notifications</Link> page. Enter it below to release this
            transfer.
          </p>

          {verification ? (
            <p className="card__note">
              Attempts remaining: <strong>{verification.attemptsRemaining}</strong> of{' '}
              {verification.maxAttempts}. The code expires at {formatDateTime(verification.expiresAt)}.
            </p>
          ) : null}

          <form onSubmit={handleVerify} className="inline-form" noValidate>
            <label className="field">
              <span className="field__label">Verification code</span>
              <input
                className="field__input field__input--code"
                value={code}
                onChange={(event) => setCode(event.target.value.replace(/\D/g, '').slice(0, 6))}
                inputMode="numeric"
                maxLength={6}
                placeholder="000000"
                required
              />
            </label>
            <button
              type="submit"
              className="button button--primary"
              disabled={submitting || code.length !== 6}
            >
              {submitting ? 'Verifying...' : 'Verify and complete'}
            </button>
          </form>
        </section>
      ) : null}

      {transaction.status === 'APPROVED' ? (
        <section className="card">
          <div className="card__header">
            <h2 className="card__title">Something wrong with this transfer?</h2>
          </div>
          <p className="card__note">
            If you did not authorise this transfer you can raise a complaint and our operations team
            will look into it.
          </p>
          <Link
            to="/disputes"
            state={{ transactionReference: transaction.reference }}
            className="button button--secondary"
          >
            Raise a complaint
          </Link>
        </section>
      ) : null}
    </div>
  );
}
