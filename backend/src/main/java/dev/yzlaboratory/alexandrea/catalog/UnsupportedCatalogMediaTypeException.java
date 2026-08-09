package dev.yzlaboratory.alexandrea.catalog;

/**
 * Thrown when {@code media_type} has no provider wired up yet. Movies is the
 * only real behaviour this slice (#37) builds; TV/Books/Games are #39's job.
 */
public class UnsupportedCatalogMediaTypeException extends RuntimeException {

    public UnsupportedCatalogMediaTypeException(String mediaType) {
        super("No catalog provider is wired up for media type: " + mediaType);
    }
}
