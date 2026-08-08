import { type ReactNode, useActionState } from 'react';
import {
  Alert,
  Box,
  Button,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { type LoginOutcome } from './authApi';
import { textField } from './forms';

interface LoginFormProps {
  onAuthenticated: (lastMediaType: string | null) => void;
  onUnverified: (email: string) => void;
  /** The login call, injected so the form is testable without the network. */
  submit: (email: string, password: string) => Promise<LoginOutcome>;
}

type FormError = string | null;

function LoginForm({
  onAuthenticated,
  onUnverified,
  submit,
}: LoginFormProps): ReactNode {
  const [error, formAction, isPending] = useActionState<FormError, FormData>(
    async (_previous, formData) => {
      const email = textField(formData, 'email');
      const password = textField(formData, 'password');

      const outcome = await submit(email, password);
      switch (outcome.status) {
        case 'authenticated':
          onAuthenticated(outcome.lastMediaType);
          return null;
        case 'unverified':
          onUnverified(email);
          return null;
        case 'invalid-credentials':
          return 'Email or password is incorrect.';
        case 'error':
          return 'Something went wrong. Please try again.';
      }
    },
    null,
  );

  return (
    <Stack spacing={3} component="form" action={formAction}>
      <Typography variant="h4" component="h1">
        Log in
      </Typography>

      {error !== null && <Alert severity="error">{error}</Alert>}

      <TextField
        name="email"
        type="email"
        label="Email"
        autoComplete="email"
        required
        fullWidth
      />
      <TextField
        name="password"
        type="password"
        label="Password"
        autoComplete="current-password"
        required
        fullWidth
      />

      <Box>
        <Button
          type="submit"
          variant="contained"
          size="large"
          disabled={isPending}
        >
          {isPending ? 'Logging in…' : 'Log in'}
        </Button>
      </Box>
    </Stack>
  );
}

export default LoginForm;
