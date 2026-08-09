import { type ReactNode, useActionState } from 'react';
import {
  Alert,
  Box,
  Button,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { type SignupOutcome } from './authApi';
import {
  MAX_PASSWORD_LENGTH,
  MIN_PASSWORD_LENGTH,
  PASSWORD_LENGTH_ERROR,
  PASSWORD_LENGTH_HINT,
  textField,
} from './forms';

interface SignupFormProps {
  onAccepted: (email: string) => void;
  /** The signup call, injected so the form is testable without the network. */
  submit: (email: string, password: string) => Promise<SignupOutcome>;
}

type FormError = string | null;

function SignupForm({ onAccepted, submit }: SignupFormProps): ReactNode {
  const [error, formAction, isPending] = useActionState<FormError, FormData>(
    async (_previous, formData) => {
      const email = textField(formData, 'email');
      const password = textField(formData, 'password');

      const outcome = await submit(email, password);
      switch (outcome.status) {
        case 'accepted':
          onAccepted(email);
          return null;
        case 'invalid-password':
          return PASSWORD_LENGTH_ERROR;
        case 'error':
          return 'Something went wrong. Please try again.';
      }
    },
    null,
  );

  return (
    <Stack spacing={3} component="form" action={formAction}>
      <Typography variant="h4" component="h1">
        Create your account
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
        autoComplete="new-password"
        required
        // Client-side hint mirrors the server policy; the server is the source
        // of truth and re-validates.
        slotProps={{
          htmlInput: {
            minLength: MIN_PASSWORD_LENGTH,
            maxLength: MAX_PASSWORD_LENGTH,
          },
        }}
        helperText={PASSWORD_LENGTH_HINT}
        fullWidth
      />

      <Box>
        <Button
          type="submit"
          variant="contained"
          size="large"
          disabled={isPending}
        >
          {isPending ? 'Creating account…' : 'Sign up'}
        </Button>
      </Box>
    </Stack>
  );
}

export default SignupForm;
