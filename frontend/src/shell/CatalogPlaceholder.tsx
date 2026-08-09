import { type ReactNode } from 'react';
import { Typography } from '@mui/material';

// Renders both for a surface that hasn't been built yet (Watchlist,
// Library) and, since #39, for a Catalog route whose :mediaType isn't
// one of the four known values — "later slice" would be wrong for the
// latter, so the copy stays neutral enough to be true for both.
function CatalogPlaceholder(): ReactNode {
  return (
    <Typography color="text.secondary">Nothing to show here yet.</Typography>
  );
}

export default CatalogPlaceholder;
