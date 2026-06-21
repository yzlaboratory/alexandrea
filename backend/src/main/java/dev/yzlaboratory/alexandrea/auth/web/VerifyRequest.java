package dev.yzlaboratory.alexandrea.auth.web;

import jakarta.validation.constraints.NotBlank;

public record VerifyRequest(@NotBlank String token) {}
