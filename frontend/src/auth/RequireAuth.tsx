import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';

import { useMe } from './useAuth';

export function RequireAuth({ children }: { children: ReactNode }) {
  const { data, isLoading } = useMe();
  if (isLoading) return <FullscreenStatus>Loading…</FullscreenStatus>;
  if (!data) return <Navigate to="/login" replace />;
  return <>{children}</>;
}

function FullscreenStatus({ children }: { children: ReactNode }) {
  return (
    <div className="flex min-h-screen items-center justify-center text-slate-400">{children}</div>
  );
}
