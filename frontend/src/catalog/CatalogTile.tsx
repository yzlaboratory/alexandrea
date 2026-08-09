import { type ReactNode } from 'react';
import { Box, Card, CardContent, CardMedia, Typography } from '@mui/material';
import type { CatalogItem } from './catalogApi';

// One decimal place matches TMDB's own display convention (e.g. "7.8/10").
export function formatExternalRating(item: CatalogItem): string {
  if (item.externalRating === null) return 'Not yet rated';
  return `${item.externalRating.toFixed(1)}/${item.externalRatingScale.toString()} ${item.provider}`;
}

export function formatReleaseDate(releaseDate: string | null): string {
  return releaseDate ?? 'Release date unknown';
}

interface CatalogTileProps {
  item: CatalogItem;
}

function CatalogTile({ item }: CatalogTileProps): ReactNode {
  return (
    <Card>
      {item.coverUrl !== null ? (
        <CardMedia
          component="img"
          image={item.coverUrl}
          alt={`${item.title} cover`}
          sx={{ aspectRatio: '2 / 3', objectFit: 'cover' }}
        />
      ) : (
        <Box
          role="img"
          aria-label={`${item.title} has no cover art`}
          sx={{ aspectRatio: '2 / 3', bgcolor: 'action.hover' }}
        />
      )}
      <CardContent>
        <Typography
          variant="subtitle1"
          component="h3"
          title={item.title}
          noWrap
        >
          {item.title}
        </Typography>
        <Typography variant="body2" color="text.secondary">
          {formatReleaseDate(item.releaseDate)}
        </Typography>
        <Typography variant="body2">{formatExternalRating(item)}</Typography>
      </CardContent>
    </Card>
  );
}

export default CatalogTile;
