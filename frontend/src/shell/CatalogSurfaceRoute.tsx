import { type ReactNode } from 'react';
import { useParams } from 'react-router-dom';
import CatalogPage from '../catalog/CatalogPage';
import CatalogPlaceholder from './CatalogPlaceholder';
import { isMediaType } from './AppShell';

// All four media types have a real catalog provider wired up now (#39
// completed TV/Books/Games alongside #37's Movies). This route is
// matched on "/:mediaType/catalog" — a dynamic :mediaType segment, not
// a movies-only static path — so AppShell's useParams()-driven tab
// highlighting (which media-type tab and which surface tab is active)
// keeps working here exactly like it does on every other surface route.
function CatalogSurfaceRoute(): ReactNode {
  const { mediaType } = useParams<{ mediaType: string }>();
  // A stale bookmark or typo could put an unrecognized segment in
  // :mediaType; that still falls through to the generic placeholder
  // rather than reaching CatalogGrid with a media type the backend
  // will 404 on.
  if (isMediaType(mediaType)) {
    // key={mediaType}: CatalogPage owns the transient search box (#41), and
    // switching tabs without remounting would otherwise leak that text (and
    // an in-flight search) from the old media type into the new one.
    return <CatalogPage key={mediaType} mediaType={mediaType} />;
  }
  return <CatalogPlaceholder />;
}

export default CatalogSurfaceRoute;
