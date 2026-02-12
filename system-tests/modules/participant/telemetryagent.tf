locals {
  telemetry_agent_image = (
    var.environment == "local" ? "localhost/telemetry-agent-postgresql-hashicorpvault" :
    var.environment == "devbox" ? "${var.devbox-registry}/telemetry-agent-postgresql-hashicorpvault" :
    var.environment == "selfhosted" ? var.telemetry_agent_image :
    "telemetry-agent-postgresql-hashicorpvault"
  )
  namespace                    = "local-eventhub-eventhubs"
  name                         = "eh1"
  telemetry_agent_release_name = "${var.participant_name}-telemetryagent"
}

##################
## TELEMETRY AGENT ##
##################

resource "helm_release" "telemetryagent" {
  name              = local.telemetry_agent_release_name
  cleanup_on_fail   = true
  dependency_update = true
  recreate_pods     = true
  repository        = var.charts_path
  chart             = "telemetry-agent"
  # version           = "latest"


  values = [
    yamlencode({
      "imagePullSecrets" : var.environment == "devbox" ? [
        {
          "name" : var.devbox-registry-cred
        }
      ] : []
      "telemetryagent" : {
        "initContainers" : [],
        "image" : {
          "repository" : local.telemetry_agent_image
          "tag" : "latest"
          "pullPolicy" : local.image_pull_policy
        },
        "keys" : {
          "sts" : {
            "privateKeyVaultAlias" : local.privatekey_alias,
            "publicKeyVaultAlias" : "${local.did_url}#my-key"
          }
        },
        "did" : {
          "web" : {
            "url" : local.did_url
            "useHttps" : false
          }
        },
        "authority" : {
          "did" : local.authority_did
        }

        "sts" : {
          "tokenUrl" : local.sts_url
          "clientId" : local.did_url,
          "clientSecretAlias" : local.sts_client_secret_alias
        }

        "logging" : <<EOT
        .level=INFO
        org.eclipse.edc.level=ALL
        handlers=java.util.logging.ConsoleHandler
        java.util.logging.ConsoleHandler.formatter=java.util.logging.SimpleFormatter
        java.util.logging.ConsoleHandler.level=ALL
        java.util.logging.SimpleFormatter.format=[%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS] [%4$-7s] %5$s%6$s%n
                EOT

        "config" : <<EOT
edc.vault.hashicorp.token.scheduled-renew-enabled=false
        EOT

        "credentialmanager" : {
          "privatekey" : {
            "alias" : var.participant_name
          }
        }
        "telemetryservice" : {
          "eventhub" : {
            "namespace" : local.namespace,
            "name" : local.name
          }
        }

        "postgresql" : {
          "jdbcUrl" : "jdbc:postgresql://${var.db_server_fqdn}/${local.db_name}",
          "credentials" : {
            "secret" : {
              "name" : kubernetes_secret.db-user-credentials.metadata.0.name
            }
          }
        },
        "ingress" : {
          "enabled" : true
          "className" : "nginx"
          "annotations" : {
            "nginx.ingress.kubernetes.io/ssl-redirect" : "false"
            "nginx.ingress.kubernetes.io/use-regex" : "true"
            "nginx.ingress.kubernetes.io/rewrite-target" : "/api/$1$2"
          },
        }
        "vault" : {
          "hashicorp" : {
            "url" : module.vault.vault_url
            "token" : {
              "secret" : {
                "name" : module.vault.vault_secret_name,
                "tokenKey" : "rootToken"
              }
            }
          }
        }
      }
    })
  ]
  depends_on = [module.vault, module.db]
}
