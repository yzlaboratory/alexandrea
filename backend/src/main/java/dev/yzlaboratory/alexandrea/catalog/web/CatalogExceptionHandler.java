package dev.yzlaboratory.alexandrea.catalog.web;

import dev.yzlaboratory.alexandrea.catalog.CatalogUpstreamException;
import dev.yzlaboratory.alexandrea.catalog.UnsupportedCatalogMediaTypeException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = CatalogController.class)
class CatalogExceptionHandler {

    /** {@code @Min(1)} on {@code page} failing surfaces here, not as a bare 500. */
    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(UnsupportedCatalogMediaTypeException.class)
    ProblemDetail handleUnsupportedMediaType(UnsupportedCatalogMediaTypeException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(CatalogUpstreamException.class)
    ProblemDetail handleUpstreamFailure(CatalogUpstreamException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }
}
