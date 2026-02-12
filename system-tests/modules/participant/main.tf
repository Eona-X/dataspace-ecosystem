locals {
  privatekey_alias = var.participant_name
  publickey_alias  = "${local.privatekey_alias}-pub"

  db_name          = "${var.participant_name}db"
  db_user          = var.participant_name
  db_user_password = "${var.participant_name}pwd"

  authority_did = var.selfhosted_authority_did != "" ? var.selfhosted_authority_did : replace(local.did_url, var.participant_name, "authority")
  
  image_pull_policy = var.environment == "local" || var.environment == "selfhosted" ? "Never" : "IfNotPresent"
}

module "db" {
  source = "../db"

  db_name                                = local.db_name
  db_server_fqdn                         = var.db_server_fqdn
  db_user                                = local.db_user
  db_user_password                       = local.db_user_password
  postgres_admin_credentials_secret_name = var.postgres_admin_credentials_secret_name
}

resource "kubernetes_secret" "db-user-credentials" {

  metadata {
    name = "${local.db_user}-db-credentials"
  }

  data = {
    "username" = local.db_user
    "password" = local.db_user_password
  }
}


module "vault" {
  source = "../vault"

  participant_name = var.participant_name
}