locals {
  kafkaproxy_release_name = "${var.participant_name}-kafka-proxy-k8s-manager"
  kafka_proxy_image = (
    var.environment == "local" ? "localhost/kafka-proxy-k8s-manager" :
    var.environment == "devbox" ? "${var.devbox-registry}/kafka-proxy-k8s-manager" :
    "kafka-proxy-k8s-manager"
  )

  kafka_plugin_image = (
    var.environment == "local" ? "localhost/kafka-proxy-entra-auth:latest" :
    var.environment == "devbox" ? "${var.devbox-registry}/kafka-proxy-entra-auth:latest" :
    "kafka-proxy-entra-auth:latest"
  )
}

resource "helm_release" "kafkaproxy" {
  count             = var.environment == "selfhosted" ? 0 : 1
  name              = local.kafkaproxy_release_name
  cleanup_on_fail   = true
  dependency_update = true
  recreate_pods     = true
  repository        = var.charts_path
  chart             = "kafka-proxy-k8s-manager"
  # version           = "latest"

  values = [
    yamlencode({
      "global" : {
        "imagePullSecrets" : var.environment == "devbox" ? [
          {
            "name" : var.devbox-registry-cred
          }
        ] : []
      }
      "kafkaProxy" : {
        "manager" : {
          "image" : {
            "repository" : local.kafka_proxy_image
            "pullPolicy" : local.image_pull_policy
            "tag" : "latest"
          }
          "initContainers" : []
          # Vault configuration
          "vaultAddr" : module.vault.vault_url
          "vaultTokenSecret" : {
            "name" : module.vault.vault_secret_name
            "key" : "rootToken"
          }
          "vaultFolder" : var.vault_folder
          "vaultTls" : {
            "enabled" : false
          }

          # Kubernetes configuration
          "namespace" : "default"
          "proxyImage" : "grepplabs/kafka-proxy:0.4.2"
          "sharedDir" : "/shared"
          "checkInterval" : 5 # Reduced for faster test execution
          "enableLock" : true
          "lockFilePath" : "/tmp/kubectl-deployer.lock"

          # Participant configuration for ownership isolation
          "participantId" : "${var.participant_name}"

          # Downstream Authentication Configuration
          "auth" : {
            "enabled" : var.auth_enabled
            "mechanism" : var.auth_mechanism
            "tenantId" : var.auth_tenant_id
            "clientId" : var.auth_client_id
            "staticUsers" : var.auth_static_users
            "image" : local.kafka_plugin_image
          }

          # TLS Listener Configuration
          "tls" : {
            "listener" : {
              "enabled" : var.tls_listener_enabled
              "certSecret" : var.tls_listener_cert_secret
              "keySecret" : var.tls_listener_key_secret
              "caSecret" : var.tls_listener_ca_secret
            }
          }
        }
      },

      "ingress" : {
        "enabled" : true
        "className" : "nginx"
        "annotations" : {
          "nginx.ingress.kubernetes.io/ssl-redirect" : "false"
          "nginx.ingress.kubernetes.io/use-regex" : "true"
          "nginx.ingress.kubernetes.io/rewrite-target" : "/api/$1"
        },
        "hosts" : [
          {
            "host" : ""
            "paths" : [
              {
                "path" : "${var.participant_with_prefix}/kafkaproxy/(.*)",
                "pathType" : "ImplementationSpecific"
              }
            ]
          }
        ]
      },

      # Service Account and RBAC configuration
      "serviceAccount" : {
        "create" : true
        "name" : "${var.participant_name}-kafkaproxy-sa"
      },

      "rbac" : {
        "create" : true
        "rules" : [
          {
            "apiGroups" : [""]
            "resources" : ["services", "pods", "configmaps", "secrets"]
            "verbs" : ["get", "list", "watch", "create", "update", "patch", "delete"]
          },
          {
            "apiGroups" : ["apps"]
            "resources" : ["deployments", "replicasets"]
            "verbs" : ["get", "list", "watch", "create", "update", "patch", "delete"]
          },
          {
            "apiGroups" : ["networking.k8s.io"]
            "resources" : ["networkpolicies"]
            "verbs" : ["get", "list", "watch", "create", "update", "patch", "delete"]
          }
        ]
      },

      # Persistence configuration
      "persistence" : {
        "shared" : {
          "enabled" : true
          "storageClass" : ""
          "accessModes" : ["ReadWriteOnce"]
          "size" : "1Gi"
        }
      },

      # Health checks - Temporarily disabled to debug endpoint
      "healthCheck" : {
        "enabled" : false
      }
    })
  ]

  depends_on = [module.vault]
}

# Additional ingress for management endpoint
resource "kubernetes_ingress_v1" "kafkaproxy-management-ingress" {
  count = var.environment == "selfhosted" ? 0 : 1
  metadata {
    name = "${var.participant_name}-kafkaproxy-management-ingress"
    annotations = {
      "nginx.ingress.kubernetes.io/ssl-redirect" : "false"
      "nginx.ingress.kubernetes.io/use-regex" : "true"
      "nginx.ingress.kubernetes.io/rewrite-target" : "/management/$1"
    }
  }

  spec {
    ingress_class_name = "nginx"
    rule {
      http {
        path {
          path      = "${var.participant_with_prefix}/kafkaproxy/management/(.*)"
          path_type = "ImplementationSpecific"
          backend {
            service {
              name = local.kafkaproxy_release_name
              port {
                number = 8081
              }
            }
          }
        }
      }
    }
  }

  depends_on = [helm_release.kafkaproxy[0]]
}