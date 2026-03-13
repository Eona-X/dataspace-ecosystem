variable "provider_name" {
  default = "provider"
}

variable "consumer_name" {
  default = "consumer"
}

variable "authority_name" {
  default = "authority"
}

variable "kube_context" {
  default = "kind-dse-cluster"
}

variable "kube_config_path" {
  default = "~/.kube/config"
}

variable "environment" {
  description = "The environment (local, devbox or production)"
  type        = string
  default     = ""
}

variable "account_secret_azurite" {
  default     = "pass"
  description = "Account secret for event hub"
  sensitive   = true
}

variable "account_name_azurite" {
  default     = "user"
  description = "Account name for event hub"
}

variable "devbox-registry" {
  description = "The container registry for devbox environment"
  type        = string
  default     = "cds.sec.io"
}

variable "devbox-registry-cred" {
  description = "The image pull secret name for devbox environment"
  type        = string
  default     = "cdsregistrycred"
}


# Authentication Configuration for System Tests
# This file provides variables for enabling authentication in system tests

variable "auth_enabled" {
  description = "Enable downstream authentication for consumer proxies"
  type        = bool
  default     = true
}

variable "auth_mechanism" {
  description = "Authentication mechanism: PLAIN (JWT-over-PLAIN) or OAUTHBEARER (proper OAuth2)"
  type        = string
  default     = "PLAIN"
  validation {
    condition     = contains(["PLAIN", "OAUTHBEARER"], var.auth_mechanism)
    error_message = "Authentication mechanism must be either PLAIN or OAUTHBEARER."
  }
}

variable "auth_static_users" {
  description = "Static users for fallback authentication (format: username1:password1,username2:password2)"
  type        = string
  default     = "admin:admin123,token:token123"
  sensitive   = true
}

# TLS Listener Configuration
variable "tls_listener_enabled" {
  description = "Enable TLS for proxy listener (clients connect to proxy via TLS)"
  type        = bool
  default     = false # Disable by default for testing
}

# Vault Configuration
variable "vault_folder" {
  description = "Vault folder for secrets organization. Empty = secret/, 'consumer' = secret/consumer/"
  type        = string
  default     = ""
}

# Keycloak/OIDC Configuration
# FIXME: identity provider generic name
variable "keycloak_base_url" {
  description = "Base URL of Keycloak"
  type        = string
  default     = "http://172.18.0.1:8080"
}

variable "keycloak_realm" {
  description = "Keycloak realm name"
  type        = string
  default     = "master"
}

variable "verifier_client_id" {
  description = "Client ID expected as audience (aud) by the verifier"
  type        = string
  default     = "dataspace-ui"
}

variable "verifier_required_scopes" {
  description = "Comma-separated list of required scopes for verifier (e.g., 'openid,email')"
  type        = string
  default     = "openid,email"
}

variable "provider_client_id" {
  description = "Confidential client ID for the proxy when using client_credentials"
  type        = string
  default     = "kafka-proxy"
}

variable "provider_client_secret" {
  description = "Secret for the confidential proxy client (used for client_credentials)"
  type        = string
  default     = "jOfmWbbmwzVg8ES6kTiJrC6xuXgrPpeu"
  sensitive   = true
}

variable "provider_scope" {
  description = "Scope(s) to request for client_credentials (optional, e.g., 'openid')"
  type        = string
  default     = "openid email"
}

variable "provider_auth_mechanism" {
  description = "Authentication mechanism: PLAIN (JWT-over-PLAIN) or OAUTHBEARER (proper OAuth2)"
  type        = string
  default     = "OAUTHBEARER"
  validation {
    condition     = contains(["PLAIN", "OAUTHBEARER"], var.provider_auth_mechanism)
    error_message = "Authentication mechanism must be either PLAIN or OAUTHBEARER."
  }
}
