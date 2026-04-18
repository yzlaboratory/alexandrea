package auth

import (
	"errors"
	"strings"
	"testing"
)

var fastParams = argonParams{
	memKiB:    256,
	timeCost:  1,
	parallel:  1,
	saltBytes: 16,
	keyBytes:  16,
}

func TestHashWithParams_EncodesPHC(t *testing.T) {
	hash, err := hashWithParams("correct horse battery staple!", fastParams)
	if err != nil {
		t.Fatalf("hash: %v", err)
	}
	if !strings.HasPrefix(hash, "$argon2id$v=19$") {
		t.Errorf("prefix wrong: %q", hash)
	}
	if strings.Count(hash, "$") != 5 {
		t.Errorf("expected 5 $-separators, got %d (%q)", strings.Count(hash, "$"), hash)
	}
}

func TestHashWithParams_NondeterministicSalt(t *testing.T) {
	h1, err := hashWithParams("same password", fastParams)
	if err != nil {
		t.Fatalf("hash1: %v", err)
	}
	h2, err := hashWithParams("same password", fastParams)
	if err != nil {
		t.Fatalf("hash2: %v", err)
	}
	if h1 == h2 {
		t.Error("two hashes of the same password are identical; salt is not random")
	}
}

func TestVerifyPassword_CorrectMatches(t *testing.T) {
	hash, err := hashWithParams("correct password", fastParams)
	if err != nil {
		t.Fatalf("hash: %v", err)
	}
	if err := VerifyPassword("correct password", hash); err != nil {
		t.Errorf("verify: %v", err)
	}
}

func TestVerifyPassword_WrongReturnsMismatch(t *testing.T) {
	hash, err := hashWithParams("right", fastParams)
	if err != nil {
		t.Fatalf("hash: %v", err)
	}
	err = VerifyPassword("wrong", hash)
	if !errors.Is(err, ErrPasswordMismatch) {
		t.Errorf("err = %v, want ErrPasswordMismatch", err)
	}
}

func TestVerifyPassword_EmptyPasswordRejectsWithoutPanic(t *testing.T) {
	hash, err := hashWithParams("pw", fastParams)
	if err != nil {
		t.Fatalf("hash: %v", err)
	}
	if err := VerifyPassword("", hash); !errors.Is(err, ErrPasswordMismatch) {
		t.Errorf("err = %v, want ErrPasswordMismatch", err)
	}
}

func TestVerifyPassword_MalformedHashes(t *testing.T) {
	cases := []struct {
		name string
		hash string
	}{
		{"empty", ""},
		{"not an argon2 hash", "$bcrypt$12$whatever"},
		{"too few fields", "$argon2id$v=19$m=1,t=1,p=1"},
		{"wrong algo", "$argon2i$v=19$m=1,t=1,p=1$c2FsdA$aGFzaA"},
		{"bad params", "$argon2id$v=19$notparams$c2FsdA$aGFzaA"},
		{"bad base64 salt", "$argon2id$v=19$m=1,t=1,p=1$!!!!$aGFzaA"},
		{"bad base64 hash", "$argon2id$v=19$m=1,t=1,p=1$c2FsdA$!!!!"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			err := VerifyPassword("anything", tc.hash)
			if err == nil {
				t.Errorf("expected error, got nil")
			}
			if errors.Is(err, ErrPasswordMismatch) {
				t.Errorf("got ErrPasswordMismatch, want format error")
			}
		})
	}
}

func TestVerifyPassword_UnknownVersion(t *testing.T) {
	err := VerifyPassword("any", "$argon2id$v=99$m=1,t=1,p=1$c2FsdA$aGFzaA")
	if !errors.Is(err, ErrUnsupportedAlgo) {
		t.Errorf("err = %v, want ErrUnsupportedAlgo", err)
	}
}

func TestHashPassword_DefaultParamsRoundTrip(t *testing.T) {
	if testing.Short() {
		t.Skip("uses production argon2 params (~250ms per hash)")
	}
	hash, err := HashPassword("production-strength password 123!")
	if err != nil {
		t.Fatalf("hash: %v", err)
	}
	if err := VerifyPassword("production-strength password 123!", hash); err != nil {
		t.Errorf("verify: %v", err)
	}
}

func TestVerifyPassword_EncodedIsSelfContained(t *testing.T) {
	// A hash produced with one set of params must verify correctly without
	// the caller knowing those params. This is the "we can retune later"
	// property from ADR 0003.
	hash, err := hashWithParams("pw", argonParams{memKiB: 512, timeCost: 2, parallel: 1, saltBytes: 16, keyBytes: 16})
	if err != nil {
		t.Fatalf("hash: %v", err)
	}
	if err := VerifyPassword("pw", hash); err != nil {
		t.Errorf("verify with different params at call site: %v", err)
	}
}
