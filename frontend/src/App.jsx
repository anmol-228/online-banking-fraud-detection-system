import { Navigate, Route, Routes } from 'react-router-dom';
import AppLayout from './components/AppLayout.jsx';
import ProtectedRoute from './auth/ProtectedRoute.jsx';
import { useAuth } from './auth/AuthContext.jsx';

import LoginPage from './pages/LoginPage.jsx';
import RegisterPage from './pages/RegisterPage.jsx';
import DashboardPage from './pages/DashboardPage.jsx';
import AccountsPage from './pages/AccountsPage.jsx';
import BeneficiariesPage from './pages/BeneficiariesPage.jsx';
import TransferPage from './pages/TransferPage.jsx';
import TransactionsPage from './pages/TransactionsPage.jsx';
import TransactionDetailPage from './pages/TransactionDetailPage.jsx';
import NotificationsPage from './pages/NotificationsPage.jsx';
import DisputesPage from './pages/DisputesPage.jsx';
import AlertsPage from './pages/analyst/AlertsPage.jsx';
import CasesPage from './pages/analyst/CasesPage.jsx';
import CaseDetailPage from './pages/analyst/CaseDetailPage.jsx';
import DisputeQueuePage from './pages/staff/DisputeQueuePage.jsx';
import UsersPage from './pages/admin/UsersPage.jsx';
import AuditPage from './pages/admin/AuditPage.jsx';
import ReportsPage from './pages/admin/ReportsPage.jsx';
import NotAuthorisedPage from './pages/NotAuthorisedPage.jsx';
import NotFoundPage from './pages/NotFoundPage.jsx';

/** Sends each role to the screen that is most useful to them straight after signing in. */
function HomeRedirect() {
  const { hasRole } = useAuth();
  if (hasRole('CUSTOMER')) {
    return <Navigate to="/dashboard" replace />;
  }
  if (hasRole('FRAUD_ANALYST')) {
    return <Navigate to="/fraud/alerts" replace />;
  }
  if (hasRole('BANK_ADMIN')) {
    return <Navigate to="/admin/users" replace />;
  }
  if (hasRole('OPS_OFFICER')) {
    return <Navigate to="/operations/disputes" replace />;
  }
  return <Navigate to="/admin/audit" replace />;
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />

      <Route
        element={
          <ProtectedRoute>
            <AppLayout />
          </ProtectedRoute>
        }
      >
        <Route path="/" element={<HomeRedirect />} />

        {/* Customer screens */}
        <Route
          path="/dashboard"
          element={
            <ProtectedRoute roles={['CUSTOMER']}>
              <DashboardPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/accounts"
          element={
            <ProtectedRoute roles={['CUSTOMER']}>
              <AccountsPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/beneficiaries"
          element={
            <ProtectedRoute roles={['CUSTOMER']}>
              <BeneficiariesPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/transfer"
          element={
            <ProtectedRoute roles={['CUSTOMER']}>
              <TransferPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/transactions"
          element={
            <ProtectedRoute roles={['CUSTOMER']}>
              <TransactionsPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/transactions/:reference"
          element={
            <ProtectedRoute roles={['CUSTOMER']}>
              <TransactionDetailPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/notifications"
          element={
            <ProtectedRoute roles={['CUSTOMER']}>
              <NotificationsPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/disputes"
          element={
            <ProtectedRoute roles={['CUSTOMER']}>
              <DisputesPage />
            </ProtectedRoute>
          }
        />

        {/* Fraud analyst screens */}
        <Route
          path="/fraud/alerts"
          element={
            <ProtectedRoute roles={['FRAUD_ANALYST', 'BANK_ADMIN']}>
              <AlertsPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/fraud/cases"
          element={
            <ProtectedRoute roles={['FRAUD_ANALYST', 'BANK_ADMIN']}>
              <CasesPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/fraud/cases/:reference"
          element={
            <ProtectedRoute roles={['FRAUD_ANALYST', 'BANK_ADMIN']}>
              <CaseDetailPage />
            </ProtectedRoute>
          }
        />

        {/* Operations screens */}
        <Route
          path="/operations/disputes"
          element={
            <ProtectedRoute roles={['OPS_OFFICER', 'BANK_ADMIN']}>
              <DisputeQueuePage />
            </ProtectedRoute>
          }
        />

        {/* Reporting and administration */}
        <Route
          path="/reports"
          element={
            <ProtectedRoute roles={['BANK_ADMIN', 'FRAUD_ANALYST', 'OPS_OFFICER']}>
              <ReportsPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/admin/users"
          element={
            <ProtectedRoute roles={['BANK_ADMIN']}>
              <UsersPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/admin/audit"
          element={
            <ProtectedRoute roles={['BANK_ADMIN', 'SYSTEM_ADMIN']}>
              <AuditPage />
            </ProtectedRoute>
          }
        />

        <Route path="/not-authorised" element={<NotAuthorisedPage />} />
      </Route>

      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  );
}
