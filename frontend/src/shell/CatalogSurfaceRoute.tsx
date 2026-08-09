import { type ReactNode } from 'react';
import { useParams } from 'react-router-dom';
import CatalogPage from '../catalog/CatalogPage';
import CatalogPlaceholder from './CatalogPlaceholder';

// Only Movies has a real catalog provider wired up yet (#39 adds the
// rest); every other media type still falls through to the generic
// placeholder. This route is matched on "/:mediaType/catalog" — a
// dynamic :mediaType segment, not a movies-only static path — so
// AppShell's useParams()-driven tab highlighting (which media-type tab
// and which surface tab is active) keeps working here exactly like it
// does on every other surface route. A static "/movies/catalog" route
// left both tab rows unable to tell which tab was active, since neither
// param existed on that route at all.
function CatalogSurfaceRoute(): ReactNode {
  const { mediaType } = useParams<{ mediaType: string }>();
  if (mediaType === 'movies') {
    return <CatalogPage mediaType="movies" />;
  }
  return <CatalogPlaceholder />;
}

export default CatalogSurfaceRoute;
