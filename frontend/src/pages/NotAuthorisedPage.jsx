import { Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext.jsx';

/**
 * Shown when a signed-in user opens a screen their role does not allow.
 *
 * The backend would refuse the request in any case; this page just explains why the screen is
 * not available instead of showing an empty page.
 */
export default function NotAuthorisedPage() {
  const { roles } = useAuth();

  return (
    <div className="page">
      <div className="card">
        <h1 className="page__title">You do not have access to this screen</h1>
        <p className="card__note">
          Your account holds the role(s) <strong>{roles.join(', ') || 'none'}</strong>, which does
          not permit this operation. If you believe this is wrong, please contact the bank
          administrator.
        </p>
        <Link to="/" className="button button--primary">
          Back to my home screen
        </Link>
      </div>
    </div>
  );
}
