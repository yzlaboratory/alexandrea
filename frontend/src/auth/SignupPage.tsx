import { type ReactNode, useState } from 'react';
import { Container } from '@mui/material';
import { signup } from './authApi';
import SignupForm from './SignupForm';
import CheckEmailPanel from './CheckEmailPanel';

function SignupPage(): ReactNode {
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
