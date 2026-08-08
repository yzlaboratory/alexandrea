package dev.yzlaboratory.alexandrea.auth.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record MediaTypeRequest(
    @NotBlank @Pattern(regexp = "movies|tv|books|games") String mediaType
) {}
