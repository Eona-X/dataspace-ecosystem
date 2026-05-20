variable "authority_name" {}

variable "db_server_fqdn" {}

variable "postgres_admin_credentials_secret_name" {
  description = "(Required) Secret containing the DB Admin credentials"
}

variable "environment" {
  description = "The environment (local or production)"
  type        = string
}


variable "minio_access_key" {
  description = "Access key for MinIO"
}

variable "minio_secret_key" {
  description = "Secret key for MinIO"
  sensitive   = true
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

variable "kafka_bootstrap_servers" {
  description = "Kafka bootstrap servers for the telemetry agent"
  type        = string
  default     = "broker.default.svc.cluster.local:9092"
}

variable "kafka_topic" {
  description = "Kafka topic for telemetry events"
  type        = string
  default     = "telemetry"
}

variable "kafka_group_id" {
  description = "Kafka group ID for the telemetry storage"
  type        = string
  default     = "telemetry-storage-group"
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
