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

export interface CatalogPageResult {
  items: CatalogItem[];
  page: number;
  hasMore: boolean;
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
): Promise<FetchCatalogPageOutcome> {
  // fetch() itself rejects on a network-level failure (offline, DNS,
  // connection reset) rather than resolving with a non-ok Response. Without
  // this catch, that rejection propagates out of CatalogGrid's loadNextPage
  // past the point where it resets loadingRef/sets status to 'error',
  // leaving the grid stuck in a permanent loading spinner with no visible
  // way to retry.
  const searchParam = search ? `&search=${encodeURIComponent(search)}` : '';
  // Backend ignores sort while a search is active (CatalogService), so
  // CatalogGrid never sends both — but this helper stays permissive about
  // it, the same "just build the query string from whatever's given" shape
  // the search param already has.
  const sortParam = sort ? `&sort=${sort}&direction=${direction ?? ''}` : '';
  const response = await fetch(
    `/api/catalog/${mediaType}?page=${String(page)}${searchParam}${sortParam}`,
    { credentials: 'same-origin' },
  ).catch(() => null);
  if (!response?.ok) return { status: 'error' };
  const result = (await response
    .json()
    .catch(() => null)) as CatalogPageResult | null;
  if (result === null) return { status: 'error' };
  return { status: 'ok', result };
}

export interface SortPreference {
  sortKey: string | null;
  sortDirection: string | null;
}

const NO_SORT_PREFERENCE: SortPreference = {
  sortKey: null,
  sortDirection: null,
};

// A failure here (network error, non-2xx, bad JSON) degrades to "nothing
// stored" rather than a distinct error state — CatalogPage's own default
// (popularity/desc) is already the correct fallback for that case, so there
// is no separate error UI worth building for this one small preference read.
export async function fetchSortPreference(
  mediaType: string,
): Promise<SortPreference> {
  const response = await fetch(`/api/catalog/${mediaType}/sort-preference`, {
    credentials: 'same-origin',
  }).catch(() => null);
  if (!response?.ok) return NO_SORT_PREFERENCE;
  const result = (await response
    .json()
    .catch(() => null)) as SortPreference | null;
  return result ?? NO_SORT_PREFERENCE;
}
