locals {
  catalog_filter_release_name = "${var.authority_name}-federatedcatalogfilter"

  federated_catalog_filter_image = (
    var.environment == "local" ? "localhost/federated-catalog-filter-postgresql-hashicorpvault" :
      var.environment == "devbox" ? "${var.devbox-registry}/federated-catalog-filter-postgresql-hashicorpvault" :
      "federated-catalog-filter-postgresql-hashicorpvault"
  )
  filter_url = "http://${local.catalog_filter_release_name}:8383/api/catalogfilter/filter"
}
resource "helm_release" "federated-catalog-filter" {
  name              = local.catalog_filter_release_name
  cleanup_on_fail   = true
  dependency_update = true
  recreate_pods     = true
  repository        = "../charts"
  chart             = "federated-catalog-filter"
# version           = "latest"
  values = [
    yamlencode({
      "imagePullSecrets" : var.environment == "devbox" ? [
        {
          "name" : var.devbox-registry-cred
        }
      ] : []
      "federatedcatalogfilter" : {
        "initContainers" : [],
        "image" : {
          "repository" : local.federated_catalog_filter_image
          "tag" : "latest"
          "pullPolicy" : var.environment == "local" ? "Never" : "IfNotPresent"
        },
        "did" : {
          "web" : {
            "url" : local.did_url,
            "useHttps" : false
          }
        },
        "trustedIssuers" : {
          "authority" : {
            "did" : local.did_url
          }
        }
        #        "logging" : <<EOT
        #.level=INFO
        #org.eclipse.edc.level=ALL
        #handlers=java.util.logging.ConsoleHandler
        #java.util.logging.ConsoleHandler.formatter=java.util.logging.SimpleFormatter
        #java.util.logging.ConsoleHandler.level=ALL
        #java.util.logging.SimpleFormatter.format=[%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS] [%4$-7s] %5$s%6$s%n
        #        EOT

        "config" : <<EOT
edc.iam.trusted-issuer.authority.id=${local.did_url}
edc.vault.hashicorp.token.scheduled-renew-enabled=false
        EOT

        "sts" : {
          "tokenUrl" : local.sts_url,
          "clientId" : local.did_url,
          "clientSecretAlias" : local.sts_client_secret_alias
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
              "port" : 8383,
              "path" : "/${var.authority_name}/(catalogfilter)(.*)",
              "pathType" : "ImplementationSpecific"
            }
          ]
        }
        "api" : {
          "cors" : {
            "enabled" : true
          }
        },
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
  depends_on = [module.vault]
}