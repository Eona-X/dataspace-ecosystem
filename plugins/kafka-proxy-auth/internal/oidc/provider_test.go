package oidc

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestGetAccessToken(t *testing.T) {
	// Create a mock server
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			t.Errorf("expected POST, got %s", r.Method)
		}
		if err := r.ParseForm(); err != nil {
			t.Errorf("failed to parse form: %v", err)
		}
		if r.PostFormValue("grant_type") != "client_credentials" {
			t.Errorf("expected grant_type=client_credentials, got %s", r.PostFormValue("grant_type"))
		}

		// Mock response
		w.Header().Set("Content-Type", "application/json")
		resp := map[string]interface{}{
			"access_token": generateMockToken("test-client"),
			"expires_in":   3600,
		}
		json.NewEncoder(w).Encode(resp)
	}))
	defer server.Close()

	provider := &Provider{
		ClientID:     "clientId",
		ClientSecret: "clientSecret",
		TokenURL:     server.URL,
		Scope:        "email profile",
		Debug:        true,
	}

	token, err := provider.GetAccessToken(context.Background())

	if err != nil {
		t.Fatalf("expected no error, got: %v", err)
	}

	if token == "" {
		t.Fatal("expected non-empty token")
	}

	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		t.Fatalf("expected JWT with 3 parts, got %d: %s", len(parts), token)
	}

	payload, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		t.Fatalf("failed to decode JWT payload: %v", err)
	}

	var claims struct {
		Sub      string `json:"sub"`
		Exp      int64  `json:"exp"`
		ClientID string `json:"client_id"`
		Aud      any    `json:"aud"`
	}
	if err := json.Unmarshal(payload, &claims); err != nil {
		t.Fatalf("failed to parse JWT claims: %v", err)
	}
	if claims.Sub == "" {
		t.Error("JWT missing 'sub' claim")
	}
	if claims.Exp == 0 {
		t.Error("JWT missing 'exp' claim")
	}

	if claims.ClientID == "" {
		t.Error("JWT missing 'client_id' claim")
	}
	if claims.Aud == nil {
		t.Error("JWT missing 'aud' claim")
	}
	if time.Unix(claims.Exp, 0).Before(time.Now()) {
		t.Errorf("JWT already expired at %v", time.Unix(claims.Exp, 0))
	}

	t.Logf("token: %v", token)
	t.Logf("claims: %+v", claims)
}

func generateMockToken(clientID string) string {
	header := base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"HS256","typ":"JWT"}`))
	payloadObj := map[string]interface{}{
		"sub":       "test-sub",
		"exp":       time.Now().Add(time.Hour).Unix(),
		"client_id": clientID,
		"aud":       "test-aud",
	}
	payloadBytes, _ := json.Marshal(payloadObj)
	payload := base64.RawURLEncoding.EncodeToString(payloadBytes)
	signature := base64.RawURLEncoding.EncodeToString([]byte("signature"))
	return header + "." + payload + "." + signature
}
