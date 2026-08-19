import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from './AuthContext.jsx';
import Loader from '../components/Loader.jsx';

/**
 * Hides a route from anybody who is not signed in, or who does not hold one of the required
 * roles.
 *
 * <p>This only controls what the interface offers. Every endpoint is independently protected on
 * the server, so hiding a link is never the only thing standing between a user and an
 * operation.</p>
 */
export default function ProtectedRoute({ children, roles }) {
  const { isAuthenticated, loading, hasAnyRole } = useAuth();
  const location = useLocation();

  if (loading) {
    return <Loader label="Checking your session" />;
  }

  if (!isAuthenticated) {
    return <Navigate to="/login" state={{ from: location.pathname }} replace />;
  }

  if (roles && roles.length > 0 && !hasAnyRole(...roles)) {
    return <Navigate to="/not-authorised" replace />;
  }

  return children;
}
