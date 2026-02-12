locals {
  dataplane_release_name = "${var.participant_name}-dataplane"

  dpf_selector_url = "http://${local.controlplane_release_name}:8383/api/control/v1/dataplanes"


  ##################
  ## IMPORT NOTE! ##
  ############################################################################################
  # These URLs must be the external routes exposed by the participant over the public internet
  # which, are typically exposed through an API gateway, an external Load Balancer...
  # In the case of this MVD we use internal routes for simplicity, but this should not
  # reproduce in prod-grade deployment as all connectors of a dataspace will not be deployed
  # in the same Kubernetes cluster in the real life
  ############################################################################################
  public_url = "http://${local.dataplane_release_name}:8181/api/public/"
  data_plane_image = (
    var.environment == "local" ? "localhost/data-plane-postgresql-hashicorpvault" :
    var.environment == "devbox" ? "${var.devbox-registry}/data-plane-postgresql-hashicorpvault" :
    var.environment == "selfhosted" ? var.data_plane_image :
    "data-plane-postgresql-hashicorpvault"
  )
}

resource "helm_release" "dataplane" {
  name              = local.dataplane_release_name
  cleanup_on_fail   = true
  dependency_update = true
  recreate_pods     = true
  repository        = var.charts_path
  chart             = "data-plane"
  # version           = "latest"

  values = [
    yamlencode({
      "imagePullSecrets" : var.environment == "devbox" ? [
        {
          "name" : var.devbox-registry-cred
        }
      ] : []
      "dataplane" : {
        "initContainers" : [],
        "image" : {
          "repository" : local.data_plane_image
          "pullPolicy" : local.image_pull_policy
          "tag" : "latest"
        },
        "did" : {
          "web" : {
            "url" : local.did_url,
            "useHttps" : false
          }
        },
        "keys" : {
          // use the same key pair for simplicity
          "dataplane" : {
            "privateKeyVaultAlias" : local.privatekey_alias,
            "publicKeyVaultAlias" : local.publickey_alias
          }
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
edc.dataplane.state-machine.iteration-wait-millis=${var.data_plane_state_machine_wait_millis}
        EOT
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
              "path" : "${var.participant_with_prefix}/dp/(public)(.*)",
              "pathType" : "ImplementationSpecific"
            },
            {
              "port" : 8282,
              "path" : "${var.participant_with_prefix}/dp/(data)(.*)",
              "pathType" : "ImplementationSpecific"
            }
          ]
        },

        "selector" : {
          "url" : local.dpf_selector_url
        }

        "url" : {
          "public" : local.public_url
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
                "name" : module.vault.vault_secret_name,
                "tokenKey" : var.selfhosted_vault_token_secret_key != "" ? var.selfhosted_vault_token_secret_key : "rootToken"
              }
            }
          }
        }
      }
    })
  ]

  depends_on = [module.vault, module.db, helm_release.controlplane]
}
