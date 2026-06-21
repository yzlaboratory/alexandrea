import { type JSX, useState } from 'react';
import { Container } from '@mui/material';
import { signup } from './authApi';
import SignupForm from './SignupForm';
import CheckEmailPanel from './CheckEmailPanel';

// Two-state signup surface: the form, then — once signup is accepted — the
// "check your email to verify" panel with a resend option. Which one shows is
// derived from whether we hold a submitted address, so there is no Effect
// syncing the transition (stack.md: that belongs in the submit handler).
function SignupPage(): JSX.Element {
  const [submittedEmail, setSubmittedEmail] = useState<string | null>(null);

  return (
    <Container maxWidth="sm" sx={{ py: 8 }}>
      {submittedEmail === null ? (
        <SignupForm onAccepted={setSubmittedEmail} submit={signup} />
      ) : (
        <CheckEmailPanel email={submittedEmail} />
      )}
    </Container>
  );
}

export default SignupPage;
