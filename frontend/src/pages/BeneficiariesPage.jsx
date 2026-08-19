import { useEffect, useState } from 'react';
import api from '../services/bankingService.js';
import { readError, readFieldErrors } from '../api/client.js';
import { formatDateTime } from '../utils/format.js';
import EmptyState from '../components/EmptyState.jsx';
import Loader from '../components/Loader.jsx';
import Message from '../components/Message.jsx';

const EMPTY_FORM = {
  name: '',
  accountNumber: '',
  bankName: '',
  ifscCode: '',
  nickname: '',
};

/** Beneficiary management (FR-08). */
export default function BeneficiariesPage() {
  const [beneficiaries, setBeneficiaries] = useState([]);
  const [form, setForm] = useState(EMPTY_FORM);
  const [fieldErrors, setFieldErrors] = useState({});
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  async function load() {
    try {
      const { data } = await api.beneficiaries();
      setBeneficiaries(data);
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
      await api.addBeneficiary(form);
      setSuccess(`${form.name} has been added to your beneficiary list.`);
      setForm(EMPTY_FORM);
      await load();
    } catch (err) {
      setError(readError(err));
      setFieldErrors(readFieldErrors(err));
    } finally {
      setSaving(false);
    }
  }

  async function handleRemove(beneficiary) {
    // Removing a payee is not easily undone, so the customer is asked to confirm first.
    const confirmed = window.confirm(
      `Remove ${beneficiary.name} (${beneficiary.accountNumber}) from your beneficiary list?`
    );
    if (!confirmed) {
      return;
    }
    setError('');
    setSuccess('');
    try {
      await api.removeBeneficiary(beneficiary.id);
      setSuccess(`${beneficiary.name} has been removed.`);
      await load();
    } catch (err) {
      setError(readError(err));
    }
  }

  function fieldClass(name) {
    return fieldErrors[name] ? 'field__input field__input--error' : 'field__input';
  }

  return (
    <div className="page">
      <header className="page__header">
        <div>
          <h1 className="page__title">Beneficiaries</h1>
          <p className="page__subtitle">
            People and accounts you can transfer money to. A payee added very recently raises the
            risk score of a transfer.
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
          <h2 className="card__title">Add a beneficiary</h2>
        </div>
        <form onSubmit={handleSubmit} noValidate>
          <div className="form-grid">
            <label className="field">
              <span className="field__label">Beneficiary name</span>
              <input
                className={fieldClass('name')}
                value={form.name}
                onChange={(event) => update('name', event.target.value)}
                required
              />
              {fieldErrors.name ? <span className="field__error">{fieldErrors.name}</span> : null}
            </label>

            <label className="field">
              <span className="field__label">Account number</span>
              <input
                className={fieldClass('accountNumber')}
                value={form.accountNumber}
                onChange={(event) => update('accountNumber', event.target.value)}
                inputMode="numeric"
                required
              />
              {fieldErrors.accountNumber ? (
                <span className="field__error">{fieldErrors.accountNumber}</span>
              ) : (
                <span className="field__hint">9 to 18 digits.</span>
              )}
            </label>

            <label className="field">
              <span className="field__label">Bank name</span>
              <input
                className={fieldClass('bankName')}
                value={form.bankName}
                onChange={(event) => update('bankName', event.target.value)}
                required
              />
              {fieldErrors.bankName ? <span className="field__error">{fieldErrors.bankName}</span> : null}
            </label>

            <label className="field">
              <span className="field__label">IFSC code (optional)</span>
              <input
                className={fieldClass('ifscCode')}
                value={form.ifscCode}
                onChange={(event) => update('ifscCode', event.target.value.toUpperCase())}
              />
              {fieldErrors.ifscCode ? (
                <span className="field__error">{fieldErrors.ifscCode}</span>
              ) : (
                <span className="field__hint">Format: ABCD0123456</span>
              )}
            </label>

            <label className="field">
              <span className="field__label">Nickname (optional)</span>
              <input
                className={fieldClass('nickname')}
                value={form.nickname}
                onChange={(event) => update('nickname', event.target.value)}
              />
            </label>
          </div>

          <button type="submit" className="button button--primary" disabled={saving}>
            {saving ? 'Adding...' : 'Add beneficiary'}
          </button>
        </form>
      </section>

      <section className="card">
        <div className="card__header">
          <h2 className="card__title">Saved beneficiaries</h2>
        </div>
        {loading ? (
          <Loader label="Loading beneficiaries" />
        ) : beneficiaries.length === 0 ? (
          <EmptyState
            title="No beneficiaries saved yet"
            description="Add a payee above before making your first transfer."
          />
        ) : (
          <div className="table-wrapper">
            <table className="table">
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Account number</th>
                  <th>Bank</th>
                  <th>IFSC</th>
                  <th>Nickname</th>
                  <th>Added on</th>
                  <th aria-label="Actions" />
                </tr>
              </thead>
              <tbody>
                {beneficiaries.map((beneficiary) => (
                  <tr key={beneficiary.id}>
                    <td>{beneficiary.name}</td>
                    <td className="table__mono">{beneficiary.accountNumber}</td>
                    <td>{beneficiary.bankName}</td>
                    <td>{beneficiary.ifscCode || '-'}</td>
                    <td>{beneficiary.nickname || '-'}</td>
                    <td>{formatDateTime(beneficiary.createdAt)}</td>
                    <td>
                      <button
                        type="button"
                        className="button button--danger button--small"
                        onClick={() => handleRemove(beneficiary)}
                      >
                        Remove
                      </button>
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
