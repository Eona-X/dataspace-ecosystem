package main

import (
	"context"
	"log"
	"os"

	"github.com/grepplabs/kafka-proxy-oauth2-plugins/internal/oidc"
	"github.com/grepplabs/kafka-proxy/pkg/apis"
	"github.com/grepplabs/kafka-proxy/plugin/token-provider/shared"
	"github.com/hashicorp/go-plugin"
	"github.com/spf13/pflag"
)

type providerPlugin struct{ p *oidc.Provider }

func (p *providerPlugin) GetToken(ctx context.Context, req apis.TokenRequest) (apis.TokenResponse, error) {
	token, err := p.p.GetAccessToken(ctx)
	if err != nil {
		log.Printf("[ERROR] Token fetch: %v", err)
		return apis.TokenResponse{Success: false, Status: 1}, nil
	}
	log.Printf("[INFO] Token successfully fetch")
	return apis.TokenResponse{Success: true, Status: 0, Token: token}, nil
}

func main() {
	var debug bool
	var id, sec, url, scp string
	pflag.StringVar(&id, "client-id", os.Getenv("PROVIDER_CLIENT_ID"), "")
	pflag.StringVar(&sec, "client-secret", os.Getenv("PROVIDER_CLIENT_SECRET"), "")
	pflag.StringVar(&url, "token-url", os.Getenv("PROVIDER_TOKEN_URL"), "")
	pflag.StringVar(&scp, "scope", os.Getenv("PROVIDER_SCOPE"), "")
	pflag.BoolVar(&debug, "debug", os.Getenv("DEBUG") == "true", "Enable debug logging")
	pflag.Parse()

	plugin.Serve(&plugin.ServeConfig{
		HandshakeConfig: shared.Handshake,
		Plugins: map[string]plugin.Plugin{
			"tokenProvider": &shared.TokenProviderPlugin{Impl: &providerPlugin{
				p: &oidc.Provider{TokenURL: url, ClientID: id, ClientSecret: sec, Scope: scp, Debug: debug},
			}},
		},
		GRPCServer: plugin.DefaultGRPCServer,
	})
}
