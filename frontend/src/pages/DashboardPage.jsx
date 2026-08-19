import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import api from '../services/bankingService.js';
import { readError } from '../api/client.js';
import { useAuth } from '../auth/AuthContext.jsx';
import { formatAmount, formatDateTime } from '../utils/format.js';
import Badge from '../components/Badge.jsx';
import EmptyState from '../components/EmptyState.jsx';
import Loader from '../components/Loader.jsx';
import Message from '../components/Message.jsx';

/** Customer dashboard: balances, recent activity and anything waiting for attention. */
export default function DashboardPage() {
  const { user } = useAuth();
  const [accounts, setAccounts] = useState([]);
  const [transactions, setTransactions] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    let cancelled = false;

    async function load() {
      try {
        const [accountsResponse, transactionsResponse] = await Promise.all([
          api.accounts(),
          api.transactions(0, 5),
        ]);
        if (!cancelled) {
          setAccounts(accountsResponse.data);
          setTransactions(transactionsResponse.data.content);
        }
      } catch (err) {
        if (!cancelled) {
          setError(readError(err));
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    }

    load();
    return () => {
      cancelled = true;
    };
  }, []);

  if (loading) {
    return <Loader label="Loading your dashboard" />;
  }

  const totalBalance = accounts.reduce((sum, account) => sum + Number(account.balance), 0);
  const awaitingVerification = transactions.filter(
    (transaction) => transaction.status === 'PENDING_VERIFICATION'
  );
  const underReview = transactions.filter((transaction) => transaction.status === 'PENDING');

  return (
    <div className="page">
      <header className="page__header">
        <div>
          <h1 className="page__title">Welcome back, {user?.fullName}</h1>
          <p className="page__subtitle">Customer number {user?.customerNumber}</p>
        </div>
        <Link to="/transfer" className="button button--primary">
          Transfer funds
        </Link>
      </header>

      <Message tone="error" onDismiss={() => setError('')}>
        {error}
      </Message>

      {awaitingVerification.length > 0 ? (
        <Message tone="warning">
          You have {awaitingVerification.length} transfer(s) waiting for additional verification.{' '}
          <Link to={`/transactions/${awaitingVerification[0].reference}`}>Complete verification</Link>
        </Message>
      ) : null}

      {underReview.length > 0 ? (
        <Message tone="info">
          {underReview.length} transfer(s) are being reviewed by our security team. You will be
          notified once the review is complete.
        </Message>
      ) : null}

      <section className="stat-grid">
        <div className="stat-card">
          <p className="stat-card__label">Total balance</p>
          <p className="stat-card__value">{formatAmount(totalBalance)}</p>
        </div>
        <div className="stat-card">
          <p className="stat-card__label">Accounts</p>
          <p className="stat-card__value">{accounts.length}</p>
        </div>
        <div className="stat-card">
          <p className="stat-card__label">Recent transfers</p>
          <p className="stat-card__value">{transactions.length}</p>
        </div>
      </section>

      <section className="card">
        <div className="card__header">
          <h2 className="card__title">Your accounts</h2>
          <Link to="/accounts" className="card__action">
            View all
          </Link>
        </div>
        <div className="table-wrapper">
          <table className="table">
            <thead>
              <tr>
                <th>Account number</th>
                <th>Type</th>
                <th>Status</th>
                <th className="table__number">Available</th>
                <th className="table__number">Balance</th>
              </tr>
            </thead>
            <tbody>
              {accounts.map((account) => (
                <tr key={account.id}>
                  <td className="table__mono">{account.accountNumber}</td>
                  <td>{account.accountType}</td>
                  <td>
                    <Badge value={account.status} />
                  </td>
                  <td className="table__number">{formatAmount(account.availableBalance, account.currency)}</td>
                  <td className="table__number">{formatAmount(account.balance, account.currency)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="card">
        <div className="card__header">
          <h2 className="card__title">Recent transactions</h2>
          <Link to="/transactions" className="card__action">
            View all
          </Link>
        </div>
        {transactions.length === 0 ? (
          <EmptyState
            title="No transactions yet"
            description="Once you make your first transfer it will appear here."
            action={
              <Link to="/transfer" className="button button--primary">
                Make a transfer
              </Link>
            }
          />
        ) : (
          <div className="table-wrapper">
            <table className="table">
              <thead>
                <tr>
                  <th>Reference</th>
                  <th>To</th>
                  <th className="table__number">Amount</th>
                  <th>Status</th>
                  <th>Risk</th>
                  <th>Date</th>
                </tr>
              </thead>
              <tbody>
                {transactions.map((transaction) => (
                  <tr key={transaction.reference}>
                    <td className="table__mono">
                      <Link to={`/transactions/${transaction.reference}`}>{transaction.reference}</Link>
                    </td>
                    <td>{transaction.destinationName}</td>
                    <td className="table__number">
                      {formatAmount(transaction.amount, transaction.currency)}
                    </td>
                    <td>
                      <Badge value={transaction.status} />
                    </td>
                    <td>
                      <Badge value={transaction.riskLevel} />
                    </td>
                    <td>{formatDateTime(transaction.createdAt)}</td>
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
