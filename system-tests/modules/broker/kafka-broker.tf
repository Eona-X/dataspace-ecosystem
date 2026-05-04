# Kafka Broker module for system tests (SASL-PLAINTEXT without TLS)

locals {
  participants = toset(var.participants)
  common_labels = {
    "app.kubernetes.io/name"    = "kafka-e2e-test"
    "app.kubernetes.io/part-of" = "dataspace-ecosystem"
  }

  kafka_proxy_image = (
    var.environment == "local" ? "localhost/kafka-proxy-oidc-auth:latest" :
      var.environment == "devbox" ? "${var.devbox-registry}/kafka-proxy-oidc-auth:latest" :
      "kafka-proxy-oidc-auth:latest"
  )

  # OIDC Configuration
  keycloak_issuer  = "${var.keycloak_base_url}/realms/${var.keycloak_realm}"
  keycloak_jwks    = "${local.keycloak_issuer}/protocol/openid-connect/certs"

  allowed_issuers = local.keycloak_issuer
  # Allow localhost for local tests if needed, but primary is the external IP
}

resource "kubernetes_deployment" "kafka_broker" {
  for_each = local.participants
  metadata {
    name = "${each.value}-broker"
    labels = merge(local.common_labels, {
      "app" = "${each.value}-broker"
    })
  }

  spec {
    replicas = 1

    selector {
      match_labels = {
        app = "${each.value}-broker"
      }
    }

    template {
      metadata {
        labels = {
          app = "${each.value}-broker"
        }
      }

      spec {
        container {
          name  = "broker"
          image = "apache/kafka:4.0.0"

          port {
            container_port = 9092
            name           = "kafka-plain"
          }
          port {
            container_port = 19093
            name           = "controller"
          }

          env {
            name  = "KAFKA_NODE_ID"
            value = "1"
          }
          env {
            name  = "KAFKA_PROCESS_ROLES"
            value = "broker,controller"
          }
          env {
            name  = "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP"
            value = "PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT"
          }
          env {
            name  = "KAFKA_ADVERTISED_LISTENERS"
            value = "PLAINTEXT://${each.value}-broker:9092"
          }
          env {
            name = "KAFKA_LISTENERS"
            value = "PLAINTEXT://:9092,CONTROLLER://:19093"
          }
          env {
            name  = "KAFKA_CONTROLLER_LISTENER_NAMES"
            value = "CONTROLLER"
          }
          env {
            name  = "KAFKA_INTER_BROKER_LISTENER_NAME"
            value = "PLAINTEXT"
          }
          env {
            name  = "KAFKA_CONTROLLER_QUORUM_VOTERS"
            value = "1@localhost:19093"
          }
          env {
            name  = "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR"
            value = "1"
          }
          env {
            name  = "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR"
            value = "1"
          }
          env {
            name  = "KAFKA_TRANSACTION_STATE_LOG_MIN_ISR"
            value = "1"
          }
          env {
            name  = "KAFKA_LOG_DIRS"
            value = "/tmp/kraft-combined-logs"
          }
          env {
            name  = "CLUSTER_ID"
            value = "cluster-${each.value}"
          }
          env {
            name  = "KAFKA_BROKER_ID"
            value = "1"
          }
          env {
            name  = "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR"
            value = "1"
          }
          env {
            name  = "KAFKA_DEFAULT_REPLICATION_FACTOR"
            value = "1"
          }
          env {
            name  = "KAFKA_MIN_INSYNC_REPLICAS"
            value = "1"
          }
          env {
            name  = "KAFKA_TRANSACTION_STATE_LOG_MIN_ISR"
            value = "1"
          }
          env {
            name  = "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR"
            value = "1"
          }
          env {
            name  = "KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS"
            value = "0"
          }

          volume_mount {
            name       = "kafka-logs"
            mount_path = "/tmp/kraft-combined-logs"
          }

          resources {
            requests = {
              memory = "512Mi"
              cpu    = "250m"
            }
            limits = {
              memory = "1Gi"
              cpu    = "500m"
            }
          }

          liveness_probe {
            tcp_socket {
              port = 9092
            }
            initial_delay_seconds = 60
            period_seconds        = 10
            timeout_seconds       = 5
            failure_threshold     = 3
          }

          readiness_probe {
            tcp_socket {
              port = 9092
            }
            initial_delay_seconds = 30
            period_seconds        = 10
            timeout_seconds       = 5
            failure_threshold     = 3
          }
        }

        volume {
          name = "kafka-logs"
          empty_dir {}
        }
      }
    }
  }
}

