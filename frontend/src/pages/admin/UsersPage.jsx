import { useEffect, useState } from 'react';
import api from '../../services/bankingService.js';
import { readError } from '../../api/client.js';
import { formatDateTime, humanise } from '../../utils/format.js';
import EmptyState from '../../components/EmptyState.jsx';
import Loader from '../../components/Loader.jsx';
import Message from '../../components/Message.jsx';

/** User and role administration (FR-19). */
export default function UsersPage() {
  const [users, setUsers] = useState([]);
  const [roles, setRoles] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [editing, setEditing] = useState(null);
  const [selectedRoles, setSelectedRoles] = useState([]);
  const [saving, setSaving] = useState(false);

  async function load() {
    try {
      const [usersResponse, rolesResponse] = await Promise.all([api.users(), api.roles()]);
      setUsers(usersResponse.data);
      setRoles(rolesResponse.data);
    } catch (err) {
      setError(readError(err));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load();
  }, []);

  function startEditing(user) {
    setEditing(user);
    setSelectedRoles(user.roles);
    setError('');
    setSuccess('');
  }

  function toggleRole(role) {
    setSelectedRoles((current) =>
      current.includes(role) ? current.filter((item) => item !== role) : [...current, role]
    );
  }

  async function saveRoles() {
    if (selectedRoles.length === 0) {
      setError('A user must keep at least one role.');
      return;
    }
    setSaving(true);
    setError('');
    try {
      await api.updateUserRoles(editing.id, selectedRoles);
      setSuccess(`Roles updated for ${editing.username}.`);
      setEditing(null);
      await load();
    } catch (err) {
      setError(readError(err));
    } finally {
      setSaving(false);
    }
  }

  async function toggleStatus(user) {
    const action = user.enabled ? 'disable' : 'enable';
    const confirmed = window.confirm(`Are you sure you want to ${action} the login of ${user.username}?`);
    if (!confirmed) {
      return;
    }
    setError('');
    setSuccess('');
    try {
      await api.updateUserStatus(user.id, !user.enabled);
      setSuccess(`Login for ${user.username} has been ${user.enabled ? 'disabled' : 'enabled'}.`);
      await load();
    } catch (err) {
      setError(readError(err));
    }
  }

  if (loading) {
    return <Loader label="Loading users" />;
  }

  return (
    <div className="page">
      <header className="page__header">
        <div>
          <h1 className="page__title">Users and roles</h1>
          <p className="page__subtitle">
            Grant or revoke roles and enable or disable a login. Role changes take effect the next
            time the user signs in.
          </p>
        </div>
      </header>

      <Message tone="error" onDismiss={() => setError('')}>
        {error}
      </Message>
      <Message tone="success" onDismiss={() => setSuccess('')}>
        {success}
      </Message>

      {editing ? (
        <section className="card card--highlight">
          <div className="card__header">
            <h2 className="card__title">Roles for {editing.username}</h2>
            <button type="button" className="card__action" onClick={() => setEditing(null)}>
              Cancel
            </button>
          </div>
          <div className="checkbox-grid">
            {roles.map((role) => (
              <label key={role} className="checkbox">
                <input
                  type="checkbox"
                  checked={selectedRoles.includes(role)}
                  onChange={() => toggleRole(role)}
                />
                <span>{humanise(role)}</span>
              </label>
            ))}
          </div>
          <button
            type="button"
            className="button button--primary"
            onClick={saveRoles}
            disabled={saving}
          >
            {saving ? 'Saving...' : 'Save roles'}
          </button>
        </section>
      ) : null}

      <section className="card">
        <div className="card__header">
          <h2 className="card__title">All users ({users.length})</h2>
        </div>
        {users.length === 0 ? (
          <EmptyState title="No users found" />
        ) : (
          <div className="table-wrapper">
            <table className="table">
              <thead>
                <tr>
                  <th>Username</th>
                  <th>Full name</th>
                  <th>Email</th>
                  <th>Roles</th>
                  <th>Customer number</th>
                  <th>Login status</th>
                  <th>Registered</th>
                  <th aria-label="Actions" />
                </tr>
              </thead>
              <tbody>
                {users.map((user) => (
                  <tr key={user.id}>
                    <td className="table__mono">{user.username}</td>
                    <td>{user.fullName}</td>
                    <td>{user.email}</td>
                    <td>{user.roles.map(humanise).join(', ')}</td>
                    <td className="table__mono">
                      {user.customerNumber || <span className="table__muted">-</span>}
                    </td>
                    <td>
                      <span className={user.enabled ? 'badge badge--success' : 'badge badge--danger'}>
                        {user.enabled ? 'Enabled' : 'Disabled'}
                      </span>
                    </td>
                    <td>{formatDateTime(user.createdAt)}</td>
                    <td>
                      <div className="button-row button-row--tight">
                        <button
                          type="button"
                          className="button button--small button--secondary"
                          onClick={() => startEditing(user)}
                        >
                          Edit roles
                        </button>
                        <button
                          type="button"
                          className={
                            user.enabled
                              ? 'button button--small button--danger'
                              : 'button button--small button--ghost'
                          }
                          onClick={() => toggleStatus(user)}
                        >
                          {user.enabled ? 'Disable' : 'Enable'}
                        </button>
                      </div>
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
