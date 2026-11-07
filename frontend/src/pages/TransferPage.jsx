import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import api from '../services/bankingService.js';
import { readError, readFieldErrors } from '../api/client.js';
import { formatAmount, newIdempotencyKey } from '../utils/format.js';
import {
  ambiguousFailureMessage,
  createKeyStore,
} from '../utils/idempotency.js';
import Loader from '../components/Loader.jsx';
import Message from '../components/Message.jsx';

/**
 * Fund transfer screen (FR-07, FR-09).
 *
* <p>The customer confirms the details before the transfer is sent, and every submission carries
 * a key that lets the backend recognise a repeated click - or a retry after a timeout - as the
 * same transfer. The key belongs to the request (source, destination, amount), is kept when the
 * outcome is unknown, and is replaced only after the bank has given a definitive answer. See
 * utils/idempotency.js.</p>
 */
export default function TransferPage() {
  const navigate = useNavigate();

  const [accounts, setAccounts] = useState([]);
  const [beneficiaries, setBeneficiaries] = useState([]);
  const [loading, setLoading] = useState(true);

  const [sourceAccountNumber, setSourceAccountNumber] = useState('');
  const [destinationAccountNumber, setDestinationAccountNumber] = useState('');
  const [amount, setAmount] = useState('');
  const [description, setDescription] = useState('');

  const [confirming, setConfirming] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const [fieldErrors, setFieldErrors] = useState({});
  const keyStore = useMemo(() => createKeyStore(), []);

  useEffect(() => {
    let cancelled = false;
    Promise.all([api.accounts(), api.beneficiaries()])
      .then(([accountsResponse, beneficiariesResponse]) => {
        if (cancelled) {
          return;
        }
        setAccounts(accountsResponse.data);
        setBeneficiaries(beneficiariesResponse.data);
        if (accountsResponse.data.length > 0) {
          setSourceAccountNumber(accountsResponse.data[0].accountNumber);
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

  const selectedAccount = useMemo(
    () => accounts.find((account) => account.accountNumber === sourceAccountNumber),
    [accounts, sourceAccountNumber]
  );

  const selectedBeneficiary = useMemo(
    () => beneficiaries.find((item) => item.accountNumber === destinationAccountNumber),
    [beneficiaries, destinationAccountNumber]
  );

  function startConfirmation(event) {
    event.preventDefault();
    setError('');
    setFieldErrors({});

    if (!sourceAccountNumber || !destinationAccountNumber || !amount) {
      setError('Please choose an account, a destination and an amount.');
      return;
    }
    if (Number(amount) <= 0) {
      setError('The amount must be greater than zero.');
      return;
    }
    setConfirming(true);
  }

  async function submitTransfer() {
    setSubmitting(true);
    setError('');
    const request = { sourceAccountNumber, destinationAccountNumber, amount, description };
    // The same request always gets the same key (even after a page reload), so pressing Confirm
    // again after a timeout cannot move the money twice.
    const idempotencyKey = keyStore.keyFor(request, newIdempotencyKey);
    try {
      const { data } = await api.transfer({ ...request, idempotencyKey });
      keyStore.forget(request);
      navigate(`/transactions/${data.reference}`, { state: { justCreated: true } });
    } catch (err) {
      const unknownOutcome = ambiguousFailureMessage(err);
      if (unknownOutcome) {
        // The bank may or may not have received the request: keep the key and stay on the
        // confirmation screen so the customer can safely confirm the identical request again.
        setError(unknownOutcome);
      } else {
        // A definitive answer: the next attempt (usually after a correction) is a new request.
        keyStore.forget(request);
        setError(readError(err));
        setFieldErrors(readFieldErrors(err));
        setConfirming(false);
      }
    } finally {
      setSubmitting(false);
    }
  }
  if (loading) {
    return <Loader label="Preparing the transfer form" />;
  }

  return (
    <div className="page">
      <header className="page__header">
        <div>
          <h1 className="page__title">Transfer funds</h1>
          <p className="page__subtitle">
            Transfers are checked for suspicious activity before they are completed.
          </p>
        </div>
      </header>

      <Message tone="error" onDismiss={() => setError('')}>
        {error}
      </Message>

      {confirming ? (
        <section className="card card--highlight">
          <div className="card__header">
            <h2 className="card__title">Confirm this transfer</h2>
          </div>
          <dl className="detail-grid">
            <div>
              <dt>From</dt>
              <dd className="table__mono">{sourceAccountNumber}</dd>
            </div>
            <div>
              <dt>To</dt>
              <dd>
                {selectedBeneficiary ? selectedBeneficiary.name : 'Account not in your payee list'}
                <br />
                <span className="table__mono">{destinationAccountNumber}</span>
              </dd>
            </div>
            <div>
              <dt>Amount</dt>
              <dd className="account-card__amount">{formatAmount(amount)}</dd>
            </div>
            <div>
              <dt>Reference note</dt>
              <dd>{description || '-'}</dd>
            </div>
          </dl>

          <div className="button-row">
            <button
              type="button"
              className="button button--primary"
              onClick={submitTransfer}
              disabled={submitting}
            >
              {submitting ? 'Sending...' : 'Confirm and transfer'}
            </button>
            <button
              type="button"
              className="button button--ghost"
              onClick={() => setConfirming(false)}
              disabled={submitting}
            >
              Go back and edit
            </button>
          </div>
        </section>
      ) : (
        <section className="card">
          <form onSubmit={startConfirmation} noValidate>
            <div className="form-grid">
              <label className="field">
                <span className="field__label">From account</span>
                <select
                  className="field__input"
                  value={sourceAccountNumber}
                  onChange={(event) => setSourceAccountNumber(event.target.value)}
                  required
                >
                  {accounts.map((account) => (
                    <option key={account.id} value={account.accountNumber}>
                      {account.accountNumber} ({account.accountType}) -{' '}
                      {formatAmount(account.availableBalance, account.currency)} available
                    </option>
                  ))}
                </select>
                {selectedAccount ? (
                  <span className="field__hint">
                    Available balance {formatAmount(selectedAccount.availableBalance, selectedAccount.currency)}
                  </span>
                ) : null}
              </label>

              <label className="field">
                <span className="field__label">Beneficiary</span>
                <select
                  className="field__input"
                  value={destinationAccountNumber}
                  onChange={(event) => setDestinationAccountNumber(event.target.value)}
                >
                  <option value="">Select a saved beneficiary</option>
                  {beneficiaries.map((beneficiary) => (
                    <option key={beneficiary.id} value={beneficiary.accountNumber}>
                      {beneficiary.name} - {beneficiary.accountNumber}
                    </option>
                  ))}
                </select>
                <span className="field__hint">
                  You may also type an account number below that is not in your list.
                </span>
              </label>

              <label className="field">
                <span className="field__label">Destination account number</span>
                <input
                  className={
                    fieldErrors.destinationAccountNumber
                      ? 'field__input field__input--error'
                      : 'field__input'
                  }
                  value={destinationAccountNumber}
                  onChange={(event) => setDestinationAccountNumber(event.target.value)}
                  inputMode="numeric"
                  required
                />
                {fieldErrors.destinationAccountNumber ? (
                  <span className="field__error">{fieldErrors.destinationAccountNumber}</span>
                ) : null}
              </label>

              <label className="field">
                <span className="field__label">Amount</span>
                <input
                  className={fieldErrors.amount ? 'field__input field__input--error' : 'field__input'}
                  type="number"
                  min="1"
                  step="0.01"
                  value={amount}
                  onChange={(event) => setAmount(event.target.value)}
                  required
                />
                {fieldErrors.amount ? <span className="field__error">{fieldErrors.amount}</span> : null}
              </label>

              <label className="field field--wide">
                <span className="field__label">Reference note (optional)</span>
                <input
                  className="field__input"
                  value={description}
                  onChange={(event) => setDescription(event.target.value)}
                  maxLength={200}
                />
              </label>
            </div>

            <button type="submit" className="button button--primary">
              Review transfer
            </button>
          </form>
        </section>
      )}
    </div>
  );
}
