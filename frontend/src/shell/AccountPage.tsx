import { type ReactNode, useState } from 'react';
import { Alert, Container, Stack, Typography } from '@mui/material';
import { changePassword } from '../auth/authApi';
import ChangePasswordForm from '../auth/ChangePasswordForm';

function AccountPage(): ReactNode {
  // Bumping the key remounts the form, clearing its uncontrolled fields —
  // simpler than lifting the password values into state just to reset them.
  const [changeCount, setChangeCount] = useState(0);

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

        <Stack spacing={1}>
          <Typography variant="h6" component="h2">
            Change email
          </Typography>
          <Typography color="text.secondary">
            Changing your email address is coming in a later update.
          </Typography>
        </Stack>
      </Stack>
    </Container>
  );
}

export default AccountPage;
