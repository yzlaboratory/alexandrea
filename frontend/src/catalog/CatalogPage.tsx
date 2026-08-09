import { type ReactNode } from 'react';
import { Stack, Typography } from '@mui/material';
import CatalogGrid from './CatalogGrid';

interface CatalogPageProps {
  mediaType: string;
}

const MEDIA_TYPE_LABELS: Record<string, string> = {
  movies: 'Movies',
  tv: 'TV',
  books: 'Books',
  games: 'Games',
};

function CatalogPage({ mediaType }: CatalogPageProps): ReactNode {
  const label = MEDIA_TYPE_LABELS[mediaType] ?? mediaType;
  return (
    <Stack spacing={3}>
      <Typography variant="h4" component="h1">
        {label} catalog
      </Typography>
      {/* key={mediaType} forces a remount on media-type change, which is
          how CatalogGrid resets its feed rather than an internal effect. */}
      <CatalogGrid key={mediaType} mediaType={mediaType} />
    </Stack>
  );
}

export default CatalogPage;
