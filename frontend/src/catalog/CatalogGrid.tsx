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
import {
  fetchCatalogPage,
  type CatalogFilterOption,
  type CatalogItem,
} from './catalogApi';
import CatalogTile from './CatalogTile';

interface CatalogGridProps {
  mediaType: string;
  // Undefined/empty means "no active search" — the popular feed. A present,
  // non-empty value replaces it with title-search results.
  search?: string;
  // sort/direction/genre are ignored server-side whenever search is active
  // (CatalogService) — CatalogPage never passes any of them at once with a
  // search, but this component stays a plain pass-through either way rather
  // than encoding that rule itself.
  sort?: string;
  direction?: string;
  // Three states, not just present/absent: a string applies that genre;
  // null is CatalogPage's definite "no genre selected" (the user cleared a
  // previously-chosen filter, or never had one) and must reach the backend
  // as an explicit clear rather than being silently dropped; undefined
  // means this caller doesn't manage genre at all, so nothing is sent and
  // CatalogService falls back to whatever's already persisted. Collapsing
  // null into undefined here would make "explicitly cleared" indistinguishable
  // from "not mentioned", and CatalogService would then resurrect a
  // deselected filter from the persisted row instead of clearing it.
  genre?: string | null | undefined;
  // Only used to render the "clear search" affordance on a no-results
  // search page — CatalogGrid doesn't own the search box itself, so
  // clearing it is delegated back to whichever component does (CatalogPage).
  onClearSearch?: () => void;
  // Reports each fetch's capability payload back up so CatalogPage can pass
  // it into FilterControls — CatalogGrid is the only component that ever
  // sees a raw fetch response, so this is the one seam that payload has to
  // cross rather than CatalogPage re-fetching it separately.
  onAvailableFiltersChange?: (
    availableFilters: Record<string, CatalogFilterOption[]>,
  ) => void;
}

type LoadState = 'idle' | 'loading' | 'error';

// Page/hasMore/in-flight state lives in refs, not useState: loadNextPage
// only needs to change identity when mediaType, search, sort, or direction
// changes (so the IntersectionObserver effect below isn't torn down and
// rebuilt on every single page load), while still always reading the
// latest page/hasMore.
//
// Switching media type, committing a new search, or changing the sort is
// handled by CatalogPage rendering this component with a `key` covering
// all of them — not by an internal reset effect: React unmounts and
// remounts a fresh instance, which resets every ref and state variable
// here for free and avoids a same-render cascade from calling setState
// synchronously inside an effect.
function CatalogGrid({
  mediaType,
  search,
  sort,
  direction,
  genre,
  onClearSearch,
  onAvailableFiltersChange,
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

  // Four call shapes, not one call with every param always passed (even as
  // undefined), so each request looks on the wire exactly like what a
  // caller not using that feature would send — no incidental "search=",
  // "sort="/"direction=", or "genre=" params for the cases that don't use
  // them. search never combines with sort or genre (CatalogService ignores
  // both while a search is active); sort and genre do combine with each
  // other, so a page fetch threads through whichever of the two is active.
  const fetchPage = useCallback(
    (page: number) => {
      if (search) return fetchCatalogPage(mediaType, page, search);
      // genre !== undefined, not a truthy check: null is a definite "no
      // genre" the backend must apply/persist as a clear, distinct from
      // undefined ("this caller doesn't mention genre at all") — see the
      // genre prop's own doc comment above.
      if (sort && genre !== undefined) {
        return fetchCatalogPage(
          mediaType,
          page,
          undefined,
          sort,
          direction,
          genre,
        );
      }
      if (sort)
        return fetchCatalogPage(mediaType, page, undefined, sort, direction);
      if (genre !== undefined) {
        return fetchCatalogPage(
          mediaType,
          page,
          undefined,
          undefined,
          undefined,
          genre,
        );
      }
      return fetchCatalogPage(mediaType, page);
    },
    [mediaType, search, sort, direction, genre],
  );

  const loadNextPage = useCallback(async (): Promise<void> => {
    if (loadingRef.current || !hasMoreRef.current) return;
    loadingRef.current = true;
    setStatus('loading');
    const outcome = await fetchPage(nextPageRef.current);
    loadingRef.current = false;
    if (outcome.status === 'error') {
      setStatus('error');
      return;
    }
    onAvailableFiltersChange?.(outcome.result.availableFilters ?? {});
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
  }, [fetchPage, onAvailableFiltersChange]);

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
