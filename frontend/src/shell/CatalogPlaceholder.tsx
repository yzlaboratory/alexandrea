import { type ReactNode } from 'react';
import { Typography } from '@mui/material';

// #3/#4 replace this with real catalog browsing at the same /:mediaType/:surface routes.
function CatalogPlaceholder(): ReactNode {
  return (
    <Typography color="text.secondary">
      Browsing arrives in a later slice.
    </Typography>
  );
}

export default CatalogPlaceholder;
