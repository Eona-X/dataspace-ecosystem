locals {
  telemetrycsvmanager_release_name = "${var.authority_name}-telemetrycsvmanager"
  telemetry_csv_manager_image = (
    var.environment == "local" ? "localhost/telemetry-csv-manager-postgresql-hashicorpvault" :
    var.environment == "devbox" ? "${var.devbox-registry}/telemetry-csv-manager-postgresql-hashicorpvault" :
     "telemetry-csv-manager-postgresql-hashicorpvault"
  )
}

resource "helm_release" "telemetrycsvmanager" {
  name              = local.telemetrycsvmanager_release_name
  cleanup_on_fail   = true
  dependency_update = true
  recreate_pods     = true
  repository        = "../charts"
  chart             = "telemetry-csv-manager"
  # version           = "latest"

  values = [
    yamlencode({
      "imagePullSecrets" : var.environment == "devbox" ? [
        {
          "name" : var.devbox-registry-cred
        }
      ] : []
      "telemetrycsvmanager" : {
        "image" : {
          "repository" : local.telemetry_csv_manager_image
          "pullPolicy" : var.environment == "local" ? "Never" : "IfNotPresent"
          "tag" : "latest"
        },
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
web.http.telemetrycsvmanager.port=8181
web.http.telemetrycsvmanager.path=/telemetrycsvmanager
edc.vault.hashicorp.token.scheduled-renew-enabled=false
        EOT

        "objectStorageType" : "s3"
        "s3" : {
          "endpoint" : "http://minio-report-storage:9000"
          "bucketName" : "reports"
          "region" : "us-east-1"
          "credentials" : {
            "secret" : {
              "name" : "minio-credentials"
              "accessKey" : "access-key"
              "secretKey" : "secret-key"
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
              "port" : 8080,
              "path" : "/${var.authority_name}/(billing-reports)(.*)",
              "pathType" : "ImplementationSpecific"
            }
          ]
        },
        "postgresql" : {
          "jdbcUrl" : "jdbc:postgresql://${var.db_server_fqdn}/${local.db_billing_name}",
          "credentials" : {
            "secret" : {
              "name" : kubernetes_secret.billing-db-user-credentials.metadata.0.name
            }
          }
        },
        "vault" : {
          "hashicorp" : {
            "url" : module.vault.vault_url
            "cert" : {
              "secretName" : "proxy-provider-tls-ca"
            }
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