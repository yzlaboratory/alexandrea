import { type JSX, useActionState } from 'react';
import { Alert, Button, Stack, Typography } from '@mui/material';
import { resendVerification } from './authApi';

interface CheckEmailPanelProps {
  email: string;
}

type ResendState = 'idle' | 'sent' | 'error';

// Resend is always success-shaped server-side (ADR 0024); the only feedback is a
// neutral acknowledgement, or a generic error if the request itself fails.
function CheckEmailPanel({ email }: CheckEmailPanelProps): JSX.Element {
  const [resend, resendAction, isResending] = useActionState<
    ResendState,
    FormData
  >(async () => {
    try {
      await resendVerification(email);
      return 'sent';
    } catch {
      return 'error';
    }
  }, 'idle');

  return (
    <Stack spacing={3} sx={{ alignItems: 'flex-start' }}>
      <Typography variant="h4" component="h1">
        Check your email
      </Typography>
      <Typography variant="body1">
        We sent a verification link to <strong>{email}</strong>. Open it to
        activate your account. The link is single-use and expires in 24 hours.
      </Typography>

      {resend === 'sent' && (
        <Alert severity="success">
          If that address has a pending account, we sent another link.
        </Alert>
      )}
      {resend === 'error' && (
        <Alert severity="error">Something went wrong. Please try again.</Alert>
      )}

      <form action={resendAction}>
        <Button type="submit" variant="outlined" disabled={isResending}>
          {isResending ? 'Resending…' : 'Resend verification email'}
        </Button>
      </form>
    </Stack>
  );
}

export default CheckEmailPanel;
