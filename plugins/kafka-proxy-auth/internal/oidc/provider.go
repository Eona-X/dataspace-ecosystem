package oidc

import (
	"context"
	"encoding/json"
	"log"
	"net/http"
	"net/url"
	"sync"
	"time"
)

type Provider struct {
	TokenURL     string
	ClientID     string
	ClientSecret string
	Scope        string
	Debug        bool

	mu          sync.Mutex
	cachedToken string
	expiry      time.Time
}

func (p *Provider) logDebug(format string, args ...interface{}) {
	if p.Debug {
		log.Printf("[DEBUG] Provider: "+format, args...)
	}
}

func (p *Provider) GetAccessToken(ctx context.Context) (string, error) {
	p.mu.Lock()
	defer p.mu.Unlock()

	if p.cachedToken != "" && time.Now().Add(10*time.Second).Before(p.expiry) {
		p.logDebug("Using cached access token (expires at %v)", p.expiry)
		return p.cachedToken, nil
	}

	p.logDebug("Fetching new access token from %s", p.TokenURL)
	data := url.Values{}
	data.Set("grant_type", "client_credentials")
	data.Set("client_id", p.ClientID)
	data.Set("client_secret", p.ClientSecret)
	data.Set("scope", p.Scope)

	resp, err := http.PostForm(p.TokenURL, data)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()

	var res struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int    `json:"expires_in"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&res); err != nil {
		return "", err
	}

	if res.ExpiresIn == 0 {
		p.logDebug("Warning: IDP didn't return expires_in, defaulting to 1h")
		res.ExpiresIn = 3600
	}

	p.cachedToken = res.AccessToken
	p.expiry = time.Now().Add(time.Duration(res.ExpiresIn) * time.Second)
	p.logDebug("Token cached. Expiry: %v", p.expiry)
	return p.cachedToken, nil
}
