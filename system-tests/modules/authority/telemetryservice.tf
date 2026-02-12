locals {
  telemetryservice_release_name = "${var.authority_name}-telemetryservice"
  connection_string_alias       = "event-hub-connection-string"
  telemetry_service_image = (
    var.environment == "local" ? "localhost/telemetry-service-postgresql-hashicorpvault" :
    var.environment == "devbox" ? "${var.devbox-registry}/telemetry-service-postgresql-hashicorpvault" :
    "telemetry-service-postgresql-hashicorpvault"
  )

  credential_url = "http://${local.telemetryservice_release_name}:8181/api/credential"
}

resource "helm_release" "telemetryservice" {
  name              = local.telemetryservice_release_name
  cleanup_on_fail   = true
  dependency_update = true
  recreate_pods     = true
  repository        = "../charts"
  chart             = "telemetry-service"
  # version           = "latest"

  values = [
    yamlencode({
      "imagePullSecrets" : var.environment == "devbox" ? [
        {
          "name" : var.devbox-registry-cred
        }
      ] : []
      "telemetryservice" : {
        "initContainers" : [],
        "image" : {
          "repository" : local.telemetry_service_image
          "pullPolicy" : var.environment == "local" ? "Never" : "IfNotPresent"
          "tag" : "latest"
        },
        "sts" : {
          "tokenUrl" : local.sts_url
          "clientId" : local.did_url,
          "clientSecretAlias" : local.sts_client_secret_alias
        }
        "did" : {
          "web" : {
            "url" : local.did_url
            "useHttps" : false
          }
        },

        "logging" : <<EOT
        .level=DEBUG
        org.eclipse.edc.level=ALL
        handlers=java.util.logging.ConsoleHandler
        java.util.logging.ConsoleHandler.formatter=java.util.logging.SimpleFormatter
        java.util.logging.ConsoleHandler.level=ALL
        java.util.logging.SimpleFormatter.format=[%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS] [%4$-7s] %5$s%6$s%n
               EOT

        "config" : <<EOT
edc.iam.trusted-issuer.authority.id=${local.authority_did}
edc.vault.hashicorp.token.scheduled-renew-enabled=false
        EOT

        "credentialfactory" : {
          "azure" : {
            "eventhub" : {
              "connectionstring" : {
                "alias" : local.connection_string_alias
              }
            }
          }
        }

        "ingress" : {
          "enabled" : true
          "className" : "nginx"
          "annotations" : {
            "nginx.ingress.kubernetes.io/ssl-redirect" : "false"
            "nginx.ingress.kubernetes.io/use-regex" : "true"
            "nginx.ingress.kubernetes.io/rewrite-target" : "/api/$1$2"
          },
          "endpoints" : [
            {
              "port" : 8181,
              "path" : "/${var.authority_name}/ts/(credential)(.*)",
              "pathType" : "ImplementationSpecific"
            }
          ]
        },
        "postgresql" : {
          "jdbcUrl" : "jdbc:postgresql://${var.db_server_fqdn}/${local.db_name}",
          "credentials" : {
            "secret" : {
              "name" : kubernetes_secret.db-user-credentials.metadata.0.name
            }
          }
        },
        "vault" : {
          "hashicorp" : {
            "url" : module.vault.vault_url
            "token" : {
              "secret" : {
                "name" : module.vault.vault_secret_name
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
