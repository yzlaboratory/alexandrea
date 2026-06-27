import { type ReactNode } from 'react';
import { Box, Button, Container, Link, Stack, Typography } from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';

// Reachable without a session, so it deliberately renders no catalog or user
// data — it must not leak any.
function LandingPage(): ReactNode {
  return (
    <Container maxWidth="sm" sx={{ py: 10 }}>
      <Stack spacing={4} sx={{ alignItems: 'flex-start' }}>
        <Box>
          <Typography variant="h2" component="h1" gutterBottom>
            Alexandrea
          </Typography>
          <Typography variant="h6" component="p" color="text.secondary">
            Your personal tracker for the films, shows, books, and games you
            plan to enjoy — and a rated library of the ones you have. Keep a
            watchlist, rate what you finish, and share a view with a friend.
          </Typography>
        </Box>
        <Stack direction="row" spacing={2} sx={{ alignItems: 'center' }}>
          <Button
            component={RouterLink}
            to="/signup"
            variant="contained"
            size="large"
          >
            Sign up
          </Button>
          <Link component={RouterLink} to="/login">
            Log in
          </Link>
        </Stack>
      </Stack>
    </Container>
  );
}

export default LandingPage;
