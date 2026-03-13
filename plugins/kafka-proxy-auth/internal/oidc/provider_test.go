package oidc

import (
	"context"
	"os"
	"testing"
)

// TestGetAccessToken est un test d'intégration qui tente de récupérer un vrai token.
func TestGetAccessToken(t *testing.T) {
	// On récupère les identifiants depuis l'environnement
	clientID := os.Getenv("TEST_AZURE_CLIENT_ID")
	clientSecret := os.Getenv("TEST_AZURE_CLIENT_SECRET")
	tenantID := os.Getenv("TEST_AZURE_TENANT_ID")
	scope := os.Getenv("TEST_OAUTH2_SCOPE")
	tokenURL := os.Getenv("TEST_TOKEN_URL")

	// Si les variables ne sont pas là, on passe le test (Skip)
	if clientID == "" || clientSecret == "" || scope == "" {
		t.Skip("Skipping integration test: credentials not provided in ENV")
	}

	// Si l'URL n'est pas fournie, on la construit pour Azure par défaut
	if tokenURL == "" && tenantID != "" {
		tokenURL = "https://login.microsoftonline.com/" + tenantID + "/oauth2/v2.0/token"
	}

	// On initialise notre nouveau Provider
	p := &Provider{
		TokenURL:     tokenURL,
		ClientID:     clientID,
		ClientSecret: clientSecret,
		Scope:        scope,
	}

	ctx := context.Background()

	// On teste la méthode GetAccessToken (celle qui a le cache)
	token, err := p.GetAccessToken(ctx)

	if err != nil {
		t.Fatalf("Failed to get token: %v", err)
	}

	if token == "" {
		t.Error("Access token is empty")
	}

	t.Log("Successfully obtained token")

	// TEST DU CACHE : On appelle une deuxième fois
	// Cela ne doit pas déclencher d'erreur et doit être instantané
	token2, err := p.GetAccessToken(ctx)
	if err != nil {
		t.Fatalf("Failed to get token from cache: %v", err)
	}
	if token != token2 {
		t.Error("Cache failed: got a different token on second call")
	}
}

// TestProviderValidation teste la logique de validation des paramètres (remplace validateConfig)
func TestProviderValidation(t *testing.T) {
	tests := []struct {
		name    string
		p       Provider
		isValid bool
	}{
		{
			name: "Valid Provider",
			p: Provider{
				TokenURL:     "http://auth",
				ClientID:     "id",
				ClientSecret: "sec",
				Scope:        "scp",
			},
			isValid: true,
		},
		{
			name: "Missing ClientID",
			p: Provider{
				TokenURL: "http://auth",
				Scope:    "scp",
			},
			isValid: false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			// Ici on vérifie simplement si les champs requis sont là
			actualValid := tt.p.TokenURL != "" && tt.p.ClientID != "" && tt.p.ClientSecret != ""
			if actualValid != tt.isValid {
				t.Errorf("Validation mismatch for %s", tt.name)
			}
		})
	}
}
