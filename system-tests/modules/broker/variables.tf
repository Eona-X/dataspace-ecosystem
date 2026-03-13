variable "participants" {
  description = "The list of participants to create a broker for"
  type        = list(string)
}

variable "environment" {
  description = "The environment (local or production)"
  type        = string
}

variable "devbox-registry" {
  description = "The container registry for devbox environment"
  type        = string
  default     = ""
}

variable "devbox-registry-cred" {
  description = "The image pull secret name for devbox environment"
  type        = string
  default     = ""
}

# Keycloak/OIDC settings for local tests
variable "keycloak_base_url" {
  description = "Base URL of Keycloak (localhost won't work, k8s localhost is a pod)"
  type        = string
  default     = ""
}

variable "keycloak_realm" {
  description = "Keycloak realm name"
  type        = string
  default     = ""
}

variable "verifier_client_id" {
  description = "Client ID expected as audience (aud) by the verifier (typically the front-end public client)"
  type        = string
  default     = ""
}

# Optional: provider (client_credentials) settings for future use
variable "provider_client_id" {
  description = "Confidential client ID for the proxy when using client_credentials (optional)"
  type        = string
  default     = ""
}

variable "provider_scope" {
  description = "Scope(s) to request for client_credentials (optional, e.g., 'openid')"
  type        = string
  default     = ""
}

variable "provider_auth_mechanism" {
  description = "Authentication mechanism: PLAIN (JWT-over-PLAIN) or OAUTHBEARER (proper OAuth2)"
  type        = string
  default     = ""
}