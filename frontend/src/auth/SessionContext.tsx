import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { fetchSession, type SessionUser } from './authApi';

type SessionStatus = 'loading' | 'authenticated' | 'anonymous';

interface SessionState {
  user: SessionUser | null;
  status: SessionStatus;
  /** Re-fetches the session — called after a successful login or logout. */
  refresh: () => Promise<void>;
}

const SessionContext = createContext<SessionState | null>(null);

interface SessionProviderProps {
  children: ReactNode;
}

export function SessionProvider({ children }: SessionProviderProps): ReactNode {
  const [user, setUser] = useState<SessionUser | null>(null);
  const [status, setStatus] = useState<SessionStatus>('loading');

  const refresh = useCallback(async () => {
    const session = await fetchSession();
    setUser(session);
    setStatus(session === null ? 'anonymous' : 'authenticated');
  }, []);

  // Fetch-on-display: resolve who's logged in as soon as the app mounts.
  // Written inline (not via `refresh`) so the state updates live inside the
  // promise continuation rather than the effect's synchronous body.
  useEffect(() => {
    let cancelled = false;
    void fetchSession().then((session) => {
      if (cancelled) return;
      setUser(session);
      setStatus(session === null ? 'anonymous' : 'authenticated');
    });
    return () => {
      cancelled = true;
    };
  }, []);

  const value = useMemo(
    () => ({ user, status, refresh }),
    [user, status, refresh],
  );

  return (
    <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
  );
}

export function useSession(): SessionState {
  const context = useContext(SessionContext);
  if (context === null) {
    throw new Error('useSession must be called within a SessionProvider');
  }
  return context;
}
