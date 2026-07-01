package io.github.hakjuoh.oauth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import org.junit.jupiter.api.Test;

/** Method-level tests for {@link PkceUtil}. */
class PkceUtilTest {

    /** Reference S256 implementation, independent of the class under test. */
    private static String s256(String verifier) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    // ---- verifyS256 --------------------------------------------------------

    @Test
    void verifyS256ValidChallengeReturnsTrue() throws Exception {
        String verifier = "test-verifier";
        String challenge = s256(verifier);
        assertTrue(PkceUtil.verifyS256(verifier, challenge),
                "matching S256 challenge should verify");
    }

    @Test
    void verifyS256WrongChallengeReturnsFalse() throws Exception {
        String verifier = "test-verifier";
        String wrong = s256("other-verifier");
        assertFalse(PkceUtil.verifyS256(verifier, wrong),
                "non-matching challenge must not verify");
    }

    @Test
    void verifyS256NullVerifierReturnsFalse() {
        assertFalse(PkceUtil.verifyS256(null, "anything"),
                "null verifier returns false");
    }

    @Test
    void verifyS256NullChallengeReturnsFalse() {
        assertFalse(PkceUtil.verifyS256("verifier", null),
                "null challenge returns false");
    }

    @Test
    void verifyS256BothNullReturnsFalse() {
        assertFalse(PkceUtil.verifyS256(null, null),
                "both null returns false");
    }

    @Test
    void verifyS256EmptyVerifierMatchingChallengeReturnsTrue() throws Exception {
        // An empty verifier still produces a valid digest; matching challenge verifies.
        String challenge = s256("");
        assertTrue(PkceUtil.verifyS256("", challenge),
                "empty verifier with its own S256 challenge verifies");
    }

    @Test
    void verifyS256EmptyVerifierWithNonMatchingChallengeReturnsFalse() {
        assertFalse(PkceUtil.verifyS256("", "not-the-digest"),
                "empty verifier with wrong challenge returns false");
    }

    @Test
    void verifyS256NonEmptyVerifierEmptyChallengeReturnsFalse() {
        assertFalse(PkceUtil.verifyS256("verifier", ""),
                "empty challenge cannot match a non-empty digest");
    }

    @Test
    void verifyS256IsCaseSensitiveOnBase64Url() throws Exception {
        String verifier = "case-check-verifier";
        String challenge = s256(verifier);
        String swappedCase = swapAsciiCase(challenge);
        // Guard: ensure we actually produced a different string with letters present.
        if (swappedCase.equals(challenge)) {
            // No letters to swap; skip meaningfully by asserting the true case still holds.
            assertTrue(PkceUtil.verifyS256(verifier, challenge));
            return;
        }
        assertFalse(PkceUtil.verifyS256(verifier, swappedCase),
                "base64url comparison is case-sensitive");
    }

    @Test
    void verifyS256UnreservedCharsVerifierWorks() throws Exception {
        String verifier = "ABCabc012-._~ABCabc012-._~ABCabc012-._~xyz";
        String challenge = s256(verifier);
        assertTrue(PkceUtil.verifyS256(verifier, challenge),
                "RFC 7636 unreserved-char verifier verifies");
    }

