import { type ReactNode } from 'react';
import { Container, Typography } from '@mui/material';

function AccountPage(): ReactNode {
  return (
    <Container maxWidth="sm" sx={{ py: 8 }}>
      <Typography variant="h4" component="h1" gutterBottom>
        Account settings
      </Typography>
      <Typography color="text.secondary">
        Changing your password or email arrives in a later slice.
      </Typography>
    </Container>
  );
}

export default AccountPage;
