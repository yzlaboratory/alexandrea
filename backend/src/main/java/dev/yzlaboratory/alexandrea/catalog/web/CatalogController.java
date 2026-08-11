package dev.yzlaboratory.alexandrea.catalog.web;

import dev.yzlaboratory.alexandrea.auth.AuthenticatedUser;
import dev.yzlaboratory.alexandrea.catalog.CatalogPageResult;
import dev.yzlaboratory.alexandrea.catalog.CatalogService;
import jakarta.validation.constraints.Min;
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
    public CatalogPageResult browse(
        @PathVariable("media_type") String mediaType,
        @RequestParam(required = false) String search,
        @RequestParam(required = false) String sort,
        @RequestParam(required = false) String direction,
        @RequestParam(name = "page", defaultValue = "1") @Min(1) int page,
        @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        return catalogService.browse(mediaType, search, sort, direction, principal.userId(), page);
    }

    /** The signed-in user's persisted Catalog sort for this media type (ADR 0025), or both fields null if never set. */
    @GetMapping("/{media_type}/sort-preference")
    public SortPreferenceResponse sortPreference(
        @PathVariable("media_type") String mediaType, @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        return catalogService.sortPreference(principal.userId(), mediaType)
            .map(preference -> new SortPreferenceResponse(preference.sortKey(), preference.sortDirection()))
            .orElseGet(() -> new SortPreferenceResponse(null, null));
    }
}
