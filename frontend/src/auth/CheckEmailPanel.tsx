import { type JSX, useActionState } from 'react';
import { Alert, Button, Stack, Typography } from '@mui/material';
import { resendVerification } from './authApi';

interface CheckEmailPanelProps {
  email: string;
}

// Resend is always success-shaped (ADR 0024), so the only feedback is a neutral acknowledgement.
function CheckEmailPanel({ email }: CheckEmailPanelProps): JSX.Element {
  const [resent, resendAction, isResending] = useActionState<boolean, FormData>(
    async () => {
      await resendVerification(email);
      return true;
    },
    false,
  );

  return (
    <Stack spacing={3} sx={{ alignItems: 'flex-start' }}>
      <Typography variant="h4" component="h1">
        Check your email
      </Typography>
      <Typography variant="body1">
        We sent a verification link to <strong>{email}</strong>. Open it to
        activate your account. The link is single-use and expires in 24 hours.
      </Typography>

      {resent && (
        <Alert severity="success">
          If that address has a pending account, we sent another link.
        </Alert>
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
