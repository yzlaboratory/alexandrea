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
  // Which of sort/direction/filters a provider's search endpoint can
  // actually honor alongside `search` varies by media type (ADR 0018's
  // "Behavior under active text search" table) — CatalogService decides
  // that server-side, dropping whatever it can't use. This component stays
  // a plain pass-through of whatever CatalogPage currently has selected,
  // sending search combined with sort/filters whenever both are given,
  // rather than duplicating that per-provider rule itself.
  sort?: string;
  direction?: string;
  // Keyed by filter field (see catalogApi's CATALOG_FILTER_FIELDS). A
  // field's value applies that filter; a field mapped to null is
  // CatalogPage's definite "no value selected" (the user cleared a
  // previously-chosen filter, or never had one) and must reach the backend
  // as an explicit clear rather than being silently dropped; a field absent
  // from this object means this caller doesn't manage that field at all, so
  // nothing is sent for it and CatalogService falls back to whatever's
  // already persisted. Collapsing "mapped to null" into "absent" would make
  // an explicit clear indistinguishable from "not mentioned", and
  // CatalogService would then resurrect a deselected filter from the
  // persisted row instead of clearing it. The whole prop being undefined
  // means this caller manages no filters at all.
  filters?: Record<string, string | null>;
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
  // Same seam as onAvailableFiltersChange, for which sort/filter fields ADR
  // 0018 locks right now because this fetch reflects an active search — so
  // CatalogPage can pass it into SortControl/FilterControls.
  onSearchCapabilitiesChange?: (
    capabilities: CatalogSearchCapabilities,
  ) => void;
}

export interface CatalogSearchCapabilities {
  disabledFilters: string[];
  sortDisabled: boolean;
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
  filters,
  onClearSearch,
  onAvailableFiltersChange,
  onSearchCapabilitiesChange,
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

  // search combines with sort and/or filters whenever both are given —
  // CatalogService decides server-side which of them its provider's search
  // endpoint can actually honor (ADR 0018) and drops the rest, so this
  // component sends everything it has rather than guessing. fetchCatalogPage
  // itself treats an absent/falsy search or sort, and an undefined filters,
  // no differently from those args being omitted entirely, so there is no
  // wire-visible difference to preserve by branching on which props this
  // caller happens to be using.
  const fetchPage = useCallback(
    (page: number) =>
      fetchCatalogPage(mediaType, page, search, sort, direction, filters),
    [mediaType, search, sort, direction, filters],
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
    onSearchCapabilitiesChange?.({
      disabledFilters: outcome.result.disabledFilters ?? [],
      sortDisabled: outcome.result.sortDisabled ?? false,
    });
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
  }, [fetchPage, onAvailableFiltersChange, onSearchCapabilitiesChange]);

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
        <Stack
          role="status"
          aria-live="polite"
          sx={{ alignItems: 'center', py: 2 }}
        >
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
        <Stack
          role="status"
          aria-live="polite"
          spacing={1}
          sx={{ alignItems: 'flex-start' }}
        >
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
        <Typography role="status" aria-live="polite" color="text.secondary">
          No items found.
        </Typography>
      )}
      <div ref={sentinelRef} data-testid="catalog-grid-sentinel" />
    </Stack>
  );
}

export default CatalogGrid;
