import { evaluateRisk } from './showcaseFraudRules.js';
import {
  getState,
  mutate,
  nextId,
  reference,
  resetShowcase,
  sixDigitCode,
} from './showcaseStore.js';

/**
 * A complete in-browser implementation of the banking API, used by the public showcase build.
 *
 * It reproduces the behaviour of the Spring Boot backend closely enough that the interface can be
 * explored properly: the same validation rules, the same fraud scoring, the same transaction
 * states, the same notification and audit behaviour.
 *
 * What it is not: it is not a backend. Nothing leaves the browser, no real account exists, and no
 * money of any kind is involved.
 *
 * Every method returns a promise resolving to `{ data }` and rejects with an axios-shaped error,
 * so the screens cannot tell which implementation they are talking to.
 */

const SESSION_KEY = 'obfds.showcase.session';
const LATENCY_MS = 140;
const DUPLICATE_WINDOW_MS = 60000;
const VERIFICATION_VALIDITY_MS = 10 * 60000;
const MAX_ATTEMPTS = 3;

// ---------------------------------------------------------------- infrastructure --

function delay(value) {
  return new Promise((resolve) => setTimeout(() => resolve({ data: value }), LATENCY_MS));
}

/** Builds an axios-shaped error so screens can read it exactly as they read a real API failure. */
function apiError(status, code, message, fieldErrors = {}) {
  const error = new Error(message);
  error.response = {
    status,
    data: {
      timestamp: new Date().toISOString(),
      status,
      code,
      message,
      path: '(showcase mode)',
      fieldErrors,
    },
  };
  return error;
}

/** Used inside a method body to return a failure. */
function fail(status, code, message, fieldErrors = {}) {
  return Promise.reject(apiError(status, code, message, fieldErrors));
}

function setSession(username) {
  try {
    window.localStorage.setItem(SESSION_KEY, username);
  } catch {
    // Storage unavailable: the session simply will not survive a refresh.
  }
}

function currentUsername() {
  try {
    return window.localStorage.getItem(SESSION_KEY);
  } catch {
    return null;
  }
}

function currentUser() {
  const username = currentUsername();
  if (!username) return null;
  return getState().users.find((user) => user.username === username) || null;
}

/**
 * Guards throw a plain Error rather than a rejected promise. The exported service wraps every
 * method so that a throw becomes a rejection, which keeps callers on the single `.catch()` path
 * they use against the real API.
 */
function requireUser() {
  const user = currentUser();
  if (!user) {
    throw apiError(401, 'UNAUTHENTICATED', 'Authentication is required to access this resource.');
  }
  return user;
}

function requireCustomer() {
  const user = requireUser();
  if (!user.customerId) {
    throw apiError(403, 'NOT_A_CUSTOMER', 'This operation is only available to bank customers.');
  }
  return user;
}

function audit(action, entityType, entityReference, details, outcome = 'SUCCESS', asUser = null) {
  const user = asUser || currentUser();
  mutate((state) => {
    state.auditLog.unshift({
      id: nextId('audit'),
      occurredAt: new Date().toISOString(),
      username: user ? user.username : 'anonymous',
      roles: user ? user.roles.map((role) => `ROLE_${role}`).join(',') : null,
      action,
      entityType,
      entityReference,
      details,
      outcome,
    });
  });
}

function notify(customerId, type, title, message, relatedReference) {
  mutate((state) => {
    state.notifications.unshift({
      id: nextId('notification'),
      customerId,
      type,
      title,
      message,
      relatedReference,
      read: false,
      createdAt: new Date().toISOString(),
    });
  });
}

// -------------------------------------------------------------------- projections --

function reservedAmount(accountId) {
  return getState()
    .transactions.filter(
      (txn) =>
        txn.sourceAccountId === accountId &&
        (txn.status === 'PENDING' || txn.status === 'PENDING_VERIFICATION')
    )
    .reduce((total, txn) => total + Number(txn.amount), 0);
}

function accountView(account) {
  const reserved = reservedAmount(account.id);
  return {
    id: account.id,
    accountNumber: account.accountNumber,
    accountType: account.accountType,
    balance: account.balance,
    availableBalance: account.balance - reserved,
    currency: account.currency,
    status: account.status,
    openedAt: account.openedAt,
  };
}

function hasOpenVerification(txn) {
  return (
    txn.status === 'PENDING_VERIFICATION' &&
    getState().verifications.some(
      (v) => v.transactionId === txn.id && v.status === 'PENDING'
    )
  );
}

function transactionView(txn) {
  const state = getState();
  const source = state.accounts.find((a) => a.id === txn.sourceAccountId);
  return {
    id: txn.id,
    reference: txn.reference,
    sourceAccountNumber: source ? source.accountNumber : '',
    destinationAccountNumber: txn.destinationAccountNumber,
    destinationName: txn.destinationName,
    amount: txn.amount,
    currency: txn.currency,
    description: txn.description,
    status: txn.status,
    statusReason: txn.statusReason,
    riskLevel: txn.riskLevel,
    riskScore: txn.riskScore,
    riskReason: txn.riskReason,
    verificationRequired: hasOpenVerification(txn),
    initiatedBy: txn.initiatedBy,
    createdAt: txn.createdAt,
    completedAt: txn.completedAt,
  };
}

