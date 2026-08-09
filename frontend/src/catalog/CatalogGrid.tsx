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
import { fetchCatalogPage, type CatalogItem } from './catalogApi';
import CatalogTile from './CatalogTile';

interface CatalogGridProps {
  mediaType: string;
  // Undefined/empty means "no active search" — the popular feed. A present,
  // non-empty value replaces it with title-search results (issue #41);
  // never combined with sort/filter, which stay out of scope here.
  search?: string;
  // Only used to render the "clear search" affordance on a no-results
  // search page — CatalogGrid doesn't own the search box itself, so
  // clearing it is delegated back to whichever component does (CatalogPage).
  onClearSearch?: () => void;
}

type LoadState = 'idle' | 'loading' | 'error';

// Page/hasMore/in-flight state lives in refs, not useState: loadNextPage
// only needs to change identity when mediaType or search changes (so the
// IntersectionObserver effect below isn't torn down and rebuilt on every
// single page load), while still always reading the latest page/hasMore.
//
// Switching media type, or committing a new search, is handled by
// CatalogPage rendering this component with a `key` covering both — not by
// an internal reset effect: React unmounts and remounts a fresh instance,
// which resets every ref and state variable here for free and avoids a
// same-render cascade from calling setState synchronously inside an effect.
function CatalogGrid({
  mediaType,
  search,
  onClearSearch,
}: CatalogGridProps): ReactNode {
  const [items, setItems] = useState<CatalogItem[]>([]);
  // Starts 'loading', not 'idle': the mount effect below always calls
  // loadNextPage unconditionally, so an 'idle' initial value would let the
  // "No items found." message flash on the very first paint (React
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
    // Two distinct call shapes, not one call with search always passed
    // (even as undefined/''), so a plain browse request looks identical on
    // the wire to what it was before this issue — no incidental "search="
    // param for the common no-search case.
    const outcome = search
      ? await fetchCatalogPage(mediaType, nextPageRef.current, search)
      : await fetchCatalogPage(mediaType, nextPageRef.current);
    loadingRef.current = false;
    if (outcome.status === 'error') {
      setStatus('error');
      return;
    }
    setItems((previous) => {
      // TMDB's "popular" ranking can shift between successive page fetches,
      // so the same title can legitimately reappear on a later page. Without
      // this dedup, appending it again produces a duplicate React key
      // (`${provider}|${externalId}`) across the combined list.
      const seen = new Set(
        previous.map((item) => `${item.provider}|${item.externalId}`),
      );
      const fresh = outcome.result.items.filter(
        (item) => !seen.has(`${item.provider}|${item.externalId}`),
      );
      return [...previous, ...fresh];
    });
    nextPageRef.current = outcome.result.page + 1;
    hasMoreRef.current = outcome.result.hasMore;
    setStatus('idle');
  }, [mediaType, search]);

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

  const showEmptyMessage = status === 'idle' && items.length === 0;

  return (
    <Stack spacing={2}>
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fill, minmax(160px, 1fr))',
          gap: 2,
        }}
      >
        {items.map((item) => (
          <CatalogTile
            key={`${item.provider}|${item.externalId}`}
            item={item}
          />
        ))}
      </Box>
      {status === 'loading' && (
        <Stack sx={{ alignItems: 'center', py: 2 }}>
          <CircularProgress size={24} aria-label="Loading more" />
        </Stack>
      )}
      {status === 'error' && (
        // The backend already serves a stale cached page as a plain 200 on
        // a transient upstream failure (ADR 0015), so an 'error' status
        // here only ever means a genuine cold miss — one generic message
        // covers it, with no separate "may be stale" case to distinguish.
        <Alert
          severity="error"
          action={
            <Button color="inherit" size="small" onClick={retry}>
              Retry
            </Button>
          }
        >
          The catalog is temporarily unavailable. Please try again.
        </Alert>
      )}
      {showEmptyMessage && search && (
        <Stack spacing={1} sx={{ alignItems: 'flex-start' }}>
          <Typography color="text.secondary">
            No results for &ldquo;{search}&rdquo;.
          </Typography>
          {onClearSearch && (
            <Button size="small" onClick={onClearSearch}>
              Clear search
            </Button>
          )}
        </Stack>
      )}
      {showEmptyMessage && !search && (
        <Typography color="text.secondary">No items found.</Typography>
      )}
      <div ref={sentinelRef} data-testid="catalog-grid-sentinel" />
    </Stack>
  );
}

export default CatalogGrid;
