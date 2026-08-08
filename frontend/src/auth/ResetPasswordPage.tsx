import { type ReactNode, useState } from 'react';
import { Link as RouterLink, useSearchParams } from 'react-router-dom';
import { Container, Link, Stack, Typography } from '@mui/material';
import { resetPassword } from './authApi';
import ResetPasswordForm from './ResetPasswordForm';

function ResetPasswordPage(): ReactNode {
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token');

  return (
    <Container maxWidth="sm" sx={{ py: 8 }}>
      {token === null || token === '' ? (
        <RejectedPanel />
      ) : (
        <ResetFlow token={token} />
      )}
    </Container>
  );
}

type Step = 'form' | 'reset' | 'rejected';

// A reset link is consumed by user-triggered submission (not on display, unlike
// verification), so the step lives here rather than in an Effect.
function ResetFlow({ token }: { token: string }): ReactNode {
  const [step, setStep] = useState<Step>('form');

  switch (step) {
    case 'form':
      return (
        <ResetPasswordForm
          token={token}
          onReset={() => {
            setStep('reset');
          }}
          onRejected={() => {
            setStep('rejected');
          }}
          submit={resetPassword}
        />
      );
    case 'reset':
      return <ResetPanel />;
    case 'rejected':
      return <RejectedPanel />;
  }
}

function ResetPanel(): ReactNode {
  return (
    <Stack spacing={3} sx={{ alignItems: 'flex-start' }}>
      <Typography variant="h4" component="h1">
        Password updated
      </Typography>
      <Typography>
        Your password has been changed and you&apos;ve been signed out of your
        other sessions. Log in with your new password.
      </Typography>
      <Link component={RouterLink} to="/login">
        Go to log in
      </Link>
    </Stack>
  );
}

function RejectedPanel(): ReactNode {
  return (
    <Stack spacing={3} sx={{ alignItems: 'flex-start' }}>
      <Typography variant="h4" component="h1">
        This link is no longer valid
      </Typography>
      <Typography>
        Password reset links are single-use and expire after 1 hour. Request a
        fresh one to try again.
      </Typography>
      <Link component={RouterLink} to="/forgot-password">
        Request a new link
      </Link>
    </Stack>
  );
}

export default ResetPasswordPage;