resource "kubernetes_service" "kafka_broker" {
  for_each = local.participants
  metadata {
    name = "${each.value}-broker"
    labels = merge(local.common_labels, {
      "app" = "${each.value}-broker"
    })
  }

  spec {
    type = "ClusterIP"

    port {
      port        = 9092
      target_port = 9092
      protocol    = "TCP"
      name        = "kafka-plain"
    }
    port {
      port        = 19093
      target_port = 19093
      protocol    = "TCP"
      name        = "controller"
    }

    selector = {
      app = "${each.value}-broker"
    }
  }
}

# Create self-signed TLS certificates for proxy provider
resource "tls_private_key" "proxy_provider_ca" {
  for_each  = local.participants
  algorithm = "RSA"
  rsa_bits  = 2048
}

resource "tls_self_signed_cert" "proxy_provider_ca" {
  for_each        = local.participants
  private_key_pem = tls_private_key.proxy_provider_ca[each.key].private_key_pem

  subject {
    common_name  = "Kafka Proxy ${each.value} CA"
    organization = "Dataspace Ecosystem Test"
  }

  validity_period_hours = 8760 # 1 year

  is_ca_certificate = true

  allowed_uses = [
    "key_encipherment",
    "digital_signature",
    "cert_signing",
  ]
}

resource "tls_private_key" "proxy_provider_server" {
  for_each  = local.participants
  algorithm = "RSA"
  rsa_bits  = 2048
}

resource "tls_cert_request" "proxy_provider_server" {
  for_each        = local.participants
  private_key_pem = tls_private_key.proxy_provider_server[each.key].private_key_pem

  subject {
    common_name  = "proxy-${each.value}"
    organization = "Dataspace Ecosystem Test"
  }

  dns_names = [
    "proxy-${each.value}",
    "proxy-${each.value}.default",
    "proxy-${each.value}.default.svc",
    "proxy-${each.value}.default.svc.cluster.local",
    "proxy-${each.value}-oauth2",
    "proxy-${each.value}-oauth2.default",
    "proxy-${each.value}-oauth2.default.svc",
    "proxy-${each.value}-oauth2.default.svc.cluster.local",
    "localhost"
  ]

  ip_addresses = [
    "127.0.0.1"
  ]
}

resource "tls_locally_signed_cert" "proxy_provider_server" {
  for_each           = local.participants
  cert_request_pem   = tls_cert_request.proxy_provider_server[each.key].cert_request_pem
  ca_private_key_pem = tls_private_key.proxy_provider_ca[each.key].private_key_pem
  ca_cert_pem        = tls_self_signed_cert.proxy_provider_ca[each.key].cert_pem

  validity_period_hours = 8760 # 1 year

  allowed_uses = [
    "key_encipherment",
    "digital_signature",
    "server_auth",
  ]
}

# Create Kubernetes secrets for TLS certificates
resource "kubernetes_secret" "proxy_provider_tls_ca" {
  for_each = local.participants
  metadata {
    name   = "proxy-${each.value}-tls-ca"
    labels = local.common_labels
  }

  data = {
    "ca.crt" = tls_self_signed_cert.proxy_provider_ca[each.key].cert_pem
  }

  type = "Opaque"
}

resource "kubernetes_secret" "proxy_provider_tls_server" {
  for_each = local.participants
  metadata {
    name   = "proxy-${each.value}-tls-server"
    labels = local.common_labels
  }

  data = {
    "tls.crt" = tls_locally_signed_cert.proxy_provider_server[each.key].cert_pem
    "tls.key" = tls_private_key.proxy_provider_server[each.key].private_key_pem
  }

  type = "kubernetes.io/tls"
}

# Create ConfigMap for CA certificate (for easy access by clients)
resource "kubernetes_config_map" "proxy_provider_tls_ca" {
  for_each = local.participants
  metadata {
    name   = "proxy-${each.value}-tls-ca"
    labels = local.common_labels
  }

  data = {
    "ca.crt" = tls_self_signed_cert.proxy_provider_ca[each.key].cert_pem
  }
}

