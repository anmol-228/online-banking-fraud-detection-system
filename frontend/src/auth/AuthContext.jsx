import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import api from '../services/bankingService.js';
import { getToken, setToken } from '../api/client.js';

const AuthContext = createContext(null);

/**
 * Holds the identity of the signed-in user for the whole application.
 *
 * The token lives in localStorage so a page refresh does not sign the user out. On start-up the
 * profile endpoint is called once to confirm that the stored token is still valid; if it is not,
 * it is discarded and the user is sent back to the login screen.
 */
export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;

    async function restoreSession() {
      if (!getToken()) {
        setLoading(false);
        return;
      }
      try {
        const { data } = await api.profile();
        if (!cancelled) {
          setUser(data);
        }
      } catch {
        setToken(null);
        if (!cancelled) {
          setUser(null);
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
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
      login,
      register,
      logout,
      roles: user?.roles || [],
      hasRole: (role) => (user?.roles || []).includes(role),
      hasAnyRole: (...wanted) => wanted.some((role) => (user?.roles || []).includes(role)),
      isAuthenticated: Boolean(user),
    }),
    [user, loading, login, register, logout]
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
