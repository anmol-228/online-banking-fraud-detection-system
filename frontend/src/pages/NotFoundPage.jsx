import { Link } from 'react-router-dom';

/** Shown for any address that does not exist. */
export default function NotFoundPage() {
  return (
    <div className="auth-page">
      <div className="auth-card">
        <h1>Page not found</h1>
        <p className="auth-card__subtitle">
          The page you were looking for does not exist in this application.
        </p>
        <Link to="/" className="button button--primary button--block">
          Go to my dashboard
        </Link>
      </div>
    </div>
  );
}