# Deploy Proxy Provider (with TLS)
resource "kubernetes_deployment" "proxy_provider" {
  for_each = local.participants
  metadata {
    name = "kafka-proxy-${each.value}"
    labels = merge(local.common_labels, {
      "app" = "kafka-proxy-${each.value}"
    })
  }

  spec {
    replicas = 1

    selector {
      match_labels = {
        app = "kafka-proxy-${each.value}"
      }
    }

    template {
      metadata {
        labels = {
          app = "kafka-proxy-${each.value}"
        }
      }

      spec {
        dynamic "image_pull_secrets" {
          for_each = var.environment == "devbox" && var.devbox-registry-cred != "" ? [1] : []
          content {
            name = var.devbox-registry-cred
          }
        }

        container {
          name  = "kafka-proxy-provider"
          image = local.kafka_proxy_image
          image_pull_policy = var.environment == "local" ? "Never" : "IfNotPresent"

          port {
            container_port = 30001
            name           = "proxy-port"
          }
          port {
            container_port = 30002
            name           = "proxy-tls-port"
          }

          args = [
            "server",
            # Main bootstrap server mapping (TLS)
            "--bootstrap-server-mapping=${each.value}-broker:9092,0.0.0.0:30001,proxy-${each.value}:30001",
            "--debug-enable",
            "--dynamic-advertised-listener=proxy-${each.value}:30001",
            "--auth-local-enable=true",
            "--auth-local-mechanism=PLAIN",
            "--auth-local-param=--jwks-url=http://authority-telemetryservice:8181/api/credential/v1alpha/jwks.json",
            "--auth-local-command=/usr/local/bin/oidc-token-verifier",
            "--auth-local-param=--client-id=${var.verifier_client_id},${var.provider_client_id},account",
            "--auth-local-param=--static-user=${each.value}:secret1",
            "--auth-local-param=--static-user=admin:admin-secret",
            "--auth-local-param=--debug",
            # TLS listener configuration
            "--proxy-listener-tls-enable",
            "--proxy-listener-cert-file=/etc/tls/server/tls.crt",
            "--proxy-listener-key-file=/etc/tls/server/tls.key",
          ]

          env {
            name  = "VERIFIER_JWKS_URL"
            value = local.keycloak_jwks
          }
          env {
            name  = "VERIFIER_ALLOWED_ISSUERS"
            value = local.allowed_issuers
          }
          env {
            name  = "VERIFIER_CLIENT_ID"
            value = var.verifier_client_id
          }

          volume_mount {
            name       = "tls-ca"
            mount_path = "/etc/tls/ca"
            read_only  = true
          }

          volume_mount {
            name       = "tls-server"
            mount_path = "/etc/tls/server"
            read_only  = true
          }

          resources {
            requests = {
              memory = "128Mi"
              cpu    = "100m"
            }
            limits = {
              memory = "256Mi"
              cpu    = "200m"
            }
          }

          liveness_probe {
            tcp_socket {
              port = 30001
            }
            initial_delay_seconds = 30
            period_seconds        = 10
          }

          readiness_probe {
            tcp_socket {
              port = 30001
            }
            initial_delay_seconds = 5
            period_seconds        = 5
          }
        }

        volume {
          name = "tls-ca"
          secret {
            secret_name = kubernetes_secret.proxy_provider_tls_ca[each.key].metadata[0].name
          }
        }

        volume {
          name = "tls-server"
          secret {
            secret_name = kubernetes_secret.proxy_provider_tls_server[each.key].metadata[0].name
          }
        }
      }
    }
  }

  depends_on = [kubernetes_deployment.kafka_broker]
}

# Deploy Kafkacat for testing connectivity
resource "kubernetes_deployment" "kafkacat" {
  for_each = local.participants
  metadata {
    name = "kafkacat-${each.value}"
    labels = merge(local.common_labels, {
      "app" = "kafkacat-${each.value}"
    })
  }

  spec {
    replicas = 1

    selector {
      match_labels = {
        app = "kafkacat-${each.value}"
      }
    }

    template {
      metadata {
        labels = {
          app = "kafkacat-${each.value}"
        }
      }

      spec {
        container {
          name  = "kafkacat"
          image = "edenhill/kcat:1.7.1"

          # Keep container running for interactive testing
          command = ["/bin/sh"]
          args    = ["-c", "while true; do sleep 30; done"]

          env {
            name  = "KAFKA_BROKER_HOST"
            value = "${each.value}-broker"
          }
          env {
            name  = "KAFKA_BROKER_PORT"
            value = "9092"
          }
          env {
            name  = "PROXY_HOST"
            value = "proxy-${each.value}"
          }
          env {
            name  = "PROXY_PORT"
            value = "30001"
          }

          volume_mount {
            name       = "kafka-ca-cert"
            mount_path = "/etc/kafka"
            read_only  = true
          }

          resources {
            requests = {
              memory = "64Mi"
              cpu    = "50m"
            }
            limits = {
              memory = "128Mi"
              cpu    = "100m"
            }
          }
        }

        volume {
          name = "kafka-ca-cert"
          secret {
            secret_name = kubernetes_secret.proxy_provider_tls_ca[each.key].metadata[0].name
            items {
              key  = "ca.crt"
              path = "ca.crt"
            }
          }
        }
      }
    }
  }

  depends_on = [kubernetes_deployment.kafka_broker, kubernetes_deployment.proxy_provider]
}

