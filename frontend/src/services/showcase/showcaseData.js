/**
 * Fictional seed data for the frontend showcase.
 *
 * Every name, account number, email address and balance here is invented. Email addresses use the
 * reserved `.example` top-level domain, which can never resolve to a real address. Account numbers
 * do not follow the format of any real banking network.
 *
 * This mirrors the dataset created by the backend `DataSeeder`, so the showcase behaves the same
 * way the full-stack application does.
 */

const DAY = 24 * 60 * 60 * 1000;

/** Returns a fresh copy so the demo can be reset without stale references. */
export function buildInitialState() {
  const now = Date.now();
  const longAgo = new Date(now - 30 * DAY).toISOString();

  return {
    version: 1,
    users: [
      {
        id: 1,
        username: 'ravi.kumar',
        password: 'Customer@123',
        fullName: 'Ravi Kumar',
        email: 'ravi.kumar@demomail.example',
        roles: ['CUSTOMER'],
        enabled: true,
        customerId: 1,
        customerNumber: 'CUST10000001',
        phone: '9876500011',
        address: '12 MG Road, Pune, Maharashtra',
        createdAt: new Date(now - 120 * DAY).toISOString(),
      },
      {
        id: 2,
        username: 'meera.nair',
        password: 'Customer@123',
        fullName: 'Meera Nair',
        email: 'meera.nair@demomail.example',
        roles: ['CUSTOMER'],
        enabled: true,
        customerId: 2,
        customerNumber: 'CUST10000002',
        phone: '9876500022',
        address: '44 Residency Road, Bengaluru, Karnataka',
        createdAt: new Date(now - 118 * DAY).toISOString(),
      },
      {
        id: 3,
        username: 'admin.bank',
        password: 'Admin@123',
        fullName: 'Priya Deshmukh',
        email: 'priya.deshmukh@demobank.example',
        roles: ['BANK_ADMIN'],
        enabled: true,
        customerId: null,
        createdAt: new Date(now - 200 * DAY).toISOString(),
      },
      {
        id: 4,
        username: 'analyst.fraud',
        password: 'Analyst@123',
        fullName: 'Arjun Mehta',
        email: 'arjun.mehta@demobank.example',
        roles: ['FRAUD_ANALYST'],
        enabled: true,
        customerId: null,
        createdAt: new Date(now - 190 * DAY).toISOString(),
      },
      {
        id: 5,
        username: 'ops.officer',
        password: 'Officer@123',
        fullName: 'Kavya Iyer',
        email: 'kavya.iyer@demobank.example',
        roles: ['OPS_OFFICER'],
        enabled: true,
        customerId: null,
        createdAt: new Date(now - 185 * DAY).toISOString(),
      },
      {
        id: 6,
        username: 'sys.admin',
        password: 'SysAdmin@123',
        fullName: 'Rohit Verma',
        email: 'rohit.verma@demobank.example',
        roles: ['SYSTEM_ADMIN'],
        enabled: true,
        customerId: null,
        createdAt: new Date(now - 210 * DAY).toISOString(),
      },
    ],

    accounts: [
      {
        id: 1,
        accountNumber: '900000000001',
        customerId: 1,
        accountType: 'SAVINGS',
        balance: 150000.0,
        currency: 'INR',
        status: 'ACTIVE',
        openedAt: new Date(now - 120 * DAY).toISOString(),
      },
      {
        id: 2,
        accountNumber: '900000000002',
        customerId: 1,
        accountType: 'CURRENT',
        balance: 60000.0,
        currency: 'INR',
        status: 'ACTIVE',
        openedAt: new Date(now - 120 * DAY).toISOString(),
      },
      {
        id: 3,
        accountNumber: '900000000003',
        customerId: 2,
        accountType: 'SAVINGS',
        balance: 120000.0,
        currency: 'INR',
        status: 'ACTIVE',
        openedAt: new Date(now - 118 * DAY).toISOString(),
      },
    ],

    // Backdated so they do not trigger the new-beneficiary rule. A payee added during the demo
    // will trigger it, which is what makes that rule visible.
    beneficiaries: [
      {
        id: 1,
        customerId: 1,
        name: 'Meera Nair',
        accountNumber: '900000000003',
        bankName: 'Demo Bank',
        ifscCode: 'DEMO0000123',
        nickname: 'Sister',
        active: true,
        createdAt: longAgo,
      },
      {
        id: 2,
        customerId: 1,
        name: 'Anita Sharma',
        accountNumber: '500000000009',
        bankName: 'Example Bank',
        ifscCode: 'EXMP0000456',
        nickname: 'Landlord',
        active: true,
        createdAt: longAgo,
      },
      {
        id: 3,
        customerId: 2,
        name: 'Ravi Kumar',
        accountNumber: '900000000001',
        bankName: 'Demo Bank',
        ifscCode: 'DEMO0000123',
        nickname: 'Brother',
        active: true,
        createdAt: longAgo,
      },
    ],

    transactions: [],
    verifications: [],
    alerts: [],
    cases: [],
    disputes: [],
    notifications: [],
    auditLog: [],

    counters: {
      user: 100,
      account: 100,
      customer: 100,
      beneficiary: 100,
      transaction: 100,
      verification: 100,
      alert: 100,
      fraudCase: 100,
      dispute: 100,
      notification: 100,
      audit: 100,
    },
  };
}

/** Demo identities offered by the one-click role switcher on the login screen. */
export const DEMO_PERSONAS = [
  {
    username: 'ravi.kumar',
    password: 'Customer@123',
    label: 'Explore as Customer',
    name: 'Ravi Kumar',
    description: 'Accounts, beneficiaries, transfers and the fraud verification flow',
  },
  {
    username: 'analyst.fraud',
    password: 'Analyst@123',
    label: 'Explore as Fraud Analyst',
    name: 'Arjun Mehta',
    description: 'Fraud alert dashboard and case review decisions',
  },
  {
    username: 'admin.bank',
    password: 'Admin@123',
    label: 'Explore as Administrator',
    name: 'Priya Deshmukh',
    description: 'User and role management, audit trail and reports',
  },
  {
    username: 'ops.officer',
    password: 'Officer@123',
    label: 'Explore as Operations Officer',
    name: 'Kavya Iyer',
    description: 'Customer complaint queue and outcome recording',
  },
];
