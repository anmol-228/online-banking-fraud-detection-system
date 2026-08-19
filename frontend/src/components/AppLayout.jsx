import { useEffect, useState } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext.jsx';
import api from '../services/bankingService.js';
import ShowcaseBanner from './ShowcaseBanner.jsx';

/**
 * The shell around every signed-in screen: header, role-appropriate navigation, and the standing
 * disclosure that this is a simulation rather than a real banking service.
 */
export default function AppLayout() {
  const { user, logout, hasRole, hasAnyRole } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [unread, setUnread] = useState(0);
  const [menuOpen, setMenuOpen] = useState(false);

  const isCustomer = hasRole('CUSTOMER');

  // The unread badge is refreshed on navigation rather than on a timer, which is enough for a
  // demonstration and avoids polling the backend continuously.
  useEffect(() => {
    let cancelled = false;
    if (!isCustomer) {
      setUnread(0);
      return undefined;
    }
    api
      .unreadCount()
      .then(({ data }) => {
        if (!cancelled) {
          setUnread(data.unread);
        }
      })
      .catch(() => {
        // A failure here must never block the page; the badge simply stays at its last value.
      });
    return () => {
      cancelled = true;
    };
  }, [isCustomer, location.pathname]);

  function handleLogout() {
    logout();
    navigate('/login', { replace: true });
  }

  const navClass = ({ isActive }) => (isActive ? 'nav__link nav__link--active' : 'nav__link');

  return (
    <div className="app">
      <ShowcaseBanner />
      <header className="app__header">
        <div className="app__brand">
          <span className="app__logo" aria-hidden="true">
            OB
          </span>
          <div>
            <p className="app__title">Online Banking &amp; Fraud Detection System</p>
            <p className="app__subtitle">Simulated banking platform</p>
          </div>
        </div>

        <button
          type="button"
          className="app__menu-toggle"
          onClick={() => setMenuOpen((open) => !open)}
          aria-expanded={menuOpen}
        >
          Menu
        </button>

        <div className="app__user">
          <div className="app__user-info">
            <span className="app__user-name">{user?.fullName}</span>
            <span className="app__user-role">{(user?.roles || []).join(', ')}</span>
          </div>
          <button type="button" className="button button--ghost" onClick={handleLogout}>
            Sign out
          </button>
        </div>
      </header>

      <div className="app__body">
        <nav className={menuOpen ? 'nav nav--open' : 'nav'} onClick={() => setMenuOpen(false)}>
          {isCustomer ? (
            <>
              <p className="nav__section">Banking</p>
              <NavLink to="/dashboard" className={navClass}>
                Dashboard
              </NavLink>
              <NavLink to="/accounts" className={navClass}>
                My accounts
              </NavLink>
              <NavLink to="/beneficiaries" className={navClass}>
                Beneficiaries
              </NavLink>
              <NavLink to="/transfer" className={navClass}>
                Transfer funds
              </NavLink>
              <NavLink to="/transactions" className={navClass}>
                Transactions
              </NavLink>
              <NavLink to="/notifications" className={navClass}>
                Notifications
                {unread > 0 ? <span className="nav__badge">{unread}</span> : null}
              </NavLink>
              <NavLink to="/disputes" className={navClass}>
                Complaints
              </NavLink>
            </>
          ) : null}

          {hasAnyRole('FRAUD_ANALYST', 'BANK_ADMIN') ? (
            <>
              <p className="nav__section">Fraud monitoring</p>
              <NavLink to="/fraud/alerts" className={navClass}>
                Fraud alerts
              </NavLink>
              <NavLink to="/fraud/cases" className={navClass}>
                Fraud cases
              </NavLink>
            </>
          ) : null}

          {hasAnyRole('OPS_OFFICER', 'BANK_ADMIN') ? (
            <>
              <p className="nav__section">Operations</p>
              <NavLink to="/operations/disputes" className={navClass}>
                Complaint queue
              </NavLink>
            </>
          ) : null}

          {hasAnyRole('BANK_ADMIN', 'FRAUD_ANALYST', 'OPS_OFFICER') ? (
            <>
              <p className="nav__section">Reporting</p>
              <NavLink to="/reports" className={navClass}>
                Reports
              </NavLink>
            </>
          ) : null}

          {hasAnyRole('BANK_ADMIN', 'SYSTEM_ADMIN') ? (
            <>
              <p className="nav__section">Administration</p>
              {hasRole('BANK_ADMIN') ? (
                <NavLink to="/admin/users" className={navClass}>
                  Users &amp; roles
                </NavLink>
              ) : null}
              <NavLink to="/admin/audit" className={navClass}>
                Audit logs
              </NavLink>
            </>
          ) : null}
        </nav>

        <main className="app__main">
          <Outlet />
          <footer className="app__footer">
            This is a simulation. It is not connected to a real financial
            institution. No real money is processed.
          </footer>
        </main>
      </div>
    </div>
  );
}
