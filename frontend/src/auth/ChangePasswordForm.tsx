import { type ReactNode, useActionState } from 'react';
import {
  Alert,
  Box,
  Button,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { type ChangePasswordOutcome } from './authApi';
import {
  MAX_PASSWORD_LENGTH,
  MIN_PASSWORD_LENGTH,
  PASSWORD_LENGTH_ERROR,
  PASSWORD_LENGTH_HINT,
  textField,
} from './forms';

interface ChangePasswordFormProps {
  readonly onChanged: () => void;
  /** The change-password call, injected so the form is testable without the network. */
  readonly submit: (
    currentPassword: string,
    newPassword: string,
  ) => Promise<ChangePasswordOutcome>;
}

type FormError = string | null;

function ChangePasswordForm({
  onChanged,
  submit,
}: ChangePasswordFormProps): ReactNode {
  const [error, formAction, isPending] = useActionState<FormError, FormData>(
    async (_previous, formData) => {
      const currentPassword = textField(formData, 'currentPassword');
      const newPassword = textField(formData, 'newPassword');

      const outcome = await submit(currentPassword, newPassword);
      switch (outcome.status) {
        case 'changed':
          onChanged();
          return null;
        case 'incorrect-current-password':
          return 'Current password is incorrect.';
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
      <Typography variant="h6" component="h2">
        Change password
      </Typography>

      {error !== null && <Alert severity="error">{error}</Alert>}

      <TextField
        name="currentPassword"
        type="password"
        label="Current password"
        autoComplete="current-password"
        required
        fullWidth
      />
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
        <Button type="submit" variant="contained" disabled={isPending}>
          {isPending ? 'Updating…' : 'Update password'}
        </Button>
      </Box>
    </Stack>
  );
}

export default ChangePasswordForm;
