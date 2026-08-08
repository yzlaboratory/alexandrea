import { type ReactNode, useState } from 'react';
import { Container } from '@mui/material';
import { useLocation, useNavigate } from 'react-router-dom';
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
        <LoginForm
          submit={login}
          onAuthenticated={(lastMediaType) => void goToApp(lastMediaType)}
          onUnverified={setUnverifiedEmail}
        />
      ) : (
        <CheckEmailPanel email={unverifiedEmail} />
      )}
    </Container>
  );
}

export default LoginPage;
