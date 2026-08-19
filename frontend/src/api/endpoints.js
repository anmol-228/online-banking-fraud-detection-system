import client from './client.js';

/**
 * Every backend call the application makes, in one place.
 *
 * Keeping the URLs here means a screen never builds a URL by hand, so a change to the API only
 * has to be made once.
 */
export const api = {
  // Authentication (FR-01, FR-02, FR-03)
  register: (payload) => client.post('/api/auth/register', payload),
  login: (payload) => client.post('/api/auth/login', payload),
  profile: () => client.get('/api/auth/me'),

  // Accounts (FR-05, FR-06)
  accounts: () => client.get('/api/accounts'),
  balance: (accountNumber) => client.get(`/api/accounts/${accountNumber}/balance`),

  // Beneficiaries (FR-08)
  beneficiaries: () => client.get('/api/beneficiaries'),
  addBeneficiary: (payload) => client.post('/api/beneficiaries', payload),
  removeBeneficiary: (id) => client.delete(`/api/beneficiaries/${id}`),

  // Transactions and verification (FR-07, FR-10, FR-14)
  transfer: (payload) => client.post('/api/transactions/transfer', payload),
  transactions: (page = 0, size = 20) =>
    client.get('/api/transactions', { params: { page, size } }),
  transaction: (reference) => client.get(`/api/transactions/${reference}`),
  verificationStatus: (reference) => client.get(`/api/transactions/${reference}/verification`),
  submitVerification: (reference, code) =>
    client.post(`/api/transactions/${reference}/verify`, { code }),

  // Notifications (FR-16)
  notifications: () => client.get('/api/notifications'),
  unreadCount: () => client.get('/api/notifications/unread-count'),
  markNotificationRead: (id) => client.post(`/api/notifications/${id}/read`),
  markAllNotificationsRead: () => client.post('/api/notifications/read-all'),

  // Disputes (FR-17)
  submitDispute: (payload) => client.post('/api/disputes', payload),
  myDisputes: () => client.get('/api/disputes'),
  disputeQueue: () => client.get('/api/disputes/queue'),
  resolveDispute: (reference, payload) =>
    client.post(`/api/disputes/${reference}/resolve`, payload),

  // Fraud monitoring and review (FR-13, FR-15, FR-18)
  alerts: (status) => client.get('/api/alerts', { params: status ? { status } : {} }),
  alert: (reference) => client.get(`/api/alerts/${reference}`),
  fraudCases: () => client.get('/api/fraud-cases'),
  fraudCase: (reference) => client.get(`/api/fraud-cases/${reference}`),
  assignCase: (reference) => client.post(`/api/fraud-cases/${reference}/assign`),
  decideCase: (reference, payload) =>
    client.post(`/api/fraud-cases/${reference}/decision`, payload),

  // Administration, audit and reports (FR-19, FR-20, FR-21)
  users: () => client.get('/api/admin/users'),
  roles: () => client.get('/api/admin/roles'),
  updateUserRoles: (userId, roles) => client.put(`/api/admin/users/${userId}/roles`, { roles }),
  updateUserStatus: (userId, enabled) =>
    client.put(`/api/admin/users/${userId}/status`, { enabled }),
  audit: (params) => client.get('/api/audit', { params }),
  operationalReport: () => client.get('/api/reports/operational'),
  fraudReport: () => client.get('/api/reports/fraud'),
};

export default api;
