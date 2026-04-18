import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';

import { useMe } from './useAuth';

export function RedirectIfAuthed({ children }: { children: ReactNode }) {
  const { data, isLoading } = useMe();
  if (isLoading) return null;
  if (data) return <Navigate to="/" replace />;
  return <>{children}</>;
}
