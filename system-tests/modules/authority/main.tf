locals {
  privatekey_alias = var.authority_name
  publickey_alias  = "${local.privatekey_alias}-pub"

  db_name          = "${var.authority_name}db"
  db_billing_name  = "billingdb"
  db_user          = var.authority_name
  db_billing_user  = "billinguser"
  db_user_password = "${var.authority_name}pwd"

  authority_did    = local.did_url
}
module "db" {
  source = "../db"

  db_name                                = local.db_name
  db_server_fqdn                         = var.db_server_fqdn
  db_user                                = local.db_user
  db_user_password                       = local.db_user_password
  postgres_admin_credentials_secret_name = var.postgres_admin_credentials_secret_name
}

module "billing-db" {
  source = "../db"

  db_name                                = local.db_billing_name
  db_server_fqdn                         = var.db_server_fqdn
  db_user                                = local.db_billing_user
  db_user_password                       = local.db_user_password
  postgres_admin_credentials_secret_name = var.postgres_admin_credentials_secret_name

}
module "eventhub" {
  source                 = "./eventhub"
  account_name_azurite   = var.account_name_azurite
  account_secret_azurite = var.account_secret_azurite
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
resource "kubernetes_secret" "billing-db-user-credentials" {

  metadata {
    name = "${local.db_billing_user}-db-credentials"
  }

  data = {
    "username" = local.db_billing_user
    "password" = local.db_user_password
  }
}

module "vault" {
  source = "../vault"

  participant_name = var.authority_name
}
