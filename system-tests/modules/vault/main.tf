locals {
  vault_token = "root"
  vault_port  = 8200
}

########################################
## HASHICORP VAULT + KEY PAIR SEEDING ##
########################################
resource "helm_release" "vault" {
  repository        = "https://helm.releases.hashicorp.com"
  chart             = "vault"
  name              = "${var.participant_name}-vault"
  wait_for_jobs     = true
  version           = "v0.28.1"
  dependency_update = true

  values = [
    yamlencode({
      "injector" : {
        "enabled" : false
      }
      "server" : {
        "dev" : {
          "enabled" : true,
          "devRootToken" : local.vault_token
        },
        "readinessProbe" : {
          "path" : "/v1/sys/health"
        }
      }
    })
  ]
}

#######################
## KUBERNETES SECRET ##
#######################

resource "kubernetes_secret" "vault-secret" {
  metadata {
    name = "${var.participant_name}-vault"
  }

  data = {
    rootToken = local.vault_token
  }
}

####################
### VAULT INGRESS ##
####################

resource "kubernetes_ingress_v1" "vault-ingress" {
  metadata {
    name = "${var.participant_name}-vault-ingress"
    annotations = {
      "nginx.ingress.kubernetes.io/rewrite-target" = "/$2"
      "nginx.ingress.kubernetes.io/use-regex"      = "true"
    }
  }
  spec {
    ingress_class_name = "nginx"
    rule {
      http {
        path {
          path = "/${var.participant_name}/vault(/|$)(.*)"
          backend {
            service {
              name = helm_release.vault.metadata.name
              port {
                number = local.vault_port
              }
            }
          }
        }
      }
    }
  }
}

resource "tls_private_key" "key-pair" {
  algorithm   = "ECDSA"
  ecdsa_curve = "P256"
}

###################
## SEED KEY PAIR ##
###################

resource "kubernetes_job" "vault-keygen-job" {
  metadata {
    name = "${var.participant_name}-vault-keygen-job"
    annotations = {
      "helm.sh/hook"               = "post-install,post-upgrade"
      "helm.sh/hook-weight"        = "5"
      "helm.sh/hook-delete-policy" = "hook-succeeded"
    }
  }

  timeouts {
    delete = "15m"
    create = "20m"
    update = "15m"
  }

  spec {
    ttl_seconds_after_finished = 3600
    backoff_limit              = 2
    template {
      metadata {
        name = "${var.participant_name}-vault-keygen-job"
        labels = {
          app = "${var.participant_name}-vault-keygen-job"
        }
      }
      spec {
        restart_policy = "Never"
        container {
          name  = "keygen"
          image = "hashicorp/vault"
          command = [
            "sh",
            "-c",
            <<-EOF
              echo "${tls_private_key.key-pair.public_key_pem}" > /tmp/publickey.pem
              echo "${tls_private_key.key-pair.private_key_pem}" > /tmp/privatekey.pem

              vault kv put secret/${var.participant_name} content="$(cat /tmp/privatekey.pem)"
              vault kv put secret/${var.participant_name}-pub content="$(cat /tmp/publickey.pem)"
            EOF
          ]
          env {
            name  = "VAULT_ADDR"
            value = "http://${var.participant_name}-vault:${local.vault_port}"
          }
          env {
            name  = "VAULT_TOKEN"
            value = local.vault_token
          }
        }
      }
    }
  }
  depends_on          = [helm_release.vault]
  wait_for_completion = true
}


