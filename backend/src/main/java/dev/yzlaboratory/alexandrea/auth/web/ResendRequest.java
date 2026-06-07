package dev.yzlaboratory.alexandrea.auth.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Resend-verification payload (#19): just the address to re-mail the link to. */
public record ResendRequest(@NotBlank @Email String email) {}
