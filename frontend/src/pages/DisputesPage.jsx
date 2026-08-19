import { useEffect, useState } from 'react';
import { useLocation } from 'react-router-dom';
import api from '../services/bankingService.js';
import { readError, readFieldErrors } from '../api/client.js';
import { formatAmount, formatDateTime } from '../utils/format.js';
import Badge from '../components/Badge.jsx';
import EmptyState from '../components/EmptyState.jsx';
import Loader from '../components/Loader.jsx';
import Message from '../components/Message.jsx';

/** Customer complaints and disputes (FR-17). */
export default function DisputesPage() {
  const location = useLocation();

  const [disputes, setDisputes] = useState([]);
  const [transactions, setTransactions] = useState([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [fieldErrors, setFieldErrors] = useState({});

  const [form, setForm] = useState({
    transactionReference: location.state?.transactionReference || '',
    subject: '',
    description: '',
  });

  async function load() {
    try {
      const [disputesResponse, transactionsResponse] = await Promise.all([
        api.myDisputes(),
        api.transactions(0, 50),
      ]);
      setDisputes(disputesResponse.data);
      setTransactions(transactionsResponse.data.content);
    } catch (err) {
      setError(readError(err));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load();
  }, []);

  function update(field, value) {
    setForm((current) => ({ ...current, [field]: value }));
  }

  async function handleSubmit(event) {
    event.preventDefault();
    setError('');
    setSuccess('');
    setFieldErrors({});
    setSaving(true);
    try {
      const { data } = await api.submitDispute(form);
      setSuccess(`Your complaint has been registered with reference ${data.reference}.`);
      setForm({ transactionReference: '', subject: '', description: '' });
      await load();
    } catch (err) {
      setError(readError(err));
      setFieldErrors(readFieldErrors(err));
    } finally {
      setSaving(false);
    }
  }

  if (loading) {
    return <Loader label="Loading your complaints" />;
  }

  return (
    <div className="page">
      <header className="page__header">
        <div>
          <h1 className="page__title">Complaints and disputes</h1>
          <p className="page__subtitle">
            Report a transaction you do not recognise. Our operations team will review it and reply.
          </p>
        </div>
      </header>

      <Message tone="error" onDismiss={() => setError('')}>
        {error}
      </Message>
      <Message tone="success" onDismiss={() => setSuccess('')}>
        {success}
      </Message>

      <section className="card">
        <div className="card__header">
          <h2 className="card__title">Raise a complaint</h2>
        </div>

        {transactions.length === 0 ? (
          <EmptyState
            title="You have no transactions to dispute"
            description="A complaint can only be raised against a transfer you have made."
          />
        ) : (
          <form onSubmit={handleSubmit} noValidate>
            <div className="form-grid">
              <label className="field">
                <span className="field__label">Transaction</span>
                <select
                  className={
                    fieldErrors.transactionReference
                      ? 'field__input field__input--error'
                      : 'field__input'
                  }
                  value={form.transactionReference}
                  onChange={(event) => update('transactionReference', event.target.value)}
                  required
                >
                  <option value="">Select a transaction</option>
                  {transactions.map((transaction) => (
                    <option key={transaction.reference} value={transaction.reference}>
                      {transaction.reference} - {formatAmount(transaction.amount, transaction.currency)}{' '}
                      to {transaction.destinationName}
                    </option>
                  ))}
                </select>
                {fieldErrors.transactionReference ? (
                  <span className="field__error">{fieldErrors.transactionReference}</span>
                ) : null}
              </label>

              <label className="field">
                <span className="field__label">Subject</span>
                <input
                  className={fieldErrors.subject ? 'field__input field__input--error' : 'field__input'}
                  value={form.subject}
                  onChange={(event) => update('subject', event.target.value)}
                  maxLength={150}
                  required
                />
                {fieldErrors.subject ? <span className="field__error">{fieldErrors.subject}</span> : null}
              </label>

              <label className="field field--wide">
                <span className="field__label">What happened?</span>
                <textarea
                  className={
                    fieldErrors.description ? 'field__input field__input--error' : 'field__input'
                  }
                  rows={4}
                  value={form.description}
                  onChange={(event) => update('description', event.target.value)}
                  maxLength={1000}
                  required
                />
                {fieldErrors.description ? (
                  <span className="field__error">{fieldErrors.description}</span>
                ) : (
                  <span className="field__hint">At least 10 characters.</span>
                )}
              </label>
            </div>

            <button type="submit" className="button button--primary" disabled={saving}>
              {saving ? 'Submitting...' : 'Submit complaint'}
            </button>
          </form>
        )}
      </section>

      <section className="card">
        <div className="card__header">
          <h2 className="card__title">Your complaints</h2>
        </div>
        {disputes.length === 0 ? (
          <EmptyState
            title="No complaints raised"
            description="Any complaint you submit will be tracked here until it is resolved."
          />
        ) : (
          <div className="table-wrapper">
            <table className="table">
              <thead>
                <tr>
                  <th>Reference</th>
                  <th>Transaction</th>
                  <th className="table__number">Amount</th>
                  <th>Subject</th>
                  <th>Status</th>
                  <th>Outcome</th>
                  <th>Raised on</th>
                </tr>
              </thead>
              <tbody>
                {disputes.map((dispute) => (
                  <tr key={dispute.id}>
                    <td className="table__mono">{dispute.reference}</td>
                    <td className="table__mono">{dispute.transactionReference}</td>
                    <td className="table__number">{formatAmount(dispute.transactionAmount)}</td>
                    <td>{dispute.subject}</td>
                    <td>
                      <Badge value={dispute.status} />
                    </td>
                    <td>{dispute.resolution || 'Awaiting review'}</td>
                    <td>{formatDateTime(dispute.createdAt)}</td>
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