function customerOf(customerId) {
  return getState().users.find((user) => user.customerId === customerId) || null;
}

function alertView(alert) {
  const state = getState();
  const txn = state.transactions.find((t) => t.id === alert.transactionId);
  const source = state.accounts.find((a) => a.id === txn.sourceAccountId);
  const owner = customerOf(source.customerId);
  const fraudCase = state.cases.find((c) => c.alertId === alert.id);
  return {
    id: alert.id,
    reference: alert.reference,
    transactionReference: txn.reference,
    customerName: owner ? owner.fullName : 'Unknown',
    customerNumber: owner ? owner.customerNumber : '',
    sourceAccountNumber: source.accountNumber,
    destinationAccountNumber: txn.destinationAccountNumber,
    amount: txn.amount,
    riskLevel: alert.riskLevel,
    riskScore: alert.riskScore,
    reason: alert.reason,
    status: alert.status,
    transactionStatus: txn.status,
    caseReference: fraudCase ? fraudCase.reference : null,
    createdAt: alert.createdAt,
  };
}

function caseView(fraudCase) {
  const state = getState();
  const alert = state.alerts.find((a) => a.id === fraudCase.alertId);
  const txn = state.transactions.find((t) => t.id === alert.transactionId);
  const source = state.accounts.find((a) => a.id === txn.sourceAccountId);
  const owner = customerOf(source.customerId);
  return {
    id: fraudCase.id,
    reference: fraudCase.reference,
    alertReference: alert.reference,
    transactionReference: txn.reference,
    customerName: owner ? owner.fullName : 'Unknown',
    amount: txn.amount,
    riskLevel: alert.riskLevel,
    riskScore: alert.riskScore,
    riskReason: txn.riskReason,
    transactionStatus: txn.status,
    status: fraudCase.status,
    assignedTo: fraudCase.assignedTo,
    remarks: fraudCase.remarks,
    decidedBy: fraudCase.decidedBy,
    createdAt: fraudCase.createdAt,
    updatedAt: fraudCase.updatedAt,
    closedAt: fraudCase.closedAt,
  };
}

function disputeView(dispute) {
  const state = getState();
  const txn = state.transactions.find((t) => t.id === dispute.transactionId);
  const owner = customerOf(dispute.customerId);
  return {
    id: dispute.id,
    reference: dispute.reference,
    transactionReference: txn ? txn.reference : '',
    transactionAmount: txn ? txn.amount : 0,
    customerName: owner ? owner.fullName : 'Unknown',
    subject: dispute.subject,
    description: dispute.description,
    status: dispute.status,
    resolution: dispute.resolution,
    handledBy: dispute.handledBy,
    createdAt: dispute.createdAt,
    updatedAt: dispute.updatedAt,
  };
}

function userView(user) {
  return {
    id: user.id,
    username: user.username,
    fullName: user.fullName,
    email: user.email,
    roles: [...user.roles].sort(),
    enabled: user.enabled,
    customerNumber: user.customerNumber || null,
    createdAt: user.createdAt,
  };
}

// ------------------------------------------------------------ transaction settling --

function settleApproved(txn, reason) {
  mutate((state) => {
    const source = state.accounts.find((a) => a.id === txn.sourceAccountId);

    // Defensive re-check: a held transfer may have been overtaken while it waited.
    if (source.balance < Number(txn.amount)) {
      txn.status = 'FAILED';
      txn.statusReason = 'Balance was no longer sufficient when the transfer was released.';
      txn.completedAt = new Date().toISOString();
      return;
    }

    source.balance -= Number(txn.amount);

    const destination = state.accounts.find(
      (a) => a.accountNumber === txn.destinationAccountNumber && a.id !== source.id
    );
    if (destination) {
      destination.balance += Number(txn.amount);
    }

    txn.status = 'APPROVED';
    txn.statusReason = reason;
    txn.completedAt = new Date().toISOString();
  });

  if (txn.status === 'APPROVED') {
    audit('TRANSACTION_APPROVED', 'Transaction', txn.reference,
      `Transfer of ${txn.amount} approved: ${reason}`);
    const source = getState().accounts.find((a) => a.id === txn.sourceAccountId);
    notify(source.customerId, 'TRANSACTION', 'Transfer completed',
      `Your transfer of ${txn.currency} ${txn.amount} to ${txn.destinationName} has been completed. Reference ${txn.reference}.`,
      txn.reference);
  } else {
    const source = getState().accounts.find((a) => a.id === txn.sourceAccountId);
    audit('TRANSFER_REJECTED', 'Transaction', txn.reference, txn.statusReason, 'FAILURE');
    notify(source.customerId, 'TRANSACTION', 'Transfer failed',
      `Transfer ${txn.reference} could not be completed because the account balance was no longer sufficient.`,
      txn.reference);
  }
}

function settleBlocked(txn, reason) {
  mutate(() => {
    txn.status = 'BLOCKED';
    txn.statusReason = reason;
    txn.completedAt = new Date().toISOString();
  });
  audit('TRANSACTION_BLOCKED', 'Transaction', txn.reference, reason);
  const source = getState().accounts.find((a) => a.id === txn.sourceAccountId);
  notify(source.customerId, 'SECURITY', 'Transfer blocked',
    `Your transfer of ${txn.currency} ${txn.amount} to ${txn.destinationName} (reference ${txn.reference}) has been blocked. ${reason}`,
    txn.reference);
}

