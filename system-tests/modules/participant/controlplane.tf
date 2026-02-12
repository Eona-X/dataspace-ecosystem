locals {
  controlplane_release_name = "${var.participant_name}-controlplane"

  ##################
  ## IMPORT NOTE! ##
  ############################################################################################
  # These URLs must be the external routes exposed by the participant over the public internet
  # which, are typically exposed through an API gateway, an external Load Balancer...
  # In the case of this MVD we use internal routes for simplicity, but this should not
  # reproduce in prod-grade deployment as all connectors of a dataspace will not be deployed
  # in the same Kubernetes cluster in the real life
  ############################################################################################
  protocol_url = "http://${local.controlplane_release_name}:8282/api/dsp"

  control_plane_image = (
    var.environment == "local" ? "localhost/control-plane-postgresql-hashicorpvault" :
    var.environment == "devbox" ? "${var.devbox-registry}/control-plane-postgresql-hashicorpvault" :
    var.environment == "selfhosted" ? var.control_plane_image :
    "control-plane-postgresql-hashicorpvault"
  )
}

resource "helm_release" "controlplane" {
  name              = local.controlplane_release_name
  cleanup_on_fail   = true
  dependency_update = true
  recreate_pods     = true
  repository        = var.charts_path
  chart             = "control-plane"
  # version           = "latest"

  values = [
    yamlencode({
      "imagePullSecrets" : var.environment == "devbox" ? [
        {
          "name" : var.devbox-registry-cred
        }
      ] : []
      "controlplane" : {
        "initContainers" : [],
        "image" : {
          "repository" : local.control_plane_image
          "pullPolicy" : local.image_pull_policy
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
        "trustedIssuers" : {
          "authority" : {
            "did" : local.authority_did
          }
        }

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
edc.negotiation.state-machine.iteration-wait-millis=${var.negotiation_state_machine_wait_millis}
edc.transfer.state-machine.iteration-wait-millis=${var.transfer_state_machine_wait_millis}
edc.policy.monitor.state-machine.iteration-wait-millis=${var.policy_monitor_state_machine_wait_millis}
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
              "path" : "${var.participant_with_prefix}/cp/(management)(.*)",
              "pathType" : "ImplementationSpecific"
            },
            {
              "port" : 8282,
              "path" : "${var.participant_with_prefix}/cp/(dsp)(.*)",
              "pathType" : "ImplementationSpecific"
            },
            {
              "port" : 8484,
              "path" : "${var.participant_with_prefix}/cp/(onboarding)(.*)",
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

        "api" : {
          "cors" : {
            "enabled" : true
          }
        }

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

  depends_on = [module.vault, module.db]
}
