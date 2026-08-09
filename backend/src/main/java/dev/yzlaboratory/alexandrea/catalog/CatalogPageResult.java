package dev.yzlaboratory.alexandrea.catalog;

import java.util.List;

/**
 * One page of a feed or query result — the shape both {@link CatalogCache}'s
 * page layer and {@code CatalogController}'s response carry. {@code hasMore}
 * tells the caller whether a further page exists, so the frontend's
 * infinite scroll knows when to stop without guessing from a short page.
 */
public record CatalogPageResult(List<CatalogEntry> entries, int page, boolean hasMore) {}
