package dev.yzlaboratory.alexandrea.auth.web;

import jakarta.validation.constraints.NotBlank;

/** Verify payload: the raw token lifted from the link the user opened. */
public record VerifyRequest(@NotBlank String token) {}
