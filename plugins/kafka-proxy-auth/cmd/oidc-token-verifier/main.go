package main

import (
	"context"
	"log"
	"os"
	"strings"
	"time"

	"github.com/grepplabs/kafka-proxy-oauth2-plugins/internal/oidc"
	"github.com/grepplabs/kafka-proxy/plugin/local-auth/shared"
	"github.com/hashicorp/go-plugin"
	"github.com/spf13/pflag"
)

type verifierPlugin struct {
	v           *oidc.Validator
	staticUsers map[string]string
}

func (p *verifierPlugin) Authenticate(user, pass string) (bool, int32, error) {
	// 1. Essai d'authentification statique (si configurée)
	log.Printf("[INFO] Authenticate call for user: %s", user)
	if expectedPass, exists := p.staticUsers[user]; exists {
		if pass == expectedPass {
			return true, 0, nil
		}
		log.Printf("[INFO] Static user %s found, but password mismatch", user)
	}

	// 2. Essai d'authentification JWT
	if !strings.HasPrefix(pass, "eyJ") {
		return false, 1, nil
	}
	_, err := p.v.ValidateToken(context.Background(), pass)
	if err != nil {
		log.Printf("[ERROR] Auth failed for user %s: %v", user, err)
		return false, 1, nil
	}
	log.Printf("[INFO] Auth successfully for user: %s", user)
	return true, 0, nil
}

func main() {
	var jwks, aud, allowedIssuers string
	var staticUsersRaw []string
	var debug bool

	pflag.StringVar(&jwks, "jwks-url", os.Getenv("VERIFIER_JWKS_URL"), "JWKS URL")
	pflag.StringVar(&aud, "client-id", os.Getenv("VERIFIER_CLIENT_ID"), "Allowed Audience")
	pflag.StringVar(&allowedIssuers, "allowed-issuer", "", "Allowed Issuers")
	pflag.StringSliceVar(&staticUsersRaw, "static-user", []string{}, "Static users user:pass")
	pflag.BoolVar(&debug, "debug", os.Getenv("DEBUG") == "true", "Enable debug logging")
	pflag.Parse()

	// Reconstruction de la map des utilisateurs statiques
	staticMap := make(map[string]string)

	if debug {
		if len(staticMap) == 0 {
			envStatic := os.Getenv("AUTH_STATIC_USERS")
			if envStatic != "" && envStatic != "AUTH_STATIC_USERS" {
				log.Printf("[DEBUG] Loaded static users from environment variable fallback")
			}
		}
		log.Printf("[DEBUG] Verifier started with %d static users", len(staticMap))
		for u := range staticMap {
			log.Printf("[DEBUG] Loaded static user: %s", u)
		}
	}

	for _, pair := range staticUsersRaw {
		parts := strings.SplitN(pair, ":", 2)
		if len(parts) == 2 {
			staticMap[parts[0]] = parts[1]
		}
	}

	validator := &oidc.Validator{
		JWKSUrl:         jwks,
		AllowedAuds:     oidc.ParseStringSlice(aud),
		AllowedIssuers:  oidc.ParseStringSlice(allowedIssuers),
		CacheExpiration: 1 * time.Hour,
		Debug:           debug,
	}

	plugin.Serve(&plugin.ServeConfig{
		HandshakeConfig: shared.Handshake,
		Plugins: map[string]plugin.Plugin{
			"passwordAuthenticator": &shared.PasswordAuthenticatorPlugin{Impl: &verifierPlugin{
				v:           validator,
				staticUsers: staticMap,
			}},
		},
		GRPCServer: plugin.DefaultGRPCServer,
	})
}
