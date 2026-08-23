package dev.yzlaboratory.alexandrea.catalog.web;

import dev.yzlaboratory.alexandrea.auth.AuthenticatedUser;
import dev.yzlaboratory.alexandrea.catalog.CatalogFilterKeys;
import dev.yzlaboratory.alexandrea.catalog.CatalogService;
import jakarta.validation.constraints.Min;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/catalog/{media_type}} — one page of one media type's
 * catalog, identical in shape for movies, tv, books, and games (see
 * {@link CatalogService} for the per-media-type routing). This endpoint
 * requires authentication via the app-wide default in {@code SecurityConfig}
 * — no route-specific rule needed here.
 */
@RestController
@RequestMapping("/api/catalog")
@Validated
public class CatalogController {

    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/{media_type}")
    public CatalogBrowseResponse browse(
        @PathVariable("media_type") String mediaType,
        @RequestParam(required = false) String search,
        @RequestParam(required = false) String sort,
        @RequestParam(required = false) String direction,
        @RequestParam(required = false) String genre,
        @RequestParam(required = false) String originalLanguage,
        @RequestParam(required = false) String availableInLanguage,
        @RequestParam(required = false) String runtime,
        @RequestParam(required = false) String pageCount,
        @RequestParam(name = "page", defaultValue = "1") @Min(1) int page,
        @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        var filters = requestedFilters(genre, originalLanguage, availableInLanguage, runtime, pageCount);
        var pageResult = catalogService.browse(mediaType, search, sort, direction, filters, principal.userId(), page);
        var searchCapabilities = catalogService.searchCapabilities(mediaType, search);
        return CatalogBrowseResponse.from(pageResult, catalogService.availableFilters(mediaType), searchCapabilities);
    }

    /** The signed-in user's persisted Catalog sort and filter selections for this media type (ADR 0025), or null/empty fields if never set. */
    @GetMapping("/{media_type}/preference")
    public CatalogPreferenceResponse preference(
        @PathVariable("media_type") String mediaType, @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        var preference = catalogService.preference(principal.userId(), mediaType);
        return new CatalogPreferenceResponse(preference.sortKey(), preference.sortDirection(), preference.filters());
    }

    // Only fields the request actually mentioned end up in the map — an
    // absent query param stays absent here too, which is what lets
    // CatalogService tell "not mentioned" (fall back to whatever's
    // persisted) apart from "mentioned but empty" (an explicit clear).
    private static Map<String, String> requestedFilters(
        String genre, String originalLanguage, String availableInLanguage, String runtime, String pageCount
    ) {
        var filters = new LinkedHashMap<String, String>();
        if (genre != null) {
            filters.put(CatalogFilterKeys.GENRE, genre);
        }
        if (originalLanguage != null) {
            filters.put(CatalogFilterKeys.ORIGINAL_LANGUAGE, originalLanguage);
        }
        if (availableInLanguage != null) {
            filters.put(CatalogFilterKeys.AVAILABLE_IN_LANGUAGE, availableInLanguage);
        }
        if (runtime != null) {
            filters.put(CatalogFilterKeys.RUNTIME, runtime);
        }
        if (pageCount != null) {
            filters.put(CatalogFilterKeys.PAGE_COUNT, pageCount);
        }
        return filters;
    }
}
