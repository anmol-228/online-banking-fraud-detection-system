import { useState } from 'react';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext.jsx';
import { readError, readFieldErrors } from '../api/client.js';
import Message from '../components/Message.jsx';

const EMPTY_FORM = {
  username: '',
  password: '',
  fullName: '',
  email: '',
  phone: '',
  address: '',
};

/** Customer registration screen (FR-01). */
export default function RegisterPage() {
  const { register, isAuthenticated } = useAuth();
  const navigate = useNavigate();

  const [form, setForm] = useState(EMPTY_FORM);
  const [fieldErrors, setFieldErrors] = useState({});
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  if (isAuthenticated) {
    return <Navigate to="/" replace />;
  }

  function update(field, value) {
    setForm((current) => ({ ...current, [field]: value }));
  }

  async function handleSubmit(event) {
    event.preventDefault();
    setError('');
    setFieldErrors({});
    setSubmitting(true);
    try {
      await register({
        ...form,
        username: form.username.trim(),
        email: form.email.trim(),
      });
      navigate('/dashboard', { replace: true });
    } catch (err) {
      setError(readError(err));
      setFieldErrors(readFieldErrors(err));
    } finally {
      setSubmitting(false);
    }
  }

  function fieldProps(name, type = 'text') {
    return {
      className: fieldErrors[name] ? 'field__input field__input--error' : 'field__input',
      type,
      value: form[name],
      onChange: (event) => update(name, event.target.value),
    };
  }

  return (
    <div className="auth-page">
      <div className="auth-card auth-card--wide">
        <div className="auth-card__header">
          <h1>Create your online banking profile</h1>
          <p className="auth-card__subtitle">
            A savings account is opened automatically so you can try the system straight away.
          </p>
        </div>

        <Message tone="error" onDismiss={() => setError('')}>
          {error}
        </Message>

        <form onSubmit={handleSubmit} noValidate>
          <div className="form-grid">
            <label className="field">
              <span className="field__label">Full name</span>
              <input {...fieldProps('fullName')} autoComplete="name" required />
              {fieldErrors.fullName ? <span className="field__error">{fieldErrors.fullName}</span> : null}
            </label>

            <label className="field">
              <span className="field__label">Email address</span>
              <input {...fieldProps('email', 'email')} autoComplete="email" required />
              {fieldErrors.email ? <span className="field__error">{fieldErrors.email}</span> : null}
            </label>

            <label className="field">
              <span className="field__label">Username</span>
              <input {...fieldProps('username')} autoComplete="username" required />
              {fieldErrors.username ? (
                <span className="field__error">{fieldErrors.username}</span>
              ) : (
                <span className="field__hint">At least 4 characters, letters and digits.</span>
              )}
            </label>

            <label className="field">
              <span className="field__label">Password</span>
              <input {...fieldProps('password', 'password')} autoComplete="new-password" required />
              {fieldErrors.password ? (
                <span className="field__error">{fieldErrors.password}</span>
              ) : (
                <span className="field__hint">At least 8 characters.</span>
              )}
            </label>

            <label className="field">
              <span className="field__label">Phone number</span>
              <input {...fieldProps('phone', 'tel')} autoComplete="tel" />
              {fieldErrors.phone ? <span className="field__error">{fieldErrors.phone}</span> : null}
            </label>

            <label className="field">
              <span className="field__label">Address</span>
              <input {...fieldProps('address')} autoComplete="street-address" />
              {fieldErrors.address ? <span className="field__error">{fieldErrors.address}</span> : null}
            </label>
          </div>

          <button type="submit" className="button button--primary button--block" disabled={submitting}>
            {submitting ? 'Creating your profile...' : 'Register'}
          </button>
        </form>

        <p className="auth-card__footer">
          Already registered? <Link to="/login">Sign in</Link>
        </p>
      </div>
    </div>
  );
}
