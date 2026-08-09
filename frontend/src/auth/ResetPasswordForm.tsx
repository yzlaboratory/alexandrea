import { type ReactNode, useActionState } from 'react';
import {
  Alert,
  Box,
  Button,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { type ResetPasswordOutcome } from './authApi';
import {
  MAX_PASSWORD_LENGTH,
  MIN_PASSWORD_LENGTH,
  PASSWORD_LENGTH_ERROR,
  PASSWORD_LENGTH_HINT,
  textField,
} from './forms';

interface ResetPasswordFormProps {
  readonly token: string;
  readonly onReset: () => void;
  readonly onRejected: () => void;
  /** The reset-submit call, injected so the form is testable without the network. */
  readonly submit: (
    token: string,
    newPassword: string,
  ) => Promise<ResetPasswordOutcome>;
}

type FormError = string | null;

function ResetPasswordForm({
  token,
  onReset,
  onRejected,
  submit,
}: ResetPasswordFormProps): ReactNode {
  const [error, formAction, isPending] = useActionState<FormError, FormData>(
    async (_previous, formData) => {
      const newPassword = textField(formData, 'newPassword');

      try {
        const outcome = await submit(token, newPassword);
        switch (outcome.status) {
          case 'reset':
            onReset();
            return null;
          case 'rejected':
            onRejected();
            return null;
          case 'invalid-password':
            return PASSWORD_LENGTH_ERROR;
          case 'error':
            return 'Something went wrong. Please try again.';
        }
      } catch {
        return 'Something went wrong. Please try again.';
      }
    },
    null,
  );

  return (
    <Stack spacing={3} component="form" action={formAction}>
      <Typography variant="h4" component="h1">
        Choose a new password
      </Typography>

      {error !== null && <Alert severity="error">{error}</Alert>}

      <TextField
        name="newPassword"
        type="password"
        label="New password"
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
          {isPending ? 'Updating…' : 'Update password'}
        </Button>
      </Box>
    </Stack>
  );
}

export default ResetPasswordForm;
