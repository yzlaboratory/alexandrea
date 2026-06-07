package dev.yzlaboratory.alexandrea.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The password rules are length-only (#19): 12–128 code points, no composition,
 * every printable character allowed. These exercise the boundaries and the
 * "all characters allowed" promise that distinguishes this policy from a
 * composition-rule one.
 */
class PasswordPolicyTest {

    @Test
    void acceptsExactlyTheMinimumLength() {
        assertThat(PasswordPolicy.isAcceptable("a".repeat(12))).isTrue();
    }

    @Test
    void acceptsExactlyTheMaximumLength() {
        assertThat(PasswordPolicy.isAcceptable("a".repeat(128))).isTrue();
    }

    @Test
    void rejectsOneCharBelowMinimum() {
        assertThat(PasswordPolicy.isAcceptable("a".repeat(11))).isFalse();
    }

    @Test
    void rejectsOneCharAboveMaximum() {
        assertThat(PasswordPolicy.isAcceptable("a".repeat(129))).isFalse();
    }

    @Test
    void rejectsNull() {
        assertThat(PasswordPolicy.isAcceptable(null)).isFalse();
    }

    @Test
    void allowsSpacesAndPunctuationWithNoCompositionRule() {
        assertThat(PasswordPolicy.isAcceptable("correct horse battery staple !!")).isTrue();
    }

    @Test
    void allowsNonAsciiUnicode() {
        // Twelve accented letters — no ASCII, still acceptable.
        assertThat(PasswordPolicy.isAcceptable("ëëëëëëëëëëëë")).isTrue();
    }

    @Test
    void countsAstralCodePointsNotUtf16Chars() {
        // Twelve "🎮" glyphs are 12 code points but 24 UTF-16 chars; a naive
        // String.length() would wrongly accept eleven of them. Eleven must fail.
        assertThat(PasswordPolicy.isAcceptable("🎮".repeat(11))).isFalse();
        assertThat(PasswordPolicy.isAcceptable("🎮".repeat(12))).isTrue();
    }
}