    @Test
    void verifyS256LongVerifierWorks() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 128; i++) {
            sb.append((char) ('A' + (i % 26)));
        }
        String verifier = sb.toString();
        String challenge = s256(verifier);
        assertTrue(PkceUtil.verifyS256(verifier, challenge),
                "128-char verifier verifies");
    }

    @Test
    void verifyS256ChallengeHasNoPadding() throws Exception {
        // The class encodes without padding; a padded challenge must not match.
        String verifier = "padding-check";
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII));
        String padded = Base64.getUrlEncoder().encodeToString(digest); // with '=' padding
        String unpadded = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        assertTrue(PkceUtil.verifyS256(verifier, unpadded),
                "unpadded challenge verifies");
        if (!padded.equals(unpadded)) {
            assertFalse(PkceUtil.verifyS256(verifier, padded),
                    "padded challenge must not verify against unpadded computation");
        }
    }

    // ---- constantTimeEquals ------------------------------------------------

    @Test
    void constantTimeEqualsIdenticalReturnsTrue() {
        assertTrue(PkceUtil.constantTimeEquals("hello-world", "hello-world"),
                "identical strings are equal");
    }

    @Test
    void constantTimeEqualsSameLengthDifferentReturnsFalse() {
        assertFalse(PkceUtil.constantTimeEquals("abcde", "abcdf"),
                "same-length differing strings are not equal");
    }

    @Test
    void constantTimeEqualsFirstByteDiffersReturnsFalse() {
        assertFalse(PkceUtil.constantTimeEquals("Xbcde", "abcde"),
                "difference in first byte returns false");
    }

    @Test
    void constantTimeEqualsLastByteDiffersReturnsFalse() {
        assertFalse(PkceUtil.constantTimeEquals("abcdX", "abcde"),
                "difference in last byte returns false");
    }

    @Test
    void constantTimeEqualsMiddleByteDiffersReturnsFalse() {
        assertFalse(PkceUtil.constantTimeEquals("abXde", "abcde"),
                "difference in middle byte returns false");
    }

    @Test
    void constantTimeEqualsAShorterReturnsFalse() {
        assertFalse(PkceUtil.constantTimeEquals("abc", "abcde"),
                "shorter a returns false");
    }

    @Test
    void constantTimeEqualsBShorterReturnsFalse() {
        assertFalse(PkceUtil.constantTimeEquals("abcde", "abc"),
                "shorter b returns false");
    }

    @Test
    void constantTimeEqualsNullAReturnsFalse() {
        assertFalse(PkceUtil.constantTimeEquals(null, "abc"),
                "null a returns false");
    }

    @Test
    void constantTimeEqualsNullBReturnsFalse() {
        assertFalse(PkceUtil.constantTimeEquals("abc", null),
                "null b returns false");
    }

    @Test
    void constantTimeEqualsBothNullReturnsFalse() {
        assertFalse(PkceUtil.constantTimeEquals(null, null),
                "both null returns false");
    }

    @Test
    void constantTimeEqualsBothEmptyReturnsTrue() {
        assertTrue(PkceUtil.constantTimeEquals("", ""),
                "two empty strings are equal");
    }

    @Test
    void constantTimeEqualsEmptyAvsNonEmptyBReturnsFalse() {
        assertFalse(PkceUtil.constantTimeEquals("", "x"),
                "empty a vs non-empty b returns false");
    }

    @Test
    void constantTimeEqualsEmptyBvsNonEmptyAReturnsFalse() {
        assertFalse(PkceUtil.constantTimeEquals("x", ""),
                "empty b vs non-empty a returns false");
    }

    @Test
    void constantTimeEqualsSingleCharMatchReturnsTrue() {
        assertTrue(PkceUtil.constantTimeEquals("a", "a"),
                "single matching char is equal");
    }

    @Test
    void constantTimeEqualsSingleCharMismatchReturnsFalse() {
        assertFalse(PkceUtil.constantTimeEquals("a", "b"),
                "single differing char is not equal");
    }

    @Test
    void constantTimeEqualsMultiByteUtf8Equal() {
        // "café" and identical: multi-byte UTF-8 sequences compare byte-by-byte.
        assertTrue(PkceUtil.constantTimeEquals("café", "café"),
                "identical multi-byte UTF-8 strings are equal");
    }

    @Test
    void constantTimeEqualsMultiByteUtf8DifferentReturnsFalse() {
        // Two different accented chars have same char count but differing bytes.
        assertFalse(PkceUtil.constantTimeEquals("café", "cafè"),
                "differing multi-byte UTF-8 strings are not equal");
    }

    @Test
    void constantTimeEqualsUtf8ByteLengthMismatchReturnsFalse() {
        // Same char count (1) but different UTF-8 byte length: 'a' (1 byte) vs 'é' (2 bytes).
        assertFalse(PkceUtil.constantTimeEquals("a", "é"),
                "differing UTF-8 byte lengths return false");
    }

    @Test
    void constantTimeEqualsTrailingSpaceDiffersReturnsFalse() {
        assertFalse(PkceUtil.constantTimeEquals("abc ", "abc"),
                "trailing space changes length/content and is not equal");
    }

    @Test
    void constantTimeEqualsTrailingSpaceSameReturnsTrue() {
        assertTrue(PkceUtil.constantTimeEquals("abc ", "abc "),
                "identical strings with trailing spaces are equal");
    }

    @Test
    void constantTimeEqualsAllNulCharsEqual() {
        String a = new String(new char[] {0, 0, 0});
        String b = new String(new char[] {0, 0, 0});
        assertTrue(PkceUtil.constantTimeEquals(a, b),
                "identical all-NUL strings are equal");
    }

    @Test
    void constantTimeEqualsNulCharDiffersReturnsFalse() {
        String a = new String(new char[] {0, 0, 0});
        String b = new String(new char[] {0, 1, 0});
        assertFalse(PkceUtil.constantTimeEquals(a, b),
                "differing NUL/non-NUL bytes are not equal");
    }

    // ---- helpers -----------------------------------------------------------

    private static String swapAsciiCase(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'a' && c <= 'z') {
                sb.append((char) (c - 32));
            } else if (c >= 'A' && c <= 'Z') {
                sb.append((char) (c + 32));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
