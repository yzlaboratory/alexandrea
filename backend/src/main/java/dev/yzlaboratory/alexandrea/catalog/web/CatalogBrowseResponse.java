package dev.yzlaboratory.alexandrea.catalog.web;

import dev.yzlaboratory.alexandrea.catalog.CatalogFilterOption;
import dev.yzlaboratory.alexandrea.catalog.CatalogItem;
import dev.yzlaboratory.alexandrea.catalog.CatalogPageResult;
import dev.yzlaboratory.alexandrea.catalog.CatalogSearchCapabilities;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code GET /api/catalog/{media_type}}'s body: one page, which filter
 * kinds are currently available for this {@code media_type} (ADR 0018's
 * capability table), keyed by filter field — a media type can report more
 * than one (e.g. Movies: {@code genre} and {@code originalLanguage}) — and
 * which of those (plus sort) {@code disabledFilters}/{@code sortDisabled}
 * lock right now because a search is active. {@code FilterControls}/{@code
 * SortControl} render from these three fields rather than hardcoding a
 * per-media-type table of their own.
 */
public record CatalogBrowseResponse(
    List<CatalogItem> items, int page, boolean hasMore, Map<String, List<CatalogFilterOption>> availableFilters,
    Set<String> disabledFilters, boolean sortDisabled
) {

    public static CatalogBrowseResponse from(
        CatalogPageResult pageResult, Map<String, List<CatalogFilterOption>> availableFilters, CatalogSearchCapabilities searchCapabilities
    ) {
        return new CatalogBrowseResponse(
            pageResult.items(), pageResult.page(), pageResult.hasMore(), availableFilters,
            searchCapabilities.disabledFilters(), searchCapabilities.sortDisabled()
        );
    }
}
