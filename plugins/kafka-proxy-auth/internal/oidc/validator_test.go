package oidc

import (
	"testing"
)

// TestParseStringSlice vérifie que notre fix pour le bug des scopes fonctionne.
// Il teste si on découpe bien par virgule ou par espace.
func TestParseStringSlice(t *testing.T) {
	tests := []struct {
		name     string
		input    string
		expected int
	}{
		{"Espaces", "openid profile email", 3},
		{"Virgules", "openid,profile,email", 3},
		{"Mélange", "openid, profile  email", 3},
		{"Vide", "", 0},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := ParseStringSlice(tt.input)
			if len(result) != tt.expected {
				t.Errorf("ParseStringSlice(%s) = %d éléments, attendu %d", tt.input, len(result), tt.expected)
			}
		})
	}
}

// TestGetScopes vérifie que l'on sait lire les scopes qu'ils soient dans "scp" ou "scope"
func TestGetScopes(t *testing.T) {
	tests := []struct {
		name     string
		claims   TokenClaims
		expected int
	}{
		{"Depuis scope", TokenClaims{Scope: "read write"}, 2},
		{"Depuis scp", TokenClaims{Scp: "admin user"}, 2},
		{"Priorité scp", TokenClaims{Scope: "read", Scp: "admin"}, 1},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			res := tt.claims.GetScopes()
			if len(res) != tt.expected {
				t.Errorf("%s: attendu %d scopes, eu %d", tt.name, tt.expected, len(res))
			}
		})
	}
}

// TestRequiredScopesLogic simule la validation des scopes
func TestRequiredScopesLogic(t *testing.T) {
	// On configure un validateur avec des scopes requis
	v := &Validator{
		RequiredScopes: []string{"read", "write"},
	}

	tests := []struct {
		name  string
		found []string
		pass  bool
	}{
		{"Tout est là", []string{"read", "write", "openid"}, true},
		{"Manque un", []string{"read", "openid"}, false},
		{"Rien du tout", []string{}, false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			// On simule la logique de ValidateToken
			scopeMap := make(map[string]bool)
			for _, s := range tt.found {
				scopeMap[s] = true
			}

			allPresent := true
			for _, req := range v.RequiredScopes {
				if !scopeMap[req] {
					allPresent = false
					break
				}
			}

			if allPresent != tt.pass {
				t.Errorf("%s: attendu %v, eu %v", tt.name, tt.pass, allPresent)
			}
		})
	}
}

// TestIssuerValidation vérifie la logique de l'issuer (sts vs login.microsoft)
func TestIssuerValidation(t *testing.T) {
	v := &Validator{
		AllowedIssuers: []string{"https://example.com/v2.0"},
	}

	tests := []struct {
		issuer   string
		expected bool
	}{
		{"https://example.com/v2.0", true},
		{"https://example.com/v2.0/", true}, // Trailing slash
		{"https://evil.com", false},
	}

	for _, tt := range tests {
		t.Run(tt.issuer, func(t *testing.T) {
			// Simule la logique de ValidateToken
			valid := false
			currIss := tt.issuer
			if currIss != "" && currIss[len(currIss)-1] == '/' {
				currIss = currIss[:len(currIss)-1]
			}

			for _, allowed := range v.AllowedIssuers {
				normAllowed := allowed
				if normAllowed[len(normAllowed)-1] == '/' {
					normAllowed = normAllowed[:len(normAllowed)-1]
				}
				if currIss == normAllowed {
					valid = true
					break
				}
			}

			if valid != tt.expected {
				t.Errorf("Issuer %s: attendu %v, eu %v", tt.issuer, tt.expected, valid)
			}
		})
	}
}