function raiseAlert(txn, evaluation, status) {
  let created;
  mutate((state) => {
    created = {
      id: nextId('alert'),
      reference: reference('ALT'),
      transactionId: txn.id,
      riskLevel: evaluation.level,
      riskScore: evaluation.score,
      reason: evaluation.reason,
      status,
      createdAt: new Date().toISOString(),
    };
    state.alerts.unshift(created);
  });
  audit('FRAUD_ALERT_RAISED', 'FraudAlert', created.reference,
    `Alert raised for transaction ${txn.reference} at risk ${evaluation.level}`);
  return created;
}

function openCase(alert, txn, remarks) {
  let created;
  mutate((state) => {
    created = {
      id: nextId('fraudCase'),
      reference: reference('CSE'),
      alertId: alert.id,
      status: 'OPEN',
      assignedTo: null,
      remarks: remarks || null,
      decidedBy: null,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      closedAt: null,
    };
    state.cases.unshift(created);
  });
  audit('FRAUD_CASE_OPENED', 'FraudCase', created.reference,
    `Case opened for transaction ${txn.reference}`);
  return created;
}

// ------------------------------------------------------------------- the service --

const service = {
  // ---- Authentication ----------------------------------------------------

  register(payload) {
    const state = getState();
    const username = (payload.username || '').trim();
    const email = (payload.email || '').trim().toLowerCase();

    if (state.users.some((u) => u.username === username)) {
      return fail(409, 'USERNAME_TAKEN', 'That username is already registered.');
    }
    if (state.users.some((u) => u.email === email)) {
      return fail(409, 'EMAIL_TAKEN', 'That email address is already registered.');
    }

    let created;
    mutate((s) => {
      const customerId = nextId('customer');
      created = {
        id: nextId('user'),
        username,
        password: payload.password,
        fullName: payload.fullName.trim(),
        email,
        roles: ['CUSTOMER'],
        enabled: true,
        customerId,
        customerNumber: `CUST${String(Math.floor(10000000 + Math.random() * 89999999))}`,
        phone: payload.phone || null,
        address: payload.address || null,
        createdAt: new Date().toISOString(),
      };
      s.users.push(created);
      s.accounts.push({
        id: nextId('account'),
        accountNumber: `9${String(Math.floor(10000000000 + Math.random() * 89999999999))}`,
        customerId,
        accountType: 'SAVINGS',
        balance: 25000.0,
        currency: 'INR',
        status: 'ACTIVE',
        openedAt: new Date().toISOString(),
      });
    });

    setSession(created.username);
    audit('REGISTER', 'Customer', created.customerNumber, 'New customer registered', 'SUCCESS', created);

    return delay({
      token: `showcase.${created.username}`,
      expiresInSeconds: 7200,
      username: created.username,
      fullName: created.fullName,
      roles: created.roles,
      customerNumber: created.customerNumber,
    });
  },

  login(payload) {
    const username = (payload.username || '').trim();
    const user = getState().users.find((u) => u.username === username);

    if (!user || user.password !== payload.password) {
      audit('LOGIN_FAILURE', 'ApplicationUser', username, 'Login rejected', 'FAILURE',
        user || { username, roles: [] });
      return fail(401, 'INVALID_CREDENTIALS', 'Invalid username or password.');
    }
    if (!user.enabled) {
      return fail(403, 'ACCOUNT_DISABLED',
        'This login has been disabled. Please contact the bank administrator.');
    }

    setSession(user.username);
    audit('LOGIN_SUCCESS', 'ApplicationUser', user.username, 'Login successful', 'SUCCESS', user);

    return delay({
      token: `showcase.${user.username}`,
      expiresInSeconds: 7200,
      username: user.username,
      fullName: user.fullName,
      roles: user.roles,
      customerNumber: user.customerNumber || null,
    });
  },

  profile() {
    const user = currentUser();
    if (!user) {
      return fail(401, 'UNAUTHENTICATED', 'Authentication is required to access this resource.');
    }
    return delay({
      username: user.username,
      fullName: user.fullName,
      email: user.email,
      roles: user.roles,
      customerNumber: user.customerNumber || null,
      phone: user.phone || null,
      address: user.address || null,
    });
  },

  // ---- Accounts ----------------------------------------------------------

  accounts() {
    const user = requireCustomer();
    return delay(
      getState()
        .accounts.filter((a) => a.customerId === user.customerId)
        .map(accountView)
    );
  },

  balance(accountNumber) {
    const user = requireCustomer();
    const account = getState().accounts.find(
      (a) => a.accountNumber === accountNumber && a.customerId === user.customerId
    );
    if (!account) {
      return fail(404, 'NOT_FOUND', `Account ${accountNumber} was not found.`);
    }
    const reserved = reservedAmount(account.id);
    return delay({
      accountNumber: account.accountNumber,
      balance: account.balance,
      availableBalance: account.balance - reserved,
      reservedAmount: reserved,
      currency: account.currency,
      asOf: new Date().toISOString(),
    });
  },

  // ---- Beneficiaries -----------------------------------------------------

  beneficiaries() {
    const user = requireCustomer();
    return delay(
      getState()
        .beneficiaries.filter((b) => b.customerId === user.customerId && b.active)
        .sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt))
    );
  },

  addBeneficiary(payload) {
    const user = requireCustomer();
    const state = getState();
    const accountNumber = (payload.accountNumber || '').trim();

    if (state.accounts.some((a) => a.accountNumber === accountNumber && a.customerId === user.customerId)) {
      return fail(422, 'OWN_ACCOUNT_AS_BENEFICIARY',
        'You cannot add one of your own accounts as a beneficiary.');
    }
    if (state.beneficiaries.some((b) => b.customerId === user.customerId && b.accountNumber === accountNumber)) {
      return fail(409, 'BENEFICIARY_EXISTS',
        'This account number is already saved in your beneficiary list.');
    }

    let created;
    mutate((s) => {
      created = {
        id: nextId('beneficiary'),
        customerId: user.customerId,
        name: payload.name.trim(),
        accountNumber,
        bankName: payload.bankName.trim(),
        ifscCode: payload.ifscCode ? payload.ifscCode.trim() : null,
        nickname: payload.nickname ? payload.nickname.trim() : null,
        active: true,
        createdAt: new Date().toISOString(),
      };
      s.beneficiaries.push(created);
    });

    audit('BENEFICIARY_ADDED', 'Beneficiary', created.accountNumber,
      `Beneficiary ${created.name} added by customer ${user.customerNumber}`);
    return delay(created);
  },

  removeBeneficiary(id) {
    const user = requireCustomer();
    const beneficiary = getState().beneficiaries.find(
      (b) => b.id === id && b.customerId === user.customerId
    );
    if (!beneficiary) {
      return fail(404, 'NOT_FOUND', 'Beneficiary was not found.');
    }
    mutate(() => {
      beneficiary.active = false;
    });
    audit('BENEFICIARY_REMOVED', 'Beneficiary', beneficiary.accountNumber,
      `Beneficiary ${beneficiary.name} deactivated`);
    return delay({ message: 'Beneficiary removed from your list.' });
  },

  // ---- Transactions ------------------------------------------------------

  transfer(payload) {
    const user = requireCustomer();
    const state = getState();
    const amount = Number(payload.amount);
    const destination = (payload.destinationAccountNumber || '').trim();

    if (payload.idempotencyKey) {
      const existing = state.transactions.find((t) => t.idempotencyKey === payload.idempotencyKey);
      if (existing) {
        return delay(transactionView(existing));
      }
    }

    const source = state.accounts.find(
      (a) => a.accountNumber === (payload.sourceAccountNumber || '').trim() && a.customerId === user.customerId
    );
    if (!source) {
      return fail(404, 'NOT_FOUND', 'Source account was not found.');
    }
    if (source.status !== 'ACTIVE') {
      return fail(422, 'ACCOUNT_NOT_ACTIVE',
        `This account is ${source.status.toLowerCase()} and cannot be used for transfers.`);
    }
    if (source.accountNumber === destination) {
      return fail(422, 'SAME_ACCOUNT_TRANSFER', 'You cannot transfer money to the same account.');
    }
    if (!Number.isFinite(amount) || amount < 1) {
      return fail(400, 'VALIDATION_FAILED', 'Some of the information you entered is not valid.', {
        amount: 'Amount must be at least 1.00',
      });
    }

    if (!payload.idempotencyKey) {
      const since = Date.now() - DUPLICATE_WINDOW_MS;
      const duplicate = state.transactions.find(
        (t) =>
          t.sourceAccountId === source.id &&
          t.destinationAccountNumber === destination &&
          Number(t.amount) === amount &&
          new Date(t.createdAt).getTime() >= since
      );
      if (duplicate) {
        return fail(409, 'DUPLICATE_TRANSACTION',
          `An identical transfer was submitted moments ago (reference ${duplicate.reference}). Please check your transaction history before trying again.`);
      }
    }

    const available = source.balance - reservedAmount(source.id);
    if (available < amount) {
      audit('TRANSFER_REJECTED', 'Account', source.accountNumber,
        `Insufficient available balance: requested ${amount}, available ${available}`, 'FAILURE');
      return fail(422, 'INSUFFICIENT_BALANCE',
        'The available balance in this account is not sufficient for this transfer.');
    }

    const beneficiary =
      state.beneficiaries.find(
        (b) => b.customerId === user.customerId && b.accountNumber === destination && b.active
      ) || null;

    const internalDestination = state.accounts.find((a) => a.accountNumber === destination);
    const destinationName = beneficiary
      ? beneficiary.name
      : internalDestination
        ? (customerOf(internalDestination.customerId)?.fullName || `Account ${destination}`)
        : `Account ${destination}`;

    // Risk is assessed before the transaction is stored, so the transfer is not counted against
    // itself by the velocity rule.
    const customerTransfers = state.transactions.filter((t) => {
      const acc = state.accounts.find((a) => a.id === t.sourceAccountId);
      return acc && acc.customerId === user.customerId;
    });
    const failedVerifications = state.verifications.filter((v) => {
      if (v.status !== 'FAILED') return false;
      const txn = state.transactions.find((t) => t.id === v.transactionId);
      if (!txn) return false;
      const acc = state.accounts.find((a) => a.id === txn.sourceAccountId);
      return acc && acc.customerId === user.customerId;
    }).length;

    const evaluation = evaluateRisk({
      account: source,
      beneficiary,
      amount,
      recentTransfers: customerTransfers,
      failedVerifications,
      now: new Date(),
    });

    let txn;
    mutate((s) => {
      txn = {
        id: nextId('transaction'),
        reference: reference('TXN'),
        sourceAccountId: source.id,
        beneficiaryId: beneficiary ? beneficiary.id : null,
        destinationAccountNumber: destination,
        destinationName,
        amount,
        currency: source.currency,
        description: payload.description ? payload.description.trim() : null,
        status: 'PENDING',
        statusReason: null,
        riskLevel: evaluation.level,
        riskScore: evaluation.score,
        riskReason: evaluation.reason,
        idempotencyKey: payload.idempotencyKey || null,
        initiatedBy: user.username,
        createdAt: new Date().toISOString(),
        completedAt: null,
      };
      s.transactions.unshift(txn);
    });

    audit('TRANSFER_INITIATED', 'Transaction', txn.reference,
      `Transfer of ${amount} to ${destination} initiated`);
    audit('RISK_EVALUATED', 'Transaction', txn.reference,
      `Risk ${evaluation.level} score ${evaluation.score}: ${evaluation.reason}`);

    if (evaluation.level === 'LOW') {
      settleApproved(txn, 'Low risk transfer approved automatically');
    } else if (evaluation.level === 'MEDIUM') {
      mutate(() => {
        txn.status = 'PENDING_VERIFICATION';
        txn.statusReason = 'Additional verification is required before this transfer can proceed.';
      });
      raiseAlert(txn, evaluation, 'OPEN');

      const code = sixDigitCode();
      mutate((s) => {
        s.verifications.unshift({
          id: nextId('verification'),
          transactionId: txn.id,
          code,
          status: 'PENDING',
          attempts: 0,
          maxAttempts: MAX_ATTEMPTS,
          createdAt: new Date().toISOString(),
          expiresAt: new Date(Date.now() + VERIFICATION_VALIDITY_MS).toISOString(),
          verifiedAt: null,
        });
      });
      audit('VERIFICATION_REQUESTED', 'Transaction', txn.reference,
        'Verification code issued, valid for 10 minutes');
      notify(source.customerId, 'VERIFICATION',
        `Verification code for transfer ${txn.reference}`,
        `Your verification code is ${code}. It is valid for 10 minutes. Enter it to release your transfer of ${txn.currency} ${amount} to ${destinationName}.`,
        txn.reference);
    } else {
      mutate(() => {
        txn.status = 'PENDING';
        txn.statusReason =
          'Held for fraud review because the transfer was classified as high risk.';
      });
      const alert = raiseAlert(txn, evaluation, 'UNDER_REVIEW');
      openCase(alert, txn, null);
      notify(source.customerId, 'FRAUD_ALERT', 'Transfer held for review',
        `Your transfer of ${txn.currency} ${amount} to ${destinationName} (reference ${txn.reference}) has been held for a security review. You will be notified once the review is complete.`,
        txn.reference);
    }

    return delay(transactionView(txn));
  },

  transactions(page = 0, size = 20) {
    const user = requireCustomer();
    const state = getState();
    const own = state.transactions.filter((t) => {
      const acc = state.accounts.find((a) => a.id === t.sourceAccountId);
      return acc && acc.customerId === user.customerId;
    });
    const start = page * size;
    const content = own.slice(start, start + size).map(transactionView);
    return delay({
      content,
      page,
      size,
      totalElements: own.length,
      totalPages: Math.max(1, Math.ceil(own.length / size)),
      last: start + size >= own.length,
    });
  },

  transaction(ref) {
    const user = requireCustomer();
    const state = getState();
    const txn = state.transactions.find((t) => t.reference === ref);
    if (!txn) return fail(404, 'NOT_FOUND', `Transaction ${ref} was not found.`);
    const acc = state.accounts.find((a) => a.id === txn.sourceAccountId);
    if (!acc || acc.customerId !== user.customerId) {
      return fail(404, 'NOT_FOUND', `Transaction ${ref} was not found.`);
    }
    return delay(transactionView(txn));
  },

  verificationStatus(ref) {
    requireCustomer();
    const state = getState();
    const txn = state.transactions.find((t) => t.reference === ref);
    if (!txn) return fail(404, 'NOT_FOUND', 'Verification request was not found.');
    const verification = state.verifications.find((v) => v.transactionId === txn.id);
    if (!verification) return fail(404, 'NOT_FOUND', 'Verification request was not found.');
    return delay({
      transactionReference: txn.reference,
      status: verification.status,
      attempts: verification.attempts,
      maxAttempts: verification.maxAttempts,
      attemptsRemaining: Math.max(0, verification.maxAttempts - verification.attempts),
      expiresAt: verification.expiresAt,
    });
  },

  submitVerification(ref, code) {
    const user = requireCustomer();
    const state = getState();
    const txn = state.transactions.find((t) => t.reference === ref);
    if (!txn) return fail(404, 'NOT_FOUND', `Transaction ${ref} was not found.`);

    const acc = state.accounts.find((a) => a.id === txn.sourceAccountId);
    if (!acc || acc.customerId !== user.customerId) {
      return fail(404, 'NOT_FOUND', `Transaction ${ref} was not found.`);
    }
    if (txn.status !== 'PENDING_VERIFICATION') {
      return fail(422, 'INVALID_STATE',
        `This transfer is not waiting for verification. Its current status is ${txn.status}.`);
    }

    const verification = state.verifications.find(
      (v) => v.transactionId === txn.id && v.status === 'PENDING'
    );
    if (!verification) {
      return fail(422, 'NO_PENDING_VERIFICATION',
        'There is no verification request waiting for this transfer.');
    }

    if (Date.now() > new Date(verification.expiresAt).getTime()) {
      mutate(() => {
        verification.status = 'EXPIRED';
      });
      settleBlocked(txn, 'The verification code expired before it was entered.');
      return fail(410, 'VERIFICATION_EXPIRED',
        'The verification code has expired and the transfer has been blocked.');
    }

    if (verification.code !== code) {
      mutate(() => {
        verification.attempts += 1;
      });

      if (verification.attempts >= verification.maxAttempts) {
        mutate(() => {
          verification.status = 'FAILED';
        });
        audit('VERIFICATION_FAILED', 'Transaction', txn.reference,
          'All verification attempts were used', 'FAILURE');
        settleBlocked(txn, `Verification failed after ${verification.maxAttempts} attempts.`);

        const alert = state.alerts.find((a) => a.transactionId === txn.id);
        if (alert && !state.cases.some((c) => c.alertId === alert.id)) {
          mutate(() => {
            alert.status = 'UNDER_REVIEW';
          });
          openCase(alert, txn, 'Opened automatically after repeated verification failure.');
        }
        return fail(422, 'VERIFICATION_FAILED',
          'The transfer has been blocked because the verification code was entered incorrectly too many times.');
      }

      audit('VERIFICATION_FAILED', 'Transaction', txn.reference,
        `Incorrect code, attempt ${verification.attempts} of ${verification.maxAttempts}`, 'FAILURE');
      const remaining = verification.maxAttempts - verification.attempts;
      return fail(422, 'INVALID_VERIFICATION_CODE',
        `That verification code is not correct. You have ${remaining} attempt(s) remaining.`);
    }

    mutate(() => {
      verification.status = 'VERIFIED';
      verification.verifiedAt = new Date().toISOString();
    });
    audit('VERIFICATION_SUCCESS', 'Transaction', txn.reference,
      'Verification completed successfully');
    settleApproved(txn, 'Released after successful additional verification.');

    const alert = getState().alerts.find((a) => a.transactionId === txn.id);
    if (alert) {
      mutate(() => {
        alert.status = 'CLOSED';
      });
    }
    return delay(transactionView(txn));
  },

  // ---- Notifications -----------------------------------------------------

  notifications() {
    const user = requireCustomer();
    return delay(getState().notifications.filter((n) => n.customerId === user.customerId));
  },

  unreadCount() {
    const user = requireCustomer();
    return delay({
      unread: getState().notifications.filter((n) => n.customerId === user.customerId && !n.read)
        .length,
    });
  },

  markNotificationRead(id) {
    const user = requireCustomer();
    const notification = getState().notifications.find(
      (n) => n.id === id && n.customerId === user.customerId
    );
    if (!notification) return fail(404, 'NOT_FOUND', 'Notification was not found.');
    mutate(() => {
      notification.read = true;
    });
    return delay(notification);
  },

  markAllNotificationsRead() {
    const user = requireCustomer();
    let count = 0;
    mutate((state) => {
      state.notifications
        .filter((n) => n.customerId === user.customerId && !n.read)
        .forEach((n) => {
          n.read = true;
          count += 1;
        });
    });
    return delay({ message: `${count} notification(s) marked as read.` });
  },

  // ---- Disputes ----------------------------------------------------------

  submitDispute(payload) {
    const user = requireCustomer();
    const state = getState();
    const txn = state.transactions.find(
      (t) => t.reference === (payload.transactionReference || '').trim()
    );
    if (!txn) return fail(404, 'NOT_FOUND', 'Transaction was not found.');

    const acc = state.accounts.find((a) => a.id === txn.sourceAccountId);
    if (!acc || acc.customerId !== user.customerId) {
      return fail(404, 'NOT_FOUND', 'Transaction was not found.');
    }
    if (state.disputes.some(
      (d) => d.transactionId === txn.id && (d.status === 'OPEN' || d.status === 'UNDER_REVIEW')
    )) {
      return fail(409, 'DISPUTE_ALREADY_OPEN',
        'There is already an open dispute for this transaction.');
    }

    let created;
    mutate((s) => {
      created = {
        id: nextId('dispute'),
        reference: reference('DSP'),
        customerId: user.customerId,
        transactionId: txn.id,
        subject: payload.subject.trim(),
        description: payload.description.trim(),
        status: 'OPEN',
        resolution: null,
        handledBy: null,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      };
      s.disputes.unshift(created);
    });

    audit('DISPUTE_SUBMITTED', 'Dispute', created.reference,
      `Dispute raised against transaction ${txn.reference}`);
    notify(user.customerId, 'DISPUTE', 'Complaint received',
      `We have received your complaint ${created.reference} about transaction ${txn.reference}. Our team will review it and update you.`,
      created.reference);

    return delay(disputeView(created));
  },

  myDisputes() {
    const user = requireCustomer();
    return delay(
      getState().disputes.filter((d) => d.customerId === user.customerId).map(disputeView)
    );
  },

  disputeQueue() {
    requireUser();
    return delay(getState().disputes.map(disputeView));
  },

  resolveDispute(ref, payload) {
    const user = requireUser();
    const dispute = getState().disputes.find((d) => d.reference === ref);
    if (!dispute) return fail(404, 'NOT_FOUND', `Dispute ${ref} was not found.`);
    if (dispute.status === 'RESOLVED' || dispute.status === 'REJECTED') {
      return fail(422, 'DISPUTE_ALREADY_CLOSED',
        `This dispute was already closed as ${dispute.status}.`);
    }

    mutate(() => {
      dispute.status = payload.status;
      dispute.resolution = payload.resolution.trim();
      dispute.handledBy = user.username;
      dispute.updatedAt = new Date().toISOString();
    });

    audit('DISPUTE_RESOLVED', 'Dispute', dispute.reference,
      `Dispute set to ${payload.status}: ${payload.resolution}`);
    notify(dispute.customerId, 'DISPUTE', `Update on complaint ${dispute.reference}`,
      `Your complaint ${dispute.reference} is now ${payload.status}. ${payload.resolution}`,
      dispute.reference);

    return delay(disputeView(dispute));
  },

  // ---- Fraud monitoring --------------------------------------------------

  alerts(status) {
    requireUser();
    const all = getState().alerts;
    const filtered = status ? all.filter((a) => a.status === status) : all;
    return delay(filtered.map(alertView));
  },

  alert(ref) {
    requireUser();
    const found = getState().alerts.find((a) => a.reference === ref);
    if (!found) return fail(404, 'NOT_FOUND', `Fraud alert ${ref} was not found.`);
    return delay(alertView(found));
  },

  fraudCases() {
    requireUser();
    return delay(getState().cases.map(caseView));
  },

  fraudCase(ref) {
    requireUser();
    const found = getState().cases.find((c) => c.reference === ref);
    if (!found) return fail(404, 'NOT_FOUND', `Fraud case ${ref} was not found.`);
    return delay(caseView(found));
  },

  assignCase(ref) {
    const user = requireUser();
    const state = getState();
    const fraudCase = state.cases.find((c) => c.reference === ref);
    if (!fraudCase) return fail(404, 'NOT_FOUND', `Fraud case ${ref} was not found.`);
    if (fraudCase.status.startsWith('RESOLVED')) {
      return fail(422, 'CASE_ALREADY_RESOLVED',
        `This case was already resolved as ${fraudCase.status}.`);
    }

    mutate(() => {
      fraudCase.assignedTo = user.username;
      fraudCase.status = 'UNDER_REVIEW';
      fraudCase.updatedAt = new Date().toISOString();
      const alert = state.alerts.find((a) => a.id === fraudCase.alertId);
      if (alert) alert.status = 'UNDER_REVIEW';
    });

    return delay(caseView(fraudCase));
  },

  decideCase(ref, payload) {
    const user = requireUser();
    const state = getState();
    const fraudCase = state.cases.find((c) => c.reference === ref);
    if (!fraudCase) return fail(404, 'NOT_FOUND', `Fraud case ${ref} was not found.`);
    if (fraudCase.status.startsWith('RESOLVED')) {
      return fail(422, 'CASE_ALREADY_RESOLVED',
        `This case was already resolved as ${fraudCase.status}.`);
    }

    const alert = state.alerts.find((a) => a.id === fraudCase.alertId);
    const txn = state.transactions.find((t) => t.id === alert.transactionId);

    if (txn.status === 'APPROVED' || txn.status === 'BLOCKED' || txn.status === 'FAILED') {
      return fail(422, 'INVALID_STATE',
        `This transfer is already ${txn.status} and can no longer be changed.`);
    }

    const approve = String(payload.decision).toUpperCase() === 'APPROVE';
    if (approve) {
      settleApproved(txn, `Released by fraud review: ${payload.remarks}`);
    } else {
      settleBlocked(txn, `Blocked by fraud review: ${payload.remarks}`);
    }

    mutate(() => {
      fraudCase.status = approve ? 'RESOLVED_APPROVED' : 'RESOLVED_BLOCKED';
      fraudCase.remarks = payload.remarks;
      fraudCase.decidedBy = user.username;
      fraudCase.assignedTo = fraudCase.assignedTo || user.username;
      fraudCase.closedAt = new Date().toISOString();
      fraudCase.updatedAt = new Date().toISOString();
      alert.status = 'CLOSED';
    });

    audit('FRAUD_CASE_DECIDED', 'FraudCase', fraudCase.reference,
      `Decision ${String(payload.decision).toUpperCase()} on transaction ${txn.reference}: ${payload.remarks}`);

    return delay(caseView(fraudCase));
  },

  // ---- Administration ----------------------------------------------------

  users() {
    requireUser();
    return delay(getState().users.map(userView));
  },

  roles() {
    requireUser();
    return delay(['BANK_ADMIN', 'CUSTOMER', 'FRAUD_ANALYST', 'OPS_OFFICER', 'SYSTEM_ADMIN']);
  },

  updateUserRoles(userId, roles) {
    const actor = requireUser();
    const target = getState().users.find((u) => u.id === userId);
    if (!target) return fail(404, 'NOT_FOUND', 'User was not found.');
    if (!roles || roles.length === 0) {
      return fail(400, 'VALIDATION_FAILED', 'A user must keep at least one role.', {
        roles: 'A user must keep at least one role',
      });
    }
    if (target.username === actor.username && !roles.includes('BANK_ADMIN')) {
      return fail(422, 'CANNOT_REMOVE_OWN_ADMIN_ROLE',
        'You cannot remove the administrator role from your own account.');
    }

    const previous = [...target.roles].sort();
    mutate(() => {
      target.roles = [...roles];
    });
    audit('USER_ROLES_UPDATED', 'ApplicationUser', target.username,
      `Roles changed from [${previous.join(', ')}] to [${[...roles].sort().join(', ')}]`);
    return delay(userView(target));
  },

  updateUserStatus(userId, enabled) {
    const actor = requireUser();
    const target = getState().users.find((u) => u.id === userId);
    if (!target) return fail(404, 'NOT_FOUND', 'User was not found.');
    if (target.username === actor.username && !enabled) {
      return fail(422, 'CANNOT_DISABLE_SELF', 'You cannot disable your own login.');
    }
    mutate(() => {
      target.enabled = enabled;
    });
    audit('USER_STATUS_UPDATED', 'ApplicationUser', target.username,
      `Login ${enabled ? 'enabled' : 'disabled'}`);
    return delay(userView(target));
  },

  // ---- Audit and reports -------------------------------------------------

  audit(params = {}) {
    requireUser();
    const { username, action, page = 0, size = 25 } = params;
    let entries = getState().auditLog;
    if (username) {
      entries = entries.filter((e) => e.username === username);
    }
    if (action) {
      entries = entries.filter((e) => e.action === action);
    }
    const start = page * size;
    return delay({
      content: entries.slice(start, start + size),
      page,
      size,
      totalElements: entries.length,
      totalPages: Math.max(1, Math.ceil(entries.length / size)),
      last: start + size >= entries.length,
    });
  },

  operationalReport() {
    requireUser();
    const state = getState();
    const byStatus = (status) => state.transactions.filter((t) => t.status === status).length;
    const report = {
      generatedAt: new Date().toISOString(),
      totalCustomers: state.users.filter((u) => u.customerId).length,
      totalAccounts: state.accounts.length,
      totalTransactions: state.transactions.length,
      approvedTransactions: byStatus('APPROVED'),
      pendingTransactions: byStatus('PENDING'),
      pendingVerificationTransactions: byStatus('PENDING_VERIFICATION'),
      blockedTransactions: byStatus('BLOCKED'),
      failedTransactions: byStatus('FAILED'),
      totalApprovedAmount: state.transactions
        .filter((t) => t.status === 'APPROVED')
        .reduce((sum, t) => sum + Number(t.amount), 0),
      openDisputes: state.disputes.filter((d) => d.status === 'OPEN').length,
    };
    audit('REPORT_GENERATED', 'Report', 'OPERATIONAL', 'OPERATIONAL report generated');
    return delay(report);
  },

  fraudReport() {
    requireUser();
    const state = getState();
    const byRisk = (level) => state.transactions.filter((t) => t.riskLevel === level).length;
    const total = state.transactions.length;
    const report = {
      generatedAt: new Date().toISOString(),
      lowRiskTransactions: byRisk('LOW'),
      mediumRiskTransactions: byRisk('MEDIUM'),
      highRiskTransactions: byRisk('HIGH'),
      totalAlerts: state.alerts.length,
      openAlerts: state.alerts.filter((a) => a.status === 'OPEN').length,
      closedAlerts: state.alerts.filter((a) => a.status === 'CLOSED').length,
      totalCases: state.cases.length,
      openCases: state.cases.filter((c) => !c.status.startsWith('RESOLVED')).length,
      casesApproved: state.cases.filter((c) => c.status === 'RESOLVED_APPROVED').length,
      casesBlocked: state.cases.filter((c) => c.status === 'RESOLVED_BLOCKED').length,
      detectionRatePercent: total === 0 ? 0 : Math.round((state.alerts.length / total) * 10000) / 100,
    };
    audit('REPORT_GENERATED', 'Report', 'FRAUD', 'FRAUD report generated');
    return delay(report);
  },

  // ---- Showcase-only controls -------------------------------------------

  /** Clears the simulated data and signs the visitor out. Showcase mode only. */
  resetShowcaseData() {
    resetShowcase();
    try {
      window.localStorage.removeItem(SESSION_KEY);
    } catch {
      // Nothing to clear.
    }
  },
};

/**
 * Every method is wrapped so that a guard throwing synchronously still reaches the caller as a
 * rejected promise. Without this a screen calling `service.x().catch(...)` would see the error
 * escape the promise chain entirely.
 */
const showcaseBankingService = Object.fromEntries(
  Object.entries(service).map(([name, fn]) => [
    name,
    (...args) => {
      try {
        return fn.apply(service, args);
      } catch (error) {
        return Promise.reject(error);
      }
    },
  ])
);

export default showcaseBankingService;
