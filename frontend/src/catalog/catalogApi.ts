// GET is CSRF-exempt (Spring only guards state-changing methods), so —
// unlike authApi.ts's postJson helper — no X-XSRF-TOKEN header is needed
// here, mirroring authApi's own fetchSession.

export interface CatalogEntry {
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
  entries: CatalogEntry[];
  page: number;
  hasMore: boolean;
}

export type FetchCatalogPageOutcome =
  | { status: 'ok'; result: CatalogPageResult }
  | { status: 'error' };

export async function fetchCatalogPage(
  mediaType: string,
  page: number,
): Promise<FetchCatalogPageOutcome> {
  // fetch() itself rejects on a network-level failure (offline, DNS,
  // connection reset) rather than resolving with a non-ok Response. Without
  // this catch, that rejection propagates out of CatalogGrid's loadNextPage
  // past the point where it resets loadingRef/sets status to 'error',
  // leaving the grid stuck in a permanent loading spinner with no visible
  // way to retry.
  const response = await fetch(
    `/api/catalog/${mediaType}?page=${String(page)}`,
    { credentials: 'same-origin' },
  ).catch(() => null);
  if (!response?.ok) return { status: 'error' };
  const result = (await response
    .json()
    .catch(() => null)) as CatalogPageResult | null;
  if (result === null) return { status: 'error' };
  return { status: 'ok', result };
}
