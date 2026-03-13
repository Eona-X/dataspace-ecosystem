variable "participant_name" {}

variable "participant_with_prefix" {
  description = "(Optional) Prefix for participant, defaults to empty string"
  type        = string
  default     = ""
}

variable "db_server_fqdn" {
  default = "postgres"
}

variable "postgres_admin_credentials_secret_name" {
  description = "(Required) Secret containing the DB Admin credentials"
}

variable "negotiation_state_machine_wait_millis" {
  description = "(Optional) Wait time of the contract state machines in milliseconds"
  default     = 2000
}

variable "transfer_state_machine_wait_millis" {
  description = "(Optional) Wait time of the transfer state machines in milliseconds"
  default     = 2000
}

variable "policy_monitor_state_machine_wait_millis" {
  description = "(Optional) Wait time of the policy_monitor state machines in milliseconds"
  default     = 5000
}

variable "data_plane_state_machine_wait_millis" {
  description = "(Optional) Wait time of the data plane state machines in milliseconds"
  default     = 5000
}

variable "environment" {
  description = "The environment (local or production)"
  type        = string
}

variable "devbox-registry" {
  description = "The container registry for devbox environment"
  type        = string
}

variable "devbox-registry-cred" {
  description = "The image pull secret name for devbox environment"
  type        = string
}


# Authentication Configuration
variable "auth_enabled" {
  description = "Enable downstream authentication for consumer proxies"
  type        = bool
  default     = true
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

variable "verifier_required_scopes" {
  description = "Comma-separated list of required scopes for verifier (e.g., 'openid,email')"
  type        = string
  default     = ""
}

# Optional: provider (client_credentials) settings for future use
variable "provider_client_id" {
  description = "Confidential client ID for the proxy when using client_credentials (optional)"
  type        = string
  default     = ""
}

variable "provider_client_secret" {
  description = "Secret for the confidential proxy client (used for client_credentials)"
  type        = string
  default     = ""
  sensitive   = true
}

variable "provider_scope" {
  description = "Scope(s) to request for client_credentials (optional, e.g., 'openid')"
  type        = string
  default     = "openid email"
}

variable "auth_mechanism" {
  description = "Authentication mechanism: PLAIN (JWT-over-PLAIN) or OAUTHBEARER (proper OAuth2)"
  type        = string
  default     = ""
}

variable "auth_static_users" {
  description = "Static users for fallback authentication (format: username1:password1,username2:password2)"
  type        = string
  default     = "admin:admin-secret"
  sensitive   = true
}

# TLS Listener Configuration
variable "tls_listener_enabled" {
  description = "Enable TLS for proxy listener (clients connect to proxy via TLS)"
  type        = bool
  default     = false
}

variable "tls_listener_cert_secret" {
  description = "Kubernetes secret name containing the proxy listener TLS certificate"
  type        = string
  default     = ""
}

variable "tls_listener_key_secret" {
  description = "Kubernetes secret name containing the proxy listener TLS private key"
  type        = string
  default     = ""
}

variable "tls_listener_ca_secret" {
  description = "Kubernetes secret name containing the proxy listener CA certificate for mutual TLS"
  type        = string
  default     = ""
}

# Vault Configuration
variable "vault_folder" {
  description = "Vault folder for secrets organization. Empty = secret/, 'consumer' = secret/consumer/"
  type        = string
  default     = ""
}

# Charts Path Configuration
variable "charts_path" {
  description = "Path to the charts directory. Use '../../../charts' when running from participant module, '../charts' when running from system-tests"
  type        = string
  default     = "../../../charts"
}

# Self-hosted-specific Configuration

variable "selfhosted_did_url" {
  description = "Custom DID URL for self-hosted environments."
  type        = string
  default     = ""
}

variable "selfhosted_sts_url" {
  description = "Custom STS URL for self-hosted environments."
  type        = string
  default     = ""
}

variable "selfhosted_vault_token_secret_key" {
  description = "Custom vault token secret key for self-hosted environment."
  type        = string
  default     = ""
}

variable "selfhosted_authority_did" {
  description = "Custom authority DID for self-hosted environments."
  type        = string
  default     = ""
}

variable "control_plane_image" {
  description = "Control plane image name for self-hosted environments"
  type        = string
  default     = ""
}

variable "data_plane_image" {
  description = "Data plane image name for self-hosted environments"
  type        = string
  default     = ""
}

variable "identity_hub_image" {
  description = "Identity hub image name for self-hosted environments"
  type        = string
  default     = ""
}

variable "telemetry_agent_image" {
  description = "Telemetry agent image name for self-hosted environments"
  type        = string
  default     = ""
}