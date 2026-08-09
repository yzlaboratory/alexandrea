import { type ReactNode, useActionState } from 'react';
import { Alert, Button, Typography } from '@mui/material';
import { resendVerification } from './authApi';
import AuthPanel from './AuthPanel';

interface CheckEmailPanelProps {
  email: string;
}

type ResendState = 'idle' | 'sent' | 'error';

function CheckEmailPanel({ email }: CheckEmailPanelProps): ReactNode {
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
    <AuthPanel title="Check your email">
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
    </AuthPanel>
  );
}

export default CheckEmailPanel;
