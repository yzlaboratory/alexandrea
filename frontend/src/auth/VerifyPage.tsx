import { type JSX, useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
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
import { Link as RouterLink } from 'react-router-dom';
import { resendVerification, verify, type VerifyOutcome } from './authApi';

// The verify-result page. It opens at the link the user clicked, lifts the
// token from the query string, and resolves it against /api/auth/verify on
// display — a genuine "fetch because the page was shown" Effect (stack.md). The
// four states are made impossible-to-confuse by a discriminated union rather
// than a bag of booleans.
function VerifyPage(): JSX.Element {
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token');

  return (
    <Container maxWidth="sm" sx={{ py: 8 }}>
      {token === null || token === '' ? (
        // A link without a token can never verify — it is just a rejected link,
        // decided during render so no Effect/setState dance is needed.
        <RejectedPanel />
      ) : (
        <TokenVerification token={token} />
      )}
    </Container>
  );
}

// Resolves a present token against the server on display — the one genuine
// "fetch because the page was shown" case (stack.md). The outcome is the only
// async-driven state; the loading and resolved views are derived from it.
function TokenVerification({ token }: { token: string }): JSX.Element {
  const [outcome, setOutcome] = useState<VerifyOutcome | null>(null);

  useEffect(() => {
    let active = true;
    void verify(token).then((result) => {
      if (active) setOutcome(result);
    });
    return () => {
      active = false;
    };
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

function VerifiedPanel(): JSX.Element {
  return (
    <Stack spacing={3} sx={{ alignItems: 'flex-start' }}>
      <Typography variant="h4" component="h1">
        Email verified
      </Typography>
      <Typography>Your account is active. You can now log in.</Typography>
      <Link component={RouterLink} to="/login">
        Go to log in
      </Link>
    </Stack>
  );
}

// An expired, already-used, or unknown link all land here with the same offer to
// resend (ADR 0024) — the page never reveals which rejection occurred. Resend
// needs the address, which a clicked link does not carry, so it is collected.
function RejectedPanel(): JSX.Element {
  const [email, setEmail] = useState('');
  const [resent, setResent] = useState(false);

  async function handleResend(): Promise<void> {
    await resendVerification(email);
    setResent(true);
  }

  return (
    <Stack spacing={3} sx={{ alignItems: 'flex-start' }}>
      <Typography variant="h4" component="h1">
        This link is no longer valid
      </Typography>
      <Typography>
        Verification links are single-use and expire after 24 hours. Enter your
        email to get a fresh one.
      </Typography>

      {resent && (
        <Alert severity="success">
          If that address has a pending account, we sent a new link.
        </Alert>
      )}

      <Stack
        direction="row"
        spacing={2}
        component="form"
        onSubmit={(event) => {
          event.preventDefault();
          void handleResend();
        }}
      >
        <TextField
          label="Email"
          type="email"
          size="small"
          required
          value={email}
          onChange={(event) => {
            setEmail(event.target.value);
          }}
        />
        <Button type="submit" variant="outlined">
          Resend link
        </Button>
      </Stack>
    </Stack>
  );
}

export default VerifyPage;
