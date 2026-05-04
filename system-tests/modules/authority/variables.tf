variable "authority_name" {}

variable "db_server_fqdn" {}

variable "postgres_admin_credentials_secret_name" {
  description = "(Required) Secret containing the DB Admin credentials"
}

variable "environment" {
  description = "The environment (local or production)"
  type        = string
}

variable "account_secret_azurite" {
  description = "Account secret for event hub"
  sensitive   = true
}

variable "account_name_azurite" {
  description = "Account name for event hub"
}

variable "devbox-registry" {
  description = "The container registry for devbox environment"
  type        = string
}

variable "devbox-registry-cred" {
  description = "The image pull secret name for devbox environment"
  type        = string
}

variable "telemetry_credential_signer_alias" {
  description = "Alias of the private key in Vault used for signing telemetry OIDC tokens"
  type        = string
  default     = "authority"
}

variable "telemetry_credential_public_key_alias" {
  description = "Alias of the public key in Vault used for JWKS exposure"
  type        = string
  default     = "authority-pub"
}
