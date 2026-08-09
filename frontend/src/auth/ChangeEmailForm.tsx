import { type ReactNode, useActionState } from 'react';
import {
  Alert,
  Box,
  Button,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { type RequestEmailChangeOutcome } from './authApi';
import { textField } from './forms';

interface ChangeEmailFormProps {
  readonly onRequested: (newEmail: string) => void;
  /** The change-email request call, injected so the form is testable without the network. */
  readonly submit: (
    currentPassword: string,
    newEmail: string,
  ) => Promise<RequestEmailChangeOutcome>;
}

type FormError = string | null;

function ChangeEmailForm({
  onRequested,
  submit,
}: ChangeEmailFormProps): ReactNode {
  const [error, formAction, isPending] = useActionState<FormError, FormData>(
    async (_previous, formData) => {
      const currentPassword = textField(formData, 'currentPassword');
      const newEmail = textField(formData, 'newEmail');

      const outcome = await submit(currentPassword, newEmail);
      switch (outcome.status) {
        case 'requested':
          onRequested(newEmail);
          return null;
        case 'incorrect-current-password':
          return 'Current password is incorrect.';
        case 'error':
          return 'Something went wrong. Please try again.';
      }
    },
    null,
  );

  return (
    <Stack spacing={3} component="form" action={formAction}>
      <Typography variant="h6" component="h2">
        Change email
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
        name="newEmail"
        type="email"
        label="New email"
        autoComplete="email"
        required
        fullWidth
      />

      <Box>
        <Button type="submit" variant="contained" disabled={isPending}>
          {isPending ? 'Sending…' : 'Send confirmation link'}
        </Button>
      </Box>
    </Stack>
  );
}

export default ChangeEmailForm;
