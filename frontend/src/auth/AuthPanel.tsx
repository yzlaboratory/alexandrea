import { type ReactNode } from 'react';
import { Stack, Typography } from '@mui/material';

interface AuthPanelProps {
  readonly title: string;
  readonly children: ReactNode;
}

function AuthPanel({ title, children }: AuthPanelProps): ReactNode {
  return (
    <Stack spacing={3} sx={{ alignItems: 'flex-start' }}>
      <Typography variant="h4" component="h1">
        {title}
      </Typography>
      {children}
    </Stack>
  );
}

export default AuthPanel;
