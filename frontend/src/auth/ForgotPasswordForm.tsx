import { type ReactNode, useActionState } from 'react';
import {
  Alert,
  Box,
  Button,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { textField } from './forms';

interface ForgotPasswordFormProps {
  readonly onRequested: (email: string) => void;
  /** The reset-request call, injected so the form is testable without the network. */
  readonly submit: (email: string) => Promise<void>;
}

type FormError = string | null;

function ForgotPasswordForm({
  onRequested,
  submit,
}: ForgotPasswordFormProps): ReactNode {
  const [error, formAction, isPending] = useActionState<FormError, FormData>(
    async (_previous, formData) => {
      const email = textField(formData, 'email');
      try {
        await submit(email);
        onRequested(email);
        return null;
      } catch {
        return 'Something went wrong. Please try again.';
      }
    },
    null,
  );

  return (
    <Stack spacing={3} component="form" action={formAction}>
      <Typography variant="h4" component="h1">
        Reset your password
      </Typography>

      <Typography>
        Enter your account email and, if it has an account, we&apos;ll send a
        link to choose a new password.
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

      <Box>
        <Button
          type="submit"
          variant="contained"
          size="large"
          disabled={isPending}
        >
          {isPending ? 'Sending…' : 'Send reset link'}
        </Button>
      </Box>
    </Stack>
  );
}

export default ForgotPasswordForm;
