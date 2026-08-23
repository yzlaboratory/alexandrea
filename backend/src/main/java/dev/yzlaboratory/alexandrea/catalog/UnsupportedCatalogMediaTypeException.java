package dev.yzlaboratory.alexandrea.catalog;

/** Thrown when {@code media_type} is not one of movies, tv, books, or games. */
public class UnsupportedCatalogMediaTypeException extends RuntimeException {

    public UnsupportedCatalogMediaTypeException(String mediaType) {
        super("No catalog provider is wired up for media type: " + mediaType);
    }
}
