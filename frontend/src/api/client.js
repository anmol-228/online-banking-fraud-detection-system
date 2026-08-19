import axios from 'axios';

const TOKEN_KEY = 'obfds.token';

/**
 * Single axios instance used by every screen.
 *
 * The base URL comes from the Vite environment so the same build can be pointed at a different
 * backend without editing source code.
 */
const client = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080',
  headers: { 'Content-Type': 'application/json' },
});

export function getToken() {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token) {
  if (token) {
    localStorage.setItem(TOKEN_KEY, token);
  } else {
    localStorage.removeItem(TOKEN_KEY);
  }
}

// Attach the bearer token to every outgoing request.
client.interceptors.request.use((config) => {
  const token = getToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

/**
 * Turns any backend error into a plain, readable message.
 *
 * The backend always answers with the same error shape, so the front end never has to display a
 * raw stack trace or an axios internal message to the user.
 */
export function readError(error) {
  const data = error?.response?.data;
  if (!data) {
    return 'The server could not be reached. Please check that the backend is running.';
  }
  if (data.fieldErrors && Object.keys(data.fieldErrors).length > 0) {
    return Object.values(data.fieldErrors).join(' ');
  }
  return data.message || 'Something went wrong while processing your request.';
}

/** Field-level messages, so a form can highlight the exact input that was wrong. */
export function readFieldErrors(error) {
  return error?.response?.data?.fieldErrors || {};
}

export function readErrorCode(error) {
  return error?.response?.data?.code || null;
}

export default client;
