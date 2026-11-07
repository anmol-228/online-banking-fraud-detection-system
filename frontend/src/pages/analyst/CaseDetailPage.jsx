import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import api from '../../services/bankingService.js';
import { readBlobError, readError, readFieldErrors } from '../../api/client.js';
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
  const [recipients, setRecipients] = useState([]);
  const [recipientKey, setRecipientKey] = useState('');
  const [handoff, setHandoff] = useState(null);

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

  useEffect(() => {
    api
      .escalationRecipients()
      .then(({ data }) => {
        setRecipients(data);
        if (data.length > 0) {
          setRecipientKey(data[0].key);
        }
      })
      .catch(() => setRecipients([]));
  }, []);

  /** Downloads the server-built evidence file. The server records the export and its hash. */
  async function exportEvidence() {
    setError('');
    setWorking(true);
    try {
      const response = await api.downloadEvidence(reference);
      const url = URL.createObjectURL(new Blob([response.data], { type: 'application/json' }));
      const link = document.createElement('a');
      link.href = url;
      link.download = `evidence-${reference}.json`;
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
      setSuccess('Evidence file downloaded. Its SHA-256 hash is recorded in the audit trail.');
    } catch (err) {
      setError(await readBlobError(err));
    } finally {
      setWorking(false);
    }
  }

  async function prepareHandoff() {
    setError('');
    setWorking(true);
    try {
      const { data } = await api.escalate(reference, recipientKey);
      setHandoff(data);
    } catch (err) {
      setError(readError(err));
    } finally {
      setWorking(false);
    }
  }

  async function attestSent() {
    setError('');
    setWorking(true);
    try {
      const { data } = await api.attestEscalation(reference);
      setHandoff(data);
      setSuccess(data.notice);
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
          <div>
            <dt>Review target</dt>
            <dd>{fraudCase.reviewDueAt ? formatDateTime(fraudCase.reviewDueAt) : '-'}</dd>
          </div>
          <div>
            <dt>Customer statement</dt>
            <dd>
              {fraudCase.customerResponse
                ? `${fraudCase.customerResponse === 'NOT_MINE' ? 'Says it is NOT theirs' : 'Says it is theirs'} (${formatDateTime(fraudCase.customerRespondedAt)})`
                : 'None recorded'}
            </dd>
          </div>
        </dl>
        {fraudCase.customerResponse ? (
          <p className="card__disclaimer">
            The customer statement is an unverified same-session statement. It is evidence, not
            identity verification, and it never releases or blocks the transfer by itself.
            {fraudCase.customerResponse === 'NOT_MINE'
              ? ' Because the customer reports it as not theirs, approval is disabled: block it, or record why the report was wrong.'
              : ''}
          </p>
        ) : null}
        <p className="card__disclaimer">
          The review target is a target, not a deadline: nothing is released automatically when it
          passes.
        </p>
        {fraudCase.remarks ? (
          <p className="card__note">
            <strong>Remarks: </strong>
            {fraudCase.remarks}
          </p>
        ) : null}
      </section>

      <section className="card">
        <div className="card__header">
          <h2 className="card__title">Evidence and handoff</h2>
        </div>
        <p className="card__note">
          Export a minimal evidence file for this case (account numbers masked, no contact details, and
          no free text: analyst remarks are not included). Its hash is recorded in the audit trail.
        </p>
        <div className="button-row">
          <button type="button" className="button button--secondary" onClick={exportEvidence} disabled={working}>
            Export evidence (JSON)
          </button>
        </div>

        {recipients.length > 0 ? (
          <>
            <p className="card__note">
              Prepare an e-mail <strong>draft</strong> to one of the team mailboxes configured by your
              administrator. <strong>This system sends nothing.</strong> Opening the draft is not
              delivery: send it from your own mail program, attach the exported file, and confirm with
              the recipient that it arrived.
            </p>
            <div className="inline-form">
              <label className="field">
                <span className="field__label">Team mailbox</span>
                <select
                  className="field__input"
                  value={recipientKey}
                  onChange={(event) => setRecipientKey(event.target.value)}
                >
                  {recipients.map((recipient) => (
                    <option key={recipient.key} value={recipient.key}>
                      {recipient.key} ({recipient.mailbox})
                    </option>
                  ))}
                </select>
              </label>
              <button type="button" className="button button--secondary" onClick={prepareHandoff} disabled={working || !recipientKey}>
                Prepare e-mail draft
              </button>
            </div>
          </>
        ) : (
          <p className="card__disclaimer">
            No team mailbox is configured, so the mail-draft handoff is switched off.
          </p>
        )}

        {handoff ? (
          <div className="card__note">
            <p>
              <strong>{handoff.state === 'ESCALATION_USER_ATTESTED_SENT' ? 'Recorded (self-reported)' : 'Draft ready'}:</strong>{' '}
              {handoff.notice}
            </p>
            {handoff.mailto ? (
              <p>
                <a href={handoff.mailto} className="button button--primary">
                  Open the e-mail draft
                </a>{' '}
                <button type="button" className="button button--ghost" onClick={attestSent} disabled={working}>
                  I sent it
                </button>
              </p>
            ) : null}
          </div>
        ) : null}

        <p className="card__disclaimer">
          This is a training system: it never reports to a bank, the police or a regulator. If a real
          customer were affected, <Link to="/help/report-fraud">see the official reporting channels</Link>.
        </p>
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
              disabled={
                working || remarks.trim().length === 0 || fraudCase.customerResponse === 'NOT_MINE'
              }
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
