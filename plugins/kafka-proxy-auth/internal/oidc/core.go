package oidc

import (
	"context"
	"crypto/rsa"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"math/big"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

type TokenClaims struct {
	jwt.RegisteredClaims
	Email             string `json:"email"`
	PreferredUsername string `json:"preferred_username"`
	Scope             string `json:"scope"`
	Scp               string `json:"scp"`
}

func (c *TokenClaims) GetScopes() []string {
	src := c.Scope
	if c.Scp != "" {
		src = c.Scp
	}
	return strings.Fields(src)
}

type Validator struct {
	JWKSUrl         string
	AllowedIssuers  []string
	AllowedAuds     []string
	RequiredScopes  []string
	CacheExpiration time.Duration
	Debug           bool

	jwksCache  map[string]*rsa.PublicKey
	cacheTime  time.Time
	cacheMutex sync.RWMutex
}

func (v *Validator) logDebug(format string, args ...interface{}) {
	if v.Debug {
		log.Printf("[DEBUG] Validator: "+format, args...)
	}
}

func (v *Validator) ValidateToken(ctx context.Context, tokenStr string) (*TokenClaims, error) {
	v.logDebug("Starting validation for token (len: %d)", len(tokenStr))

	claims := &TokenClaims{}
	token, err := jwt.ParseWithClaims(tokenStr, claims, func(t *jwt.Token) (interface{}, error) {
		kid, _ := t.Header["kid"].(string)
		v.logDebug("Token header kid: %s", kid)
		return v.getPublicKey(ctx, kid)
	})

	if err != nil {
		return nil, fmt.Errorf("jwt validation error: %w", err)
	}
	if !token.Valid {
		return nil, errors.New("invalid token")
	}

	// Validation Scopes (Fix bug initial)
	if len(v.RequiredScopes) > 0 {
		foundScopes := claims.GetScopes()
		v.logDebug("Required: %v, Found: %v", v.RequiredScopes, foundScopes)
		scopeMap := make(map[string]bool)
		for _, s := range foundScopes {
			scopeMap[s] = true
		}
		for _, req := range v.RequiredScopes {
			if !scopeMap[req] {
				return nil, fmt.Errorf("missing required scope: %s (found: %v)", req, foundScopes)
			}
		}
	}
	v.logDebug("Token successfully validated for sub: %s", claims.Subject)
	return claims, nil
}

func (v *Validator) getPublicKey(ctx context.Context, kid string) (*rsa.PublicKey, error) {
	v.cacheMutex.RLock()
	key, ok := v.jwksCache[kid]
	isValid := time.Since(v.cacheTime) < v.CacheExpiration
	v.cacheMutex.RUnlock()

	if ok && isValid {
		v.logDebug("Public key %s found in cache", kid)
		return key, nil
	}

	v.logDebug("Cache miss or expired for kid %s, refreshing JWKS...", kid)
	if err := v.refreshJWKS(ctx); err != nil {
		return nil, err
	}

	v.cacheMutex.RLock()
	defer v.cacheMutex.RUnlock()
	return v.jwksCache[kid], nil
}

func (v *Validator) refreshJWKS(ctx context.Context) error {
	v.cacheMutex.Lock()
	defer v.cacheMutex.Unlock()

	resp, err := http.Get(v.JWKSUrl)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	var jwks struct {
		Keys []struct {
			Kid string `json:"kid"`
			N   string `json:"n"`
			E   string `json:"e"`
		} `json:"keys"`
	}
	json.NewDecoder(resp.Body).Decode(&jwks)

	v.jwksCache = make(map[string]*rsa.PublicKey)
	for _, k := range jwks.Keys {
		nB, _ := base64.RawURLEncoding.DecodeString(k.N)
		eB, _ := base64.RawURLEncoding.DecodeString(k.E)
		var e int
		for _, b := range eB {
			e = e*256 + int(b)
		}
		v.jwksCache[k.Kid] = &rsa.PublicKey{N: new(big.Int).SetBytes(nB), E: e}
	}
	v.cacheTime = time.Now()
	return nil
}

func ParseStringSlice(s string) []string {
	if s == "" {
		return nil
	}
	return strings.FieldsFunc(s, func(c rune) bool { return c == ',' || c == ' ' })
}
