// GET is CSRF-exempt (Spring only guards state-changing methods), so —
// unlike authApi.ts's postJson helper — no X-XSRF-TOKEN header is needed
// here, mirroring authApi's own fetchSession.

// Named CatalogItem, not CatalogEntry: CONTEXT.md retires "entry" as an
// ambiguous synonym for this concept (it used to mean a Catalog Item, a
// Watchlist entry, and a Library entry interchangeably) and settles on
// "Catalog Item" as the domain term.
export interface CatalogItem {
  provider: string;
  externalId: string;
  mediaType: string;
  title: string;
  coverUrl: string | null;
  releaseDate: string | null;
  externalRating: number | null;
  externalRatingScale: number;
}

// One selectable filter value — e.g. one TMDB or IGDB genre. `value` is the
// provider's own native id (what round-trips back to the catalog request);
// `label` is what FilterControls renders.
export interface CatalogFilterOption {
  value: string;
  label: string;
}

export interface CatalogPageResult {
  items: CatalogItem[];
  page: number;
  hasMore: boolean;
  // Which filters are currently available for this media_type (ADR 0018's
  // capability table), keyed by filter name — today just "genre". Optional
  // because it's driven entirely by the backend response; FilterControls
  // renders only whatever keys are present rather than hardcoding a
  // per-media-type table of its own.
  availableFilters?: Record<string, CatalogFilterOption[]>;
}

export type FetchCatalogPageOutcome =
  | { status: 'ok'; result: CatalogPageResult }
  | { status: 'error' };

export async function fetchCatalogPage(
  mediaType: string,
  page: number,
  search?: string,
  sort?: string,
  direction?: string,
  genre?: string,
): Promise<FetchCatalogPageOutcome> {
  // fetch() itself rejects on a network-level failure (offline, DNS,
  // connection reset) rather than resolving with a non-ok Response. Without
  // this catch, that rejection propagates out of CatalogGrid's loadNextPage
  // past the point where it resets loadingRef/sets status to 'error',
  // leaving the grid stuck in a permanent loading spinner with no visible
  // way to retry.
  const searchParam = search ? `&search=${encodeURIComponent(search)}` : '';
  // Backend ignores sort and genre while a search is active (CatalogService),
  // so CatalogGrid never sends either alongside search — but this helper
  // stays permissive about it, the same "just build the query string from
  // whatever's given" shape the search param already has.
  const sortParam = sort ? `&sort=${sort}&direction=${direction ?? ''}` : '';
  const genreParam = genre ? `&genre=${encodeURIComponent(genre)}` : '';
  const response = await fetch(
    `/api/catalog/${mediaType}?page=${String(page)}${searchParam}${sortParam}${genreParam}`,
    { credentials: 'same-origin' },
  ).catch(() => null);
  if (!response?.ok) return { status: 'error' };
  const result = (await response
    .json()
    .catch(() => null)) as CatalogPageResult | null;
  if (result === null) return { status: 'error' };
  return { status: 'ok', result };
}

export interface CatalogPreference {
  sortKey: string | null;
  sortDirection: string | null;
  genre: string | null;
}

const NO_PREFERENCE: CatalogPreference = {
  sortKey: null,
  sortDirection: null,
  genre: null,
};

// A failure here (network error, non-2xx, bad JSON) degrades to "nothing
// stored" rather than a distinct error state — CatalogPage's own defaults
// (popularity/desc, no genre) are already the correct fallback for that
// case, so there is no separate error UI worth building for this one small
// preference read.
export async function fetchCatalogPreference(
  mediaType: string,
): Promise<CatalogPreference> {
  const response = await fetch(`/api/catalog/${mediaType}/preference`, {
    credentials: 'same-origin',
  }).catch(() => null);
  if (!response?.ok) return NO_PREFERENCE;
  const result = (await response
    .json()
    .catch(() => null)) as CatalogPreference | null;
  return result ?? NO_PREFERENCE;
}
