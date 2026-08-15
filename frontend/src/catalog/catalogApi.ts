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

// One selectable filter value — e.g. one TMDB genre or ISO 639-1 language
// code. `value` is the provider's own native id, or (for Books) OpenLibrary's
// MARC3 code — whatever round-trips back to the catalog request unchanged;
// `label` is what FilterControls renders.
export interface CatalogFilterOption {
  value: string;
  label: string;
}

// The filter fields, in the fixed order FilterControls renders them — the
// single source both CatalogPage's persisted-preference state and
// FilterControls' rendering order draw from, so the two can never silently
// drift out of sync on which fields exist.
export const CATALOG_FILTER_FIELDS = [
  'genre',
  'originalLanguage',
  'availableInLanguage',
  'runtime',
  'pageCount',
] as const;

// The subset of CATALOG_FILTER_FIELDS with no enumerable option list — a
// range has infinitely many valid values, so CatalogService reports these
// as present-but-empty (`[]`) in availableFilters rather than omitted. That
// empty-but-available signal is what lets FilterControls tell "this range
// is offered" apart from "not offered for this media type" (which omits
// the key entirely), and choose the two-number-field range UI over the
// select-from-options UI the other three fields use.
export const CATALOG_RANGE_FILTER_FIELDS = ['runtime', 'pageCount'] as const;

export interface CatalogPageResult {
  items: CatalogItem[];
  page: number;
  hasMore: boolean;
  // Which filter kinds are currently available for this media_type (ADR
  // 0018's capability table) — a media type can report more than one (e.g.
  // Movies: genre and originalLanguage). Optional because it's driven
  // entirely by the backend response; FilterControls renders only whatever
  // keys are present rather than hardcoding a per-media-type table of its
  // own.
  availableFilters?: Record<string, CatalogFilterOption[]>;
  // Which of availableFilters' fields, plus sort, ADR 0018 locks right now
  // because this response reflects an active text search — see ADR 0018 for
  // which fields that is per media type. Distinct from availableFilters:
  // that's "offered for this media type at all", this is "usable this
  // instant". Both undefined (same as an empty array / false) whenever no
  // search is active.
  disabledFilters?: string[];
  sortDisabled?: boolean;
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
  // Keyed by filter field (see CATALOG_FILTER_FIELDS). A field mapped to a
  // string applies that value. A field mapped to null is the user's
  // explicit "no value selected" (a cleared/deselected control) and is sent
  // as a present-but-empty `<field>=` param, so CatalogService can tell
  // "clear it" apart from the field being absent from this map entirely,
  // which omits the param and leaves whatever's already persisted alone.
  filters?: Record<string, string | null>,
): Promise<FetchCatalogPageOutcome> {
  // fetch() itself rejects on a network-level failure (offline, DNS,
  // connection reset) rather than resolving with a non-ok Response. Without
  // this catch, that rejection propagates out of CatalogGrid's loadNextPage
  // past the point where it resets loadingRef/sets status to 'error',
  // leaving the grid stuck in a permanent loading spinner with no visible
  // way to retry.
  const searchParam = search ? `&search=${encodeURIComponent(search)}` : '';
  // search, sort, and filters are each built independently and just
  // concatenated below — CatalogService (not this helper) decides per media
  // type which of sort/filters its provider's search endpoint can honor
  // alongside an active search (ADR 0018) and drops the rest.
  const sortParam = sort ? `&sort=${sort}&direction=${direction ?? ''}` : '';
  const filtersParam = filters
    ? Object.entries(filters)
        .map(([field, value]) =>
          value === null
            ? `&${field}=`
            : `&${field}=${encodeURIComponent(value)}`,
        )
        .join('')
    : '';
  const response = await fetch(
    `/api/catalog/${mediaType}?page=${String(page)}${searchParam}${sortParam}${filtersParam}`,
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
  // Keyed by filter field, carrying only the fields currently set — a field
  // absent from this object means no value is selected for it. Empty rather
  // than every-field-null so callers never null-check a field before
  // looking it up.
  filters: Record<string, string>;
}

const NO_PREFERENCE: CatalogPreference = {
  sortKey: null,
  sortDirection: null,
  filters: {},
};

// A failure here (network error, non-2xx, bad JSON) degrades to "nothing
// stored" rather than a distinct error state — CatalogPage's own defaults
// (popularity/desc, no filters) are already the correct fallback for that
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
