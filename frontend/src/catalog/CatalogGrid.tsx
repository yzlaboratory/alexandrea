import {
  type ReactNode,
  useCallback,
  useEffect,
  useRef,
  useState,
} from 'react';
import {
  Alert,
  Box,
  Button,
  CircularProgress,
  Stack,
  Typography,
} from '@mui/material';
import { fetchCatalogPage, type CatalogEntry } from './catalogApi';
import CatalogTile from './CatalogTile';

interface CatalogGridProps {
  mediaType: string;
}

type LoadState = 'idle' | 'loading' | 'error';

// Page/hasMore/in-flight state lives in refs, not useState: loadNextPage
// only needs to change identity when mediaType changes (so the
// IntersectionObserver effect below isn't torn down and rebuilt on every
// single page load), while still always reading the latest page/hasMore.
//
// Switching media type is handled by CatalogPage rendering this component
// with `key={mediaType}`, not by an internal reset effect: React unmounts
// and remounts a fresh instance, which resets every ref and state variable
// here for free and avoids a same-render cascade from calling setState
// synchronously inside an effect.
function CatalogGrid({ mediaType }: CatalogGridProps): ReactNode {
  const [entries, setEntries] = useState<CatalogEntry[]>([]);
  // Starts 'loading', not 'idle': the mount effect below always calls
  // loadNextPage unconditionally, so an 'idle' initial value would let the
  // "No entries found." message flash on the very first paint (React
  // commits before this component's effects run), ahead of the fetch it's
  // about to kick off.
  const [status, setStatus] = useState<LoadState>('loading');
  const nextPageRef = useRef(1);
  const hasMoreRef = useRef(true);
  const loadingRef = useRef(false);
  const sentinelRef = useRef<HTMLDivElement | null>(null);

  const loadNextPage = useCallback(async (): Promise<void> => {
    if (loadingRef.current || !hasMoreRef.current) return;
    loadingRef.current = true;
    setStatus('loading');
    const outcome = await fetchCatalogPage(mediaType, nextPageRef.current);
    loadingRef.current = false;
    if (outcome.status === 'error') {
      setStatus('error');
      return;
    }
    setEntries((previous) => {
      // TMDB's "popular" ranking can shift between successive page fetches,
      // so the same title can legitimately reappear on a later page. Without
      // this dedup, appending it again produces a duplicate React key
      // (`${provider}|${externalId}`) across the combined list.
      const seen = new Set(
        previous.map((entry) => `${entry.provider}|${entry.externalId}`),
      );
      const fresh = outcome.result.entries.filter(
        (entry) => !seen.has(`${entry.provider}|${entry.externalId}`),
      );
      return [...previous, ...fresh];
    });
    nextPageRef.current = outcome.result.page + 1;
    hasMoreRef.current = outcome.result.hasMore;
    setStatus('idle');
  }, [mediaType]);

  // Loads this instance's first page. loadNextPage's identity is stable for
  // the lifetime of one mounted instance (mediaType is fixed per instance —
  // see the key={mediaType} note above), so this effect fires exactly once.
  useEffect(() => {
    void loadNextPage();
  }, [loadNextPage]);

  useEffect(() => {
    const sentinel = sentinelRef.current;
    if (!sentinel) return undefined;
    const observer = new IntersectionObserver((observerEntries) => {
      if (
        observerEntries.some((observerEntry) => observerEntry.isIntersecting)
      ) {
        void loadNextPage();
      }
    });
    observer.observe(sentinel);
    return () => {
      observer.disconnect();
    };
  }, [loadNextPage]);

  function retry(): void {
    setStatus('idle');
    void loadNextPage();
  }

  const showEmptyMessage = status === 'idle' && entries.length === 0;

  return (
    <Stack spacing={2}>
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fill, minmax(160px, 1fr))',
          gap: 2,
        }}
      >
        {entries.map((entry) => (
          <CatalogTile
            key={`${entry.provider}|${entry.externalId}`}
            entry={entry}
          />
        ))}
      </Box>
      {status === 'loading' && (
        <Stack sx={{ alignItems: 'center', py: 2 }}>
          <CircularProgress size={24} aria-label="Loading more" />
        </Stack>
      )}
      {status === 'error' && (
        <Alert
          severity="error"
          action={
            <Button color="inherit" size="small" onClick={retry}>
              Retry
            </Button>
          }
        >
          Couldn&apos;t load the catalog. Please try again.
        </Alert>
      )}
      {showEmptyMessage && (
        <Typography color="text.secondary">No entries found.</Typography>
      )}
      <div ref={sentinelRef} data-testid="catalog-grid-sentinel" />
    </Stack>
  );
}

export default CatalogGrid;