resource "kubernetes_service" "proxy_provider" {
  for_each = local.participants
  metadata {
    name = "proxy-${each.value}"
    labels = merge(local.common_labels, {
      "app" = "kafka-proxy-${each.value}"
    })
  }

  spec {
    type = "ClusterIP"

    port {
      port        = 30001
      target_port = 30001
      protocol    = "TCP"
      name        = "proxy-port"
    }
    port {
      port        = 30002
      target_port = 30002
      protocol    = "TCP"
      name        = "proxy-tls-port"
    }

    selector = {
      app = "kafka-proxy-${each.value}"
    }
  }
}

# Create OAuth2 validation credentials secret (verifier only needs tenant_id and client_id)
resource "kubernetes_secret" "oauth2_validation_credentials" {
  for_each = local.participants
  metadata {
    name   = "oauth2-validation-credentials-${each.value}"
    labels = local.common_labels
  }

  data = {
    client_id = "<your-broker-client-id>"
    tenant_id = "<your-broker-tenant-id>"
  }

  type = "Opaque"
}

# Deploy Proxy Provider with OAuth2 Validation (port 30003)
resource "kubernetes_deployment" "proxy_provider_oauth2" {
  for_each = local.participants
  metadata {
    name = "kafka-proxy-${each.value}-oauth2"
    labels = merge(local.common_labels, {
      "app" = "kafka-proxy-${each.value}-oauth2"
    })
  }

  spec {
    replicas = 1

    selector {
      match_labels = {
        app = "kafka-proxy-${each.value}-oauth2"
      }
    }

    template {
      metadata {
        labels = {
          app = "kafka-proxy-${each.value}-oauth2"
        }
      }

      spec {
        dynamic "image_pull_secrets" {
          for_each = var.environment == "devbox" && var.devbox-registry-cred != "" ? [1] : []
          content {
            name = var.devbox-registry-cred
          }
        }

        container {
          name              = "kafka-proxy-provider-oauth2"
          image             = local.kafka_proxy_image
          image_pull_policy = var.environment == "local" ? "Never" : "IfNotPresent"

          port {
            container_port = 30003
            name           = "proxy-oauth2"
          }

          env {
            name = "OAUTH2_TENANT_ID"
            value_from {
              secret_key_ref {
                name = kubernetes_secret.oauth2_validation_credentials[each.key].metadata[0].name
                key  = "tenant_id"
              }
            }
          }

          env {
            name = "OAUTH2_CLIENT_ID"
            value_from {
              secret_key_ref {
                name = kubernetes_secret.oauth2_validation_credentials[each.key].metadata[0].name
                key  = "client_id"
              }
            }
          }

          args = [
            "server",
            # Main bootstrap server mapping (TLS)
            "--bootstrap-server-mapping=${each.value}-broker:9092,0.0.0.0:30003,proxy-${each.value}:30003",
            "--debug-enable",
            "--dynamic-advertised-listener=proxy-${each.value}:30003",
            # AUTHENTICATION
            "--auth-local-enable=true",
            "--auth-local-mechanism=${var.provider_auth_mechanism}",
            "--auth-local-command=/usr/local/bin/oidc-token-info",

            # Supprime les --static-user si tu veux être 100% OIDC
            # Ajoute l'audience attendue (très important pour oidc-token-info)
            "--auth-local-param=--client-id=${var.provider_client_id}",
            "--auth-local-param=--required-scope=${var.provider_scope}",
            "--auth-local-param=--debug",

            # TLS (Pour passer de SASL_PLAINTEXT à SASL_SSL)
            "--proxy-listener-tls-enable",
            "--proxy-listener-cert-file=/etc/tls/server/tls.crt",
            "--proxy-listener-key-file=/etc/tls/server/tls.key",
          ]

          # env vars for auth-local plugin (oidc-token-verifier reads VERIFIER_*)
          env {
            name  = "VERIFIER_JWKS_URL"
            value = local.keycloak_jwks
          }
          env {
            name  = "VERIFIER_ALLOWED_ISSUERS"
            value = local.allowed_issuers
          }
          env {
            name  = "VERIFIER_CLIENT_ID"
            value = var.verifier_client_id
          }

          # env vars for auth-gateway-server plugin (oidc-token-info reads INFO_*)
          env {
            name  = "INFO_JWKS_URL"
            value = local.keycloak_jwks
          }
          env {
            name  = "INFO_ALLOWED_ISSUERS"
            value = local.allowed_issuers
          }
          env {
            name  = "INFO_CLIENT_ID"
            value = "${var.verifier_client_id},account,${var.provider_client_id}"
          }

          volume_mount {
            name       = "tls-server"
            mount_path = "/etc/tls/server"
            read_only  = true
          }

          resources {
            requests = {
              memory = "128Mi"
              cpu    = "100m"
            }
            limits = {
              memory = "256Mi"
              cpu    = "200m"
            }
          }

          liveness_probe {
            tcp_socket {
              port = 30003
            }
            initial_delay_seconds = 30
            period_seconds        = 10
          }

          readiness_probe {
            tcp_socket {
              port = 30003
            }
            initial_delay_seconds = 5
            period_seconds        = 5
          }
        }

        volume {
          name = "tls-server"
          secret {
            secret_name = kubernetes_secret.proxy_provider_tls_server[each.key].metadata[0].name
          }
        }
      }
    }
  }

  depends_on = [kubernetes_deployment.kafka_broker]
}

