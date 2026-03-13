locals {
  participants = [var.provider_name, var.consumer_name]
}

###########################
## TLS CERTIFICATES      ##
###########################

# Generate TLS certificates for kafka-proxy listeners
module "participant_tls_certificates" {
  source = "./modules/tls-certificates"

  for_each    = { for p in local.participants : p => p }
  secret_name = "${each.key}-kafka-proxy-listener-tls"
  namespace   = "default"
  common_name = "${each.key}-kafka-proxy-listener"

  dns_names = [
    "localhost",
    "*.default.svc.cluster.local",
    "${each.key}-kafkaproxy-kafka-proxy",
    "${each.key}-kafkaproxy-kafka-proxy.default.svc.cluster.local",
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
  auth_static_users = var.auth_static_users

  # OIDC Configuration
  keycloak_base_url        = var.keycloak_base_url
  keycloak_realm           = var.keycloak_realm
  verifier_client_id       = var.verifier_client_id
  verifier_required_scopes = var.verifier_required_scopes
  provider_client_id       = var.provider_client_id
  provider_client_secret   = var.provider_client_secret
  provider_scope           = var.provider_scope

  # TLS Listener Configuration
  tls_listener_enabled     = var.tls_listener_enabled
  tls_listener_cert_secret = module.participant_tls_certificates[each.key].secret_name
  tls_listener_key_secret  = module.participant_tls_certificates[each.key].secret_name
  tls_listener_ca_secret   = "" # Empty string disables mutual TLS (client certificate verification)

  # Vault Configuration
  vault_folder = var.vault_folder

  # Charts Path (relative to system-tests)
  charts_path = "../charts"

  depends_on = [
    module.participant_tls_certificates
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

######################
## KAFKA BROKER E2E ##
######################

module "broker" {
  source                 = "./modules/broker"
  environment            = var.environment
  participants           = local.participants # FIXME: for_each
  devbox-registry        = var.devbox-registry
  devbox-registry-cred   = var.devbox-registry-cred

  # OIDC Configuration
  keycloak_base_url       = var.keycloak_base_url
  keycloak_realm          = var.keycloak_realm
  verifier_client_id      = var.verifier_client_id
  provider_client_id      = var.provider_client_id
  provider_scope          = var.provider_scope
  provider_auth_mechanism = var.provider_auth_mechanism
}

