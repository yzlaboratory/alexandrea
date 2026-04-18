package auth

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"fmt"
	"strings"

	"golang.org/x/crypto/argon2"
)

const (
	defaultMemoryKiB   uint32 = 64 * 1024
	defaultTimeCost    uint32 = 3
	defaultParallelism uint8  = 2
	defaultSaltBytes   uint32 = 32
	defaultKeyBytes    uint32 = 32
)

var (
	ErrHashFormat       = errors.New("argon2: invalid hash format")
	ErrUnsupportedAlgo  = errors.New("argon2: unsupported algorithm")
	ErrPasswordMismatch = errors.New("argon2: password does not match")
)

type argonParams struct {
	memKiB    uint32
	timeCost  uint32
	parallel  uint8
	saltBytes uint32
	keyBytes  uint32
}

func defaultParams() argonParams {
	return argonParams{
		memKiB:    defaultMemoryKiB,
		timeCost:  defaultTimeCost,
		parallel:  defaultParallelism,
		saltBytes: defaultSaltBytes,
		keyBytes:  defaultKeyBytes,
	}
}

func HashPassword(password string) (string, error) {
	return hashWithParams(password, defaultParams())
}

func hashWithParams(password string, p argonParams) (string, error) {
	salt := make([]byte, p.saltBytes)
	if _, err := rand.Read(salt); err != nil {
		return "", fmt.Errorf("read salt: %w", err)
	}
	key := argon2.IDKey([]byte(password), salt, p.timeCost, p.memKiB, p.parallel, p.keyBytes)

	encSalt := base64.RawStdEncoding.EncodeToString(salt)
	encKey := base64.RawStdEncoding.EncodeToString(key)
	return fmt.Sprintf(
		"$argon2id$v=%d$m=%d,t=%d,p=%d$%s$%s",
		argon2.Version, p.memKiB, p.timeCost, p.parallel, encSalt, encKey,
	), nil
}

func VerifyPassword(password, encoded string) error {
	parts := strings.Split(encoded, "$")
	if len(parts) != 6 || parts[1] != "argon2id" {
		return ErrHashFormat
	}

	var version int
	if _, err := fmt.Sscanf(parts[2], "v=%d", &version); err != nil {
		return ErrHashFormat
	}
	if version != argon2.Version {
		return ErrUnsupportedAlgo
	}

	var p argonParams
	if _, err := fmt.Sscanf(parts[3], "m=%d,t=%d,p=%d", &p.memKiB, &p.timeCost, &p.parallel); err != nil {
		return ErrHashFormat
	}

	salt, err := base64.RawStdEncoding.DecodeString(parts[4])
	if err != nil {
		return ErrHashFormat
	}
	want, err := base64.RawStdEncoding.DecodeString(parts[5])
	if err != nil {
		return ErrHashFormat
	}

	got := argon2.IDKey([]byte(password), salt, p.timeCost, p.memKiB, p.parallel, uint32(len(want)))
	if subtle.ConstantTimeCompare(got, want) != 1 {
		return ErrPasswordMismatch
	}
	return nil
}
