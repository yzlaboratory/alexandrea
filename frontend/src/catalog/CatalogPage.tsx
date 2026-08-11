import {
  type ChangeEvent,
  type KeyboardEvent,
  type ReactNode,
  useEffect,
  useState,
} from 'react';
import { CircularProgress, Stack, TextField, Typography } from '@mui/material';
import CatalogGrid from './CatalogGrid';
import SortControl from './SortControl';
import { fetchSortPreference } from './catalogApi';

interface CatalogPageProps {
  mediaType: string;
}

const MEDIA_TYPE_LABELS: Record<string, string> = {
  movies: 'Movies',
  tv: 'TV',
  books: 'Books',
  games: 'Games',
};

// Long enough that a fast typist doesn't fire a request per keystroke, short
// enough that the grid still feels responsive once they pause.
const SEARCH_DEBOUNCE_MS = 300;

// The sort a user who has never chosen one sees — matches the Examples table
// in #3's "Sorting catalog results" scenario.
const DEFAULT_SORT_KEY = 'popularity';
const DEFAULT_SORT_DIRECTION = 'desc';

interface SortState {
  sortKey: string;
  direction: string;
}

function CatalogPage({ mediaType }: CatalogPageProps): ReactNode {
  const label = MEDIA_TYPE_LABELS[mediaType] ?? mediaType;
  // searchInput is the uncontrolled-feeling text the user is typing;
  // activeSearch is what actually drives the grid, updated only after the
  // debounce settles (or immediately on Enter). Neither is ever written to
  // localStorage or persisted server-side — search is spec'd transient,
  // always empty on a fresh mount.
  const [searchInput, setSearchInput] = useState('');
  const [activeSearch, setActiveSearch] = useState('');
  // null means "still loading the persisted preference" — the grid and sort
  // control wait for it so the page never flashes the default sort and then
  // jumps to a different restored one.
  const [sortState, setSortState] = useState<SortState | null>(null);

  useEffect(() => {
    const trimmed = searchInput.trim();
    const timeoutId = window.setTimeout(() => {
      setActiveSearch(trimmed);
    }, SEARCH_DEBOUNCE_MS);
    return () => {
      window.clearTimeout(timeoutId);
    };
  }, [searchInput]);

  // Fetch-on-display: resolve this media type's persisted Catalog sort as
  // soon as the page mounts, the same idiom as SessionContext's own session
  // fetch. sortState's initial value is already null (the "loading" state),
  // so — unlike a component that survives a prop change — this effect needs
  // no explicit reset: CatalogSurfaceRoute remounts CatalogPage on every
  // media-type change via `key={mediaType}`, so a fresh instance (and a
  // fresh null) is what "the media type changed" already looks like here.
  // Guards against a stale response landing after unmount.
  useEffect(() => {
    let cancelled = false;
    void fetchSortPreference(mediaType).then((preference) => {
      if (cancelled) return;
      setSortState({
        sortKey: preference.sortKey ?? DEFAULT_SORT_KEY,
        direction: preference.sortDirection ?? DEFAULT_SORT_DIRECTION,
      });
    });
    return () => {
      cancelled = true;
    };
  }, [mediaType]);

  function handleSearchInputChange(event: ChangeEvent<HTMLInputElement>): void {
    setSearchInput(event.target.value);
  }

  function commitSearchImmediately(
    event: KeyboardEvent<HTMLInputElement>,
  ): void {
    if (event.key === 'Enter') {
      setActiveSearch(searchInput.trim());
    }
  }

  function clearSearch(): void {
    setSearchInput('');
    setActiveSearch('');
  }

  function handleSortChange(sortKey: string, direction: string): void {
    setSortState({ sortKey, direction });
  }

  return (
    <Stack spacing={3}>
      <Typography variant="h4" component="h1">
        {label} catalog
      </Typography>
      <Stack direction="row" spacing={2} sx={{ flexWrap: 'wrap' }}>
        <TextField
          label={`Search ${label}`}
          value={searchInput}
          onChange={handleSearchInputChange}
          onKeyDown={commitSearchImmediately}
          size="small"
          sx={{ maxWidth: 400 }}
        />
        {sortState && (
          <SortControl
            sortKey={sortState.sortKey}
            direction={sortState.direction}
            onChange={handleSortChange}
          />
        )}
      </Stack>
      {sortState ? (
        // key resets CatalogGrid's feed whenever the media type, the
        // committed search, or the sort changes, the same remount-over-
        // reset-effect idiom CatalogGrid itself documents.
        <CatalogGrid
          key={`${mediaType}:${activeSearch}:${sortState.sortKey}:${sortState.direction}`}
          mediaType={mediaType}
          search={activeSearch}
          sort={sortState.sortKey}
          direction={sortState.direction}
          onClearSearch={clearSearch}
        />
      ) : (
        <Stack sx={{ alignItems: 'center', py: 2 }}>
          <CircularProgress size={24} aria-label="Loading" />
        </Stack>
      )}
    </Stack>
  );
}

export default CatalogPage;
