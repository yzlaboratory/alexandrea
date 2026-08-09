package dev.yzlaboratory.alexandrea.auth.web;

import jakarta.validation.constraints.NotBlank;

public record ConfirmEmailChangeRequest(@NotBlank String token) {}
