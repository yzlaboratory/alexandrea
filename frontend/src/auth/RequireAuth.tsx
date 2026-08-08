import { type ReactNode } from 'react';
import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { CircularProgress, Stack } from '@mui/material';
import { useSession } from './SessionContext';

// Only routes wrapped in this element are protected — a future Share URL
// (#1) lives outside it and is reachable anonymously without any change here.
function RequireAuth(): ReactNode {
  const { status } = useSession();
  const location = useLocation();

  if (status === 'loading') {
    return (
      <Stack sx={{ alignItems: 'center', py: 10 }}>
        <CircularProgress />
      </Stack>
    );
  }

  if (status === 'anonymous') {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }

  return <Outlet />;
}

export default RequireAuth;
