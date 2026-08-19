import { useState } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext.jsx';
import { readError } from '../api/client.js';
import Message from '../components/Message.jsx';
import Loader from '../components/Loader.jsx';
import { IS_SHOWCASE, REPOSITORY_URL } from '../config/appMode.js';
import { DEMO_PERSONAS } from '../services/showcase/showcaseData.js';

/** Login screen (FR-02). */
export default function LoginPage() {
  const { login, isAuthenticated, loading } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  if (loading) {
    return <Loader label="Starting" />;
  }
  if (isAuthenticated) {
    return <Navigate to="/" replace />;
  }

  async function signIn(name, secret) {
    setError('');
    setSubmitting(true);
    try {
      await login(name, secret);
      navigate(location.state?.from || '/', { replace: true });
    } catch (err) {
      setError(readError(err));
    } finally {
      setSubmitting(false);
    }
  }

  async function handleSubmit(event) {
    event.preventDefault();
    await signIn(username.trim(), password);
  }

  return (
    <div className="auth-page">
      <div className="auth-card">
        <div className="auth-card__header">
          <span className="app__logo" aria-hidden="true">
            OB
          </span>
          <h1>Online Banking &amp; Fraud Detection System</h1>
          <p className="auth-card__subtitle">Sign in to your online banking profile</p>
        </div>

        <Message tone="error" onDismiss={() => setError('')}>
          {error}
        </Message>

        <form onSubmit={handleSubmit} noValidate>
          <label className="field">
            <span className="field__label">Username</span>
            <input
              className="field__input"
              type="text"
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              autoComplete="username"
              required
            />
          </label>

          <label className="field">
            <span className="field__label">Password</span>
            <input
              className="field__input"
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              autoComplete="current-password"
              required
            />
          </label>

          <button type="submit" className="button button--primary button--block" disabled={submitting}>
            {submitting ? 'Signing in...' : 'Sign in'}
          </button>
        </form>

        <p className="auth-card__footer">
          New to online banking? <Link to="/register">Register here</Link>
        </p>

        {IS_SHOWCASE ? (
          <div className="persona-block">
            <p className="persona-block__title">Or explore a role directly</p>
            <div className="persona-grid">
              {DEMO_PERSONAS.map((persona) => (
                <button
                  key={persona.username}
                  type="button"
                  className="persona"
                  onClick={() => signIn(persona.username, persona.password)}
                  disabled={submitting}
                >
                  <span className="persona__label">{persona.label}</span>
                  <span className="persona__name">{persona.name}</span>
                  <span className="persona__description">{persona.description}</span>
                </button>
              ))}
            </div>
          </div>
        ) : null}

        <div className="auth-card__note">
          {IS_SHOWCASE ? (
            <p>
              <strong>Frontend showcase.</strong> This live demo runs entirely in your browser on
              simulated data. It is not connected to a real financial institution and no real money
              is involved. The complete Spring Boot backend and MySQL implementation is in the{' '}
              <a href={REPOSITORY_URL} target="_blank" rel="noreferrer">
                GitHub repository
              </a>
              .
            </p>
          ) : (
            <p>
              <strong>Demonstration system.</strong> This application is not connected to a real
              financial institution and processes no real money. Demo credentials are listed in the
              repository documentation.
            </p>
          )}
        </div>
      </div>
    </div>
  );
}
