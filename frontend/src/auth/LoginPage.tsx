import { type ReactNode, useState } from 'react';
import { Container, Link, Stack } from '@mui/material';
import { Link as RouterLink, useLocation, useNavigate } from 'react-router-dom';
import { login } from './authApi';
import { useSession } from './SessionContext';
import LoginForm from './LoginForm';
import CheckEmailPanel from './CheckEmailPanel';

function defaultLandingPath(lastMediaType: string | null): string {
  return `/${lastMediaType ?? 'movies'}/watchlist`;
}

function LoginPage(): ReactNode {
  const [unverifiedEmail, setUnverifiedEmail] = useState<string | null>(null);
  const { refresh } = useSession();
  const navigate = useNavigate();
  const location = useLocation();

  async function goToApp(lastMediaType: string | null): Promise<void> {
    // The session cookie is already set by the login response; refresh the
    // context so RequireAuth sees 'authenticated' before it re-renders.
    await refresh();
    const from = (location.state as { from?: string } | null)?.from;
    void navigate(from ?? defaultLandingPath(lastMediaType), { replace: true });
  }

  return (
    <Container maxWidth="sm" sx={{ py: 8 }}>
      {unverifiedEmail === null ? (
        <Stack spacing={2}>
          <LoginForm
            submit={login}
            onAuthenticated={(lastMediaType) => void goToApp(lastMediaType)}
            onUnverified={setUnverifiedEmail}
          />
          <Link component={RouterLink} to="/forgot-password" variant="body2">
            Forgot your password?
          </Link>
        </Stack>
      ) : (
        <CheckEmailPanel email={unverifiedEmail} />
      )}
    </Container>
  );
}

export default LoginPage;
