package main

import (
	"context"
	"log"
	"os"
	"time"

	"github.com/grepplabs/kafka-proxy-oauth2-plugins/internal/oidc"
	"github.com/grepplabs/kafka-proxy/pkg/apis"
	"github.com/grepplabs/kafka-proxy/plugin/token-info/shared"
	"github.com/hashicorp/go-plugin"
	"github.com/spf13/pflag"
)

type infoPlugin struct {
	validator *oidc.Validator
}

func (p *infoPlugin) VerifyToken(ctx context.Context, req apis.VerifyRequest) (apis.VerifyResponse, error) {
	log.Printf("[INFO] Verifying token")
	claims, err := p.validator.ValidateToken(ctx, req.Token)
	if err != nil {
		log.Printf("[ERROR] Token validation failed: %v", err)
		return apis.VerifyResponse{Success: false, Status: 4}, nil
	}
	log.Printf("[INFO] Verified sub: %s", claims.Subject)
	return apis.VerifyResponse{Success: true, Status: 0}, nil
}

func main() {
	var clientID, jwksURL, requiredScope, allowedIssuers string
	var debug bool

	pflag.StringVar(&clientID, "client-id", os.Getenv("INFO_CLIENT_ID"), "Client ID")
	pflag.StringVar(&jwksURL, "jwks-url", os.Getenv("INFO_JWKS_URL"), "JWKS URL")
	pflag.StringVar(&requiredScope, "required-scope", os.Getenv("INFO_REQUIRED_SCOPE"), "Required Scopes")
	pflag.StringVar(&allowedIssuers, "allowed-issuer", os.Getenv("INFO_ALLOWED_ISSUERS"), "Allowed Issuers")
	pflag.BoolVar(&debug, "debug", os.Getenv("DEBUG") == "true", "Enable debug logging")
	pflag.Parse()

	// Initialisation du validateur avec les flags
	validator := &oidc.Validator{
		JWKSUrl:         jwksURL,
		AllowedAuds:     oidc.ParseStringSlice(clientID),
		RequiredScopes:  oidc.ParseStringSlice(requiredScope),
		AllowedIssuers:  oidc.ParseStringSlice(allowedIssuers),
		CacheExpiration: 1 * time.Hour,
		Debug:           debug,
	}

	plugin.Serve(&plugin.ServeConfig{
		HandshakeConfig: shared.Handshake,
		Plugins: map[string]plugin.Plugin{
			"tokenInfo": &shared.TokenInfoPlugin{Impl: &infoPlugin{validator: validator}},
		},
		GRPCServer: plugin.DefaultGRPCServer,
	})
}
