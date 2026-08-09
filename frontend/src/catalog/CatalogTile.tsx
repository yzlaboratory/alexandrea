import { type ReactNode } from 'react';
import { Box, Card, CardContent, CardMedia, Typography } from '@mui/material';
import type { CatalogEntry } from './catalogApi';

// One decimal place matches TMDB's own display convention (e.g. "7.8/10").
export function formatExternalRating(entry: CatalogEntry): string {
  if (entry.externalRating === null) return 'Not yet rated';
  return `${entry.externalRating.toFixed(1)}/${entry.externalRatingScale.toString()} ${entry.provider}`;
}

export function formatReleaseDate(releaseDate: string | null): string {
  return releaseDate ?? 'Release date unknown';
}

interface CatalogTileProps {
  entry: CatalogEntry;
}

function CatalogTile({ entry }: CatalogTileProps): ReactNode {
  return (
    <Card>
      {entry.coverUrl !== null ? (
        <CardMedia
          component="img"
          image={entry.coverUrl}
          alt={`${entry.title} cover`}
          sx={{ aspectRatio: '2 / 3', objectFit: 'cover' }}
        />
      ) : (
        <Box
          role="img"
          aria-label={`${entry.title} has no cover art`}
          sx={{ aspectRatio: '2 / 3', bgcolor: 'action.hover' }}
        />
      )}
      <CardContent>
        <Typography
          variant="subtitle1"
          component="h3"
          title={entry.title}
          noWrap
        >
          {entry.title}
        </Typography>
        <Typography variant="body2" color="text.secondary">
          {formatReleaseDate(entry.releaseDate)}
        </Typography>
        <Typography variant="body2">{formatExternalRating(entry)}</Typography>
      </CardContent>
    </Card>
  );
}

export default CatalogTile;