resource "kubernetes_service" "proxy_provider_oauth2" {
  for_each = local.participants
  metadata {
    name = "proxy-${each.value}-oauth2"
    labels = merge(local.common_labels, {
      "app" = "kafka-proxy-${each.value}-oauth2"
    })
  }

  spec {
    type = "ClusterIP"

    port {
      port        = 30003
      target_port = 30003
      protocol    = "TCP"
      name        = "proxy-oauth2"
    }

    selector = {
      app = "kafka-proxy-${each.value}-oauth2"
    }
  }
}

# Output the service details for use in tests
output "kafka_broker_service_name" {
  value = kubernetes_service.kafka_broker["provider"].metadata[0].name
}


output "kafka_broker_port" {
  value = 9092
}

output "kafka_broker_host" {
  value = "provider-broker.default.svc.cluster.local"
}

output "proxy_provider_service_name" {
  value = kubernetes_service.proxy_provider["provider"].metadata[0].name
}

output "proxy_provider_port" {
  value = 30001
}

output "proxy_provider_tls_port" {
  value = 30002
}

output "proxy_provider_host" {
  value = "proxy-provider.default.svc.cluster.local"
}

output "proxy_provider_ca_cert" {
  value     = tls_self_signed_cert.proxy_provider_ca["provider"].cert_pem
  sensitive = true
}

output "proxy_provider_ca_configmap" {
  value = kubernetes_config_map.proxy_provider_tls_ca["provider"].metadata[0].name
}

output "kafkacat_deployment_name" {
  value = kubernetes_deployment.kafkacat["provider"].metadata[0].name
}

output "proxy_provider_oauth2_service_name" {
  value = kubernetes_service.proxy_provider_oauth2["provider"].metadata[0].name
}

output "proxy_provider_oauth2_port" {
  value = 30003
}

output "proxy_provider_oauth2_host" {
  value = "proxy-provider-oauth2.default.svc.cluster.local"
}


output "oauth2_client_id" {
  value     = kubernetes_secret.oauth2_validation_credentials["provider"].data.client_id
  sensitive = true
}

output "kafka_broker_service_names" {
  value = { for p in local.participants : p => kubernetes_service.kafka_broker[p].metadata[0].name }
}

output "kafka_broker_hosts" {
  value = { for p in local.participants : p => "${p}-broker.default.svc.cluster.local" }
}

output "proxy_provider_service_names" {
  value = { for p in local.participants : p => kubernetes_service.proxy_provider[p].metadata[0].name }
}

output "proxy_provider_hosts" {
  value = { for p in local.participants : p => "proxy-${p}.default.svc.cluster.local" }
}

output "proxy_provider_ca_certs" {
  value     = { for p in local.participants : p => tls_self_signed_cert.proxy_provider_ca[p].cert_pem }
  sensitive = true
}

output "proxy_provider_ca_configmaps" {
  value = { for p in local.participants : p => kubernetes_config_map.proxy_provider_tls_ca[p].metadata[0].name }
}

output "kafkacat_deployment_names" {
  value = { for p in local.participants : p => kubernetes_deployment.kafkacat[p].metadata[0].name }
}

output "proxy_provider_oauth2_service_names" {
  value = { for p in local.participants : p => kubernetes_service.proxy_provider_oauth2[p].metadata[0].name }
}

output "proxy_provider_oauth2_hosts" {
  value = { for p in local.participants : p => "proxy-${p}-oauth2.default.svc.cluster.local" }
}

output "oauth2_client_ids" {
  value     = { for p in local.participants : p => kubernetes_secret.oauth2_validation_credentials[p].data.client_id }
  sensitive = true
}