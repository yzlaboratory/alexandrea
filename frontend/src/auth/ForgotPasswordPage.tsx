import { type ReactNode, useState } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { Container, Link, Typography } from '@mui/material';
import { requestPasswordReset } from './authApi';
import AuthPanel from './AuthPanel';
import ForgotPasswordForm from './ForgotPasswordForm';

function ForgotPasswordPage(): ReactNode {
  const [requestedEmail, setRequestedEmail] = useState<string | null>(null);

  return (
    <Container maxWidth="sm" sx={{ py: 8 }}>
      {requestedEmail === null ? (
        <ForgotPasswordForm
          onRequested={setRequestedEmail}
          submit={requestPasswordReset}
        />
      ) : (
        <RequestSentPanel email={requestedEmail} />
      )}
    </Container>
  );
}

function RequestSentPanel({ email }: { email: string }): ReactNode {
  return (
    <AuthPanel title="Check your email">
      <Typography>
        If <strong>{email}</strong> has an account, we&apos;ve sent a link to
        reset your password. The link is single-use.
      </Typography>
      <Link component={RouterLink} to="/login">
        Back to log in
      </Link>
    </AuthPanel>
  );
}

export default ForgotPasswordPage;
