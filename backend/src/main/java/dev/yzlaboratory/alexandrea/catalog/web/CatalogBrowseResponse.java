package dev.yzlaboratory.alexandrea.catalog.web;

import dev.yzlaboratory.alexandrea.catalog.CatalogFilterOption;
import dev.yzlaboratory.alexandrea.catalog.CatalogItem;
import dev.yzlaboratory.alexandrea.catalog.CatalogPageResult;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/catalog/{media_type}}'s body: one page plus which filters
 * are currently available for this {@code media_type} (ADR 0018's
 * capability table), keyed by filter name (today just {@code "genre"}) so
 * {@code FilterControls} renders only what a key is present for rather than
 * hardcoding a per-media-type table of its own.
 */
public record CatalogBrowseResponse(
    List<CatalogItem> items, int page, boolean hasMore, Map<String, List<CatalogFilterOption>> availableFilters
) {

    public static CatalogBrowseResponse from(CatalogPageResult pageResult, Map<String, List<CatalogFilterOption>> availableFilters) {
        return new CatalogBrowseResponse(pageResult.items(), pageResult.page(), pageResult.hasMore(), availableFilters);
    }
}
