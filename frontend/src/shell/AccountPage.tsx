import { type ReactNode, useState } from 'react';
import { Alert, Container, Stack, Typography } from '@mui/material';
import { changePassword, requestEmailChange } from '../auth/authApi';
import ChangePasswordForm from '../auth/ChangePasswordForm';
import ChangeEmailForm from '../auth/ChangeEmailForm';

function AccountPage(): ReactNode {
  // Bumping a form's key remounts it, clearing its uncontrolled fields —
  // simpler than lifting the field values into state just to reset them.
  const [changeCount, setChangeCount] = useState(0);
  const [emailChangeCount, setEmailChangeCount] = useState(0);
  const [requestedEmail, setRequestedEmail] = useState<string | null>(null);

  return (
    <Container maxWidth="sm" sx={{ py: 8 }}>
      <Stack spacing={5}>
        <Typography variant="h4" component="h1">
          Account settings
        </Typography>

        <Stack spacing={2}>
          {changeCount > 0 && (
            <Alert severity="success">
              Password updated. You&apos;ve been signed out of your other
              sessions.
            </Alert>
          )}
          <ChangePasswordForm
            key={changeCount}
            onChanged={() => {
              setChangeCount((count) => count + 1);
            }}
            submit={changePassword}
          />
        </Stack>

        <Stack spacing={2}>
          {requestedEmail !== null && (
            <Alert severity="success">
              If <strong>{requestedEmail}</strong> is available, we&apos;ve sent
              a confirmation link there. Your email stays the same until you
              open it.
            </Alert>
          )}
          <ChangeEmailForm
            key={emailChangeCount}
            onRequested={(newEmail) => {
              setEmailChangeCount((count) => count + 1);
              setRequestedEmail(newEmail);
            }}
            submit={requestEmailChange}
          />
        </Stack>
      </Stack>
    </Container>
  );
}

export default AccountPage;
