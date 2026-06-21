import { type JSX, useState } from 'react';
import { Container } from '@mui/material';
import { signup } from './authApi';
import SignupForm from './SignupForm';
import CheckEmailPanel from './CheckEmailPanel';

// Which panel shows is derived from whether we hold a submitted address — no
// Effect syncing the transition (stack.md).
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
