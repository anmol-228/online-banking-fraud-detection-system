import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import api from '../services/bankingService.js';
import { readError } from '../api/client.js';
import { formatDateTime, humanise } from '../utils/format.js';
import EmptyState from '../components/EmptyState.jsx';
import Loader from '../components/Loader.jsx';
import Message from '../components/Message.jsx';

/** Customer notifications (FR-16). */
export default function NotificationsPage() {
  const [notifications, setNotifications] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  async function load() {
    try {
      const { data } = await api.notifications();
      setNotifications(data);
    } catch (err) {
      setError(readError(err));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load();
  }, []);

  async function markRead(id) {
    try {
      await api.markNotificationRead(id);
      await load();
    } catch (err) {
      setError(readError(err));
    }
  }

  async function markAllRead() {
    try {
      await api.markAllNotificationsRead();
      await load();
    } catch (err) {
      setError(readError(err));
    }
  }

  const unreadCount = notifications.filter((notification) => !notification.read).length;

  return (
    <div className="page">
      <header className="page__header">
        <div>
          <h1 className="page__title">Notifications</h1>
          <p className="page__subtitle">
            Messages about your transfers, security events and complaints. Verification codes are
            delivered here in this simulation.
          </p>
        </div>
        {unreadCount > 0 ? (
          <button type="button" className="button button--secondary" onClick={markAllRead}>
            Mark all as read ({unreadCount})
          </button>
        ) : null}
      </header>

      <Message tone="error" onDismiss={() => setError('')}>
        {error}
      </Message>

      {loading ? (
        <Loader label="Loading notifications" />
      ) : notifications.length === 0 ? (
        <EmptyState
          title="No notifications yet"
          description="You will be notified here when a transfer completes or needs your attention."
        />
      ) : (
        <ul className="notification-list">
          {notifications.map((notification) => (
            <li
              key={notification.id}
              className={
                notification.read ? 'notification' : 'notification notification--unread'
              }
            >
              <div className="notification__head">
                <span className="notification__type">{humanise(notification.type)}</span>
                <span className="notification__time">{formatDateTime(notification.createdAt)}</span>
              </div>
              <p className="notification__title">{notification.title}</p>
              <p className="notification__message">{notification.message}</p>
              <div className="notification__actions">
                {notification.relatedReference?.startsWith('TXN-') ? (
                  <Link
                    to={`/transactions/${notification.relatedReference}`}
                    className="button button--ghost button--small"
                  >
                    View transaction
                  </Link>
                ) : null}
                {!notification.read ? (
                  <button
                    type="button"
                    className="button button--ghost button--small"
                    onClick={() => markRead(notification.id)}
                  >
                    Mark as read
                  </button>
                ) : null}
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
