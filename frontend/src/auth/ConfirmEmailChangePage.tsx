import { type ReactNode, useEffect, useRef, useState } from 'react';
import { Link as RouterLink, useSearchParams } from 'react-router-dom';
import {
  Alert,
  CircularProgress,
  Container,
  Link,
  Stack,
  Typography,
} from '@mui/material';
import { confirmEmailChange, type ConfirmEmailChangeOutcome } from './authApi';
import AuthPanel from './AuthPanel';

function ConfirmEmailChangePage(): ReactNode {
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token');

  return (
    <Container maxWidth="sm" sx={{ py: 8 }}>
      {token === null || token === '' ? (
        <RejectedPanel />
      ) : (
        <TokenConfirmation token={token} />
      )}
    </Container>
  );
}

// Resolving a token on display is a genuine "fetch because shown" Effect (stack.md).
function TokenConfirmation({ token }: { readonly token: string }): ReactNode {
  const [outcome, setOutcome] = useState<ConfirmEmailChangeOutcome | null>(
    null,
  );
  const requestedToken = useRef<string | null>(null);

  // confirmEmailChange() spends a single-use token, so the POST must fire
  // exactly once. A ref dedupes StrictMode's double-invoke (the second call
  // would 410 against the just-consumed token and wrongly show a rejected
  // link); the in-closure token also guards against a stale result if the
  // token ever changed.
  useEffect(() => {
    if (requestedToken.current === token) return;
    requestedToken.current = token;
    void confirmEmailChange(token).then((result) => {
      if (requestedToken.current === token) setOutcome(result);
    });
  }, [token]);

  if (outcome === null) {
    return (
      <Stack spacing={2} sx={{ alignItems: 'center' }}>
        <CircularProgress />
        <Typography>Confirming your new email…</Typography>
      </Stack>
    );
  }
  switch (outcome) {
    case 'changed':
      return <ChangedPanel />;
    case 'rejected':
      return <RejectedPanel />;
    case 'error':
      return (
        <Alert severity="error">
          Something went wrong confirming your email change. Please try again.
        </Alert>
      );
  }
}

function ChangedPanel(): ReactNode {
  return (
    <AuthPanel title="Email updated">
      <Typography>
        Your account email has been changed and you&apos;ve been signed out
        everywhere. Log in with your new email.
      </Typography>
      <Link component={RouterLink} to="/login">
        Go to log in
      </Link>
    </AuthPanel>
  );
}

function RejectedPanel(): ReactNode {
  return (
    <AuthPanel title="This link is no longer valid">
      <Typography>
        Email change links are single-use. Start the change again from your
        account settings to get a fresh one.
      </Typography>
      <Link component={RouterLink} to="/account">
        Go to account settings
      </Link>
    </AuthPanel>
  );
}

export default ConfirmEmailChangePage;
