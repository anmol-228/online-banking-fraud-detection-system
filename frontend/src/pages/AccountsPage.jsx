import { useEffect, useState } from 'react';
import api from '../services/bankingService.js';
import { readError } from '../api/client.js';
import { formatAmount, formatDateTime } from '../utils/format.js';
import Badge from '../components/Badge.jsx';
import Loader from '../components/Loader.jsx';
import Message from '../components/Message.jsx';

/** Account details and balance enquiry (FR-05, FR-06). */
export default function AccountsPage() {
  const [accounts, setAccounts] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [balance, setBalance] = useState(null);
  const [checking, setChecking] = useState('');

  useEffect(() => {
    let cancelled = false;
    api
      .accounts()
      .then(({ data }) => {
        if (!cancelled) {
          setAccounts(data);
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

  async function checkBalance(accountNumber) {
    setChecking(accountNumber);
    setError('');
    try {
      const { data } = await api.balance(accountNumber);
      setBalance(data);
    } catch (err) {
      setError(readError(err));
    } finally {
      setChecking('');
    }
  }

  if (loading) {
    return <Loader label="Loading your accounts" />;
  }

  return (
    <div className="page">
      <header className="page__header">
        <div>
          <h1 className="page__title">My accounts</h1>
          <p className="page__subtitle">
            Account details and balance enquiry. Available balance excludes transfers that are
            still pending.
          </p>
        </div>
      </header>

      <Message tone="error" onDismiss={() => setError('')}>
        {error}
      </Message>

      <div className="account-grid">
        {accounts.map((account) => (
          <article key={account.id} className="account-card">
            <div className="account-card__top">
              <div>
                <p className="account-card__type">{account.accountType} ACCOUNT</p>
                <p className="account-card__number">{account.accountNumber}</p>
              </div>
              <Badge value={account.status} />
            </div>

            <dl className="account-card__details">
              <div>
                <dt>Balance</dt>
                <dd className="account-card__amount">
                  {formatAmount(account.balance, account.currency)}
                </dd>
              </div>
              <div>
                <dt>Available balance</dt>
                <dd>{formatAmount(account.availableBalance, account.currency)}</dd>
              </div>
              <div>
                <dt>Currency</dt>
                <dd>{account.currency}</dd>
              </div>
              <div>
                <dt>Opened on</dt>
                <dd>{formatDateTime(account.openedAt)}</dd>
              </div>
            </dl>

            <button
              type="button"
              className="button button--secondary"
              onClick={() => checkBalance(account.accountNumber)}
              disabled={checking === account.accountNumber}
            >
              {checking === account.accountNumber ? 'Checking...' : 'Check balance now'}
            </button>
          </article>
        ))}
      </div>

      {balance ? (
        <section className="card">
          <div className="card__header">
            <h2 className="card__title">Balance enquiry result</h2>
            <button type="button" className="card__action" onClick={() => setBalance(null)}>
              Clear
            </button>
          </div>
          <dl className="detail-grid">
            <div>
              <dt>Account number</dt>
              <dd className="table__mono">{balance.accountNumber}</dd>
            </div>
            <div>
              <dt>Balance</dt>
              <dd>{formatAmount(balance.balance, balance.currency)}</dd>
            </div>
            <div>
              <dt>Reserved by pending transfers</dt>
              <dd>{formatAmount(balance.reservedAmount, balance.currency)}</dd>
            </div>
            <div>
              <dt>Available balance</dt>
              <dd>{formatAmount(balance.availableBalance, balance.currency)}</dd>
            </div>
            <div>
              <dt>As of</dt>
              <dd>{formatDateTime(balance.asOf)}</dd>
            </div>
          </dl>
        </section>
      ) : null}
    </div>
  );
}
