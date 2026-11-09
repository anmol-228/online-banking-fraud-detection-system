import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import api from '../services/bankingService.js';
import { getToken, setToken } from '../api/client.js';
import { isAuthRejection, isTransientFailure } from './sessionErrors.js';

const AuthContext = createContext(null);

/** Waits before each retry while the server is unavailable; roughly three minutes in total. */
const WAKE_RETRY_DELAYS_MS = [1500, 3000, 5000, 8000, 12000, 15000, 20000, 25000, 30000, 30000, 30000];

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Holds the identity of the signed-in user for the whole application.
 *
 * The token lives in localStorage so a page refresh does not sign the user out. On start-up the
 * profile endpoint is called to confirm that the stored token is still valid:
 *
 * - if the server REJECTS the token (401), it is discarded and the user is sent to the login page;
 * - if the server merely cannot ANSWER (a hosted back end waking up, a database resuming, a
 *   dropped connection, a rate limit) the token is KEPT and the call is retried with a growing
 *   delay. `serverWaking` tells the interface to say so instead of showing a blank login page.
 */
export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(true);
  const [serverWaking, setServerWaking] = useState(false);

  useEffect(() => {
    let cancelled = false;

    async function restoreSession() {
      if (!getToken()) {
        setLoading(false);
        return;
      }
      for (let attempt = 0; ; attempt += 1) {
        try {
          const { data } = await api.profile();
          if (!cancelled) {
            setUser(data);
            setServerWaking(false);
            setLoading(false);
          }
          return;
        } catch (error) {
          if (cancelled) {
            return;
          }
          if (isAuthRejection(error)) {
            setToken(null);
            setUser(null);
            setServerWaking(false);
            setLoading(false);
            return;
          }
          if (!isTransientFailure(error) || attempt >= WAKE_RETRY_DELAYS_MS.length) {
            // A definitive non-auth answer, or the server stayed unavailable for minutes. The
            // token is still kept; the user lands on the login page and can sign in again.
            setUser(null);
            setServerWaking(false);
            setLoading(false);
            return;
          }
          setServerWaking(true);
          await sleep(WAKE_RETRY_DELAYS_MS[attempt]);
        }
      }
    }

    restoreSession();
    return () => {
      cancelled = true;
    };
  }, []);

  const login = useCallback(async (username, password) => {
    const { data } = await api.login({ username, password });
    setToken(data.token);
    const profile = await api.profile();
    setUser(profile.data);
    return data;
  }, []);

  const register = useCallback(async (payload) => {
    const { data } = await api.register(payload);
    setToken(data.token);
    const profile = await api.profile();
    setUser(profile.data);
    return data;
  }, []);

  const logout = useCallback(() => {
    setToken(null);
    setUser(null);
  }, []);

  const value = useMemo(
    () => ({
      user,
      loading,
      serverWaking,
      login,
      register,
      logout,
      roles: user?.roles || [],
      hasRole: (role) => (user?.roles || []).includes(role),
      hasAnyRole: (...wanted) => wanted.some((role) => (user?.roles || []).includes(role)),
      isAuthenticated: Boolean(user),
    }),
    [user, loading, serverWaking, login, register, logout]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used inside an AuthProvider');
  }
  return context;
}
