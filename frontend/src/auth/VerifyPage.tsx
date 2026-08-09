import {
  type ReactNode,
  useActionState,
  useEffect,
  useRef,
  useState,
} from 'react';
import { Link as RouterLink, useSearchParams } from 'react-router-dom';
import {
  Alert,
  Button,
  CircularProgress,
  Container,
  Link,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { resendVerification, verify, type VerifyOutcome } from './authApi';
import AuthPanel from './AuthPanel';
import { textField } from './forms';

type ResendState = 'idle' | 'sent' | 'error';

function VerifyPage(): ReactNode {
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token');

  return (
    <Container maxWidth="sm" sx={{ py: 8 }}>
      {token === null || token === '' ? (
        <RejectedPanel />
      ) : (
        <TokenVerification token={token} />
      )}
    </Container>
  );
}

// Resolving a token on display is a genuine "fetch because shown" Effect (stack.md).
function TokenVerification({ token }: { token: string }): ReactNode {
  const [outcome, setOutcome] = useState<VerifyOutcome | null>(null);
  const requestedToken = useRef<string | null>(null);

  // verify() spends a single-use token, so the POST must fire exactly once. A
  // ref dedupes StrictMode's double-invoke (the second call would 410 against
  // the just-consumed token and wrongly show a rejected link); the in-closure
  // token also guards against a stale result if the token ever changed.
  useEffect(() => {
    if (requestedToken.current === token) return;
    requestedToken.current = token;
    void verify(token).then((result) => {
      if (requestedToken.current === token) setOutcome(result);
    });
  }, [token]);

  if (outcome === null) {
    return (
      <Stack spacing={2} sx={{ alignItems: 'center' }}>
        <CircularProgress />
        <Typography>Verifying your email…</Typography>
      </Stack>
    );
  }
  switch (outcome) {
    case 'verified':
      return <VerifiedPanel />;
    case 'rejected':
      return <RejectedPanel />;
    case 'error':
      return (
        <Alert severity="error">
          Something went wrong verifying your email. Please try again.
        </Alert>
      );
  }
}

function VerifiedPanel(): ReactNode {
  return (
    <AuthPanel title="Email verified">
      <Typography>Your account is active. You can now log in.</Typography>
      <Link component={RouterLink} to="/login">
        Go to log in
      </Link>
    </AuthPanel>
  );
}

// Resend needs the address, which a clicked verification link does not carry, so
// it is collected here.
function RejectedPanel(): ReactNode {
  const [resend, resendAction, isResending] = useActionState<
    ResendState,
    FormData
  >(async (_previous, formData) => {
    try {
      await resendVerification(textField(formData, 'email'));
      return 'sent';
    } catch {
      return 'error';
    }
  }, 'idle');

  return (
    <AuthPanel title="This link is no longer valid">
      <Typography>
        Verification links are single-use and expire after 24 hours. Enter your
        email to get a fresh one.
      </Typography>

      {resend === 'sent' && (
        <Alert severity="success">
          If that address has a pending account, we sent a new link.
        </Alert>
      )}
      {resend === 'error' && (
        <Alert severity="error">Something went wrong. Please try again.</Alert>
      )}

      <Stack direction="row" spacing={2} component="form" action={resendAction}>
        <TextField
          name="email"
          label="Email"
          type="email"
          size="small"
          required
        />
        <Button type="submit" variant="outlined" disabled={isResending}>
          {isResending ? 'Resending…' : 'Resend link'}
        </Button>
      </Stack>
    </AuthPanel>
  );
}

export default VerifyPage;
