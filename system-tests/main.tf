locals {
  participants = [var.provider_name, var.consumer_name]
}

###########################
## TLS CERTIFICATES      ##
###########################

# Generate TLS certificates for consumer kafka-proxy listener
module "consumer_tls_certificates" {
  source = "./modules/tls-certificates"

  secret_name = "consumer-kafka-proxy-listener-tls"
  namespace   = "default"
  common_name = "consumer-kafka-proxy-listener"

  dns_names = [
    "localhost",
    "*.default.svc.cluster.local",
    "consumer-kafkaproxy-kafka-proxy",
    "consumer-kafkaproxy-kafka-proxy.default.svc.cluster.local",
  ]
}

# Generate TLS certificates for provider kafka-proxy listener
module "provider_tls_certificates" {
  source = "./modules/tls-certificates"

  secret_name = "provider-kafka-proxy-listener-tls"
  namespace   = "default"
  common_name = "provider-kafka-proxy-listener"

  dns_names = [
    "localhost",
    "*.default.svc.cluster.local",
    "provider-kafkaproxy-kafka-proxy",
    "provider-kafkaproxy-kafka-proxy.default.svc.cluster.local",
  ]
}

###################
## POSTGRESQL DB ##
###################

module "postgres" {
  source = "./modules/postgres"
}

######################
## PROVIDER BACKEND ##
######################

module "backend-provider" {
  source               = "./modules/backend-service"
  name                 = var.provider_name
  environment          = var.environment
  devbox-registry      = var.devbox-registry
  devbox-registry-cred = var.devbox-registry-cred
}

##################
## PARTICIPANTS ##
##################

module "participant" {
  source = "./modules/participant"

  for_each                               = { for p in local.participants : p => p }
  participant_name                       = each.key
  participant_with_prefix                = "/${each.key}"
  db_server_fqdn                         = module.postgres.postgres_server_fqdn
  postgres_admin_credentials_secret_name = module.postgres.postgres_admin_credentials_secret_name
  environment                            = var.environment
  devbox-registry                        = var.devbox-registry
  devbox-registry-cred                   = var.devbox-registry-cred

  # Authentication Configuration
  auth_enabled      = var.auth_enabled
  auth_mechanism    = var.auth_mechanism
  auth_tenant_id    = var.auth_tenant_id
  auth_client_id    = var.auth_client_id
  auth_static_users = var.auth_static_users

  # TLS Listener Configuration
  tls_listener_enabled     = var.tls_listener_enabled
  tls_listener_cert_secret = each.key == "consumer" ? module.consumer_tls_certificates.secret_name : module.provider_tls_certificates.secret_name
  tls_listener_key_secret  = each.key == "consumer" ? module.consumer_tls_certificates.secret_name : module.provider_tls_certificates.secret_name
  tls_listener_ca_secret   = "" # Empty string disables mutual TLS (client certificate verification)

  # Vault Configuration
  vault_folder = var.vault_folder

  # Charts Path (relative to system-tests)
  charts_path = "../charts"

  depends_on = [
    module.consumer_tls_certificates,
    module.provider_tls_certificates
  ]
}
#
###############
## AUTHORITY ##
###############

module "authority" {
  source = "./modules/authority"

  authority_name                         = var.authority_name
  db_server_fqdn                         = module.postgres.postgres_server_fqdn
  postgres_admin_credentials_secret_name = module.postgres.postgres_admin_credentials_secret_name
  environment                            = var.environment
  account_name_azurite                   = var.account_name_azurite
  account_secret_azurite                 = var.account_secret_azurite
  devbox-registry                        = var.devbox-registry
  devbox-registry-cred                   = var.devbox-registry-cred
}

#####################
## KAFKA BROKER E2E ##
#####################

module "broker" {
  source                 = "./modules/broker"
  environment            = var.environment
  devbox-registry        = var.devbox-registry
  devbox-registry-cred   = var.devbox-registry-cred
}

