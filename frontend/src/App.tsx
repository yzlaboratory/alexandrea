import { type JSX } from 'react';
import { Container, Typography } from '@mui/material';

// Placeholder shell. Real surfaces are sliced from the feature ticket via
// /to-issues and implemented through /implement-issues. This component exists
// only to give the smoke test something to render and the dev server something
// to serve while tooling is being verified.
function App(): JSX.Element {
  return (
    <Container maxWidth="sm" sx={{ py: 8 }}>
      <Typography variant="h3" component="h1" gutterBottom>
        Entertainment Library
      </Typography>
      <Typography variant="body1">Scaffold is alive.</Typography>
    </Container>
  );
}

export default App;
