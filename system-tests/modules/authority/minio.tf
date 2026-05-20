resource "kubernetes_deployment" "minio" {
  metadata {
    name = "minio-report-storage"
    labels = {
      app = "minio-report-storage"
    }
  }

  spec {
    replicas = 1

    selector {
      match_labels = {
        app = "minio-report-storage"
      }
    }

    template {
      metadata {
        labels = {
          app = "minio-report-storage"
        }
      }

      spec {
        container {
          name  = "minio"
          image = "minio/minio:RELEASE.2024-03-07T00-43-48Z"
          args  = ["server", "/data"]

          port {
            container_port = 9000
            name           = "minio-api"
          }

          port {
            container_port = 9001
            name           = "minio-console"
          }

          env {
            name = "MINIO_ROOT_USER"
            value_from {
              secret_key_ref {
                name = kubernetes_secret.minio-credentials.metadata[0].name
                key  = "access-key"
              }
            }
          }

          env {
            name = "MINIO_ROOT_PASSWORD"
            value_from {
              secret_key_ref {
                name = kubernetes_secret.minio-credentials.metadata[0].name
                key  = "secret-key"
              }
            }
          }

          volume_mount {
            name       = "minio-data"
            mount_path = "/data"
          }

          resources {
            requests = {
              cpu    = "100m"
              memory = "256Mi"
            }
            limits = {
              cpu    = "500m"
              memory = "512Mi"
            }
          }
        }

        volume {
          name = "minio-data"
          empty_dir {}
        }
      }
    }
  }
}

resource "kubernetes_service" "minio" {
  metadata {
    name = "minio-report-storage"
  }

  spec {
    selector = {
      app = "minio-report-storage"
    }

    port {
      port        = 9000
      target_port = 9000
      name        = "minio-api"
    }

    port {
      port        = 9001
      target_port = 9001
      name        = "minio-console"
    }

    type = "ClusterIP"
  }
}

# Job to create the 'reports' bucket in MinIO
resource "kubernetes_job_v1" "minio_create_bucket" {
  metadata {
    name = "minio-create-bucket"
  }

  spec {
    template {
      metadata {
        labels = {
          app = "minio-create-bucket"
        }
      }

      spec {
        container {
          name  = "mc"
          image = "minio/mc:latest"
          command = ["/bin/sh", "-c"]
          args = [
            <<-EOT
            mc alias set myminio http://minio-report-storage:9000 $access_key $secret_key;
            mc mb myminio/reports || true;
            mc anonymous set public myminio/reports;
            EOT
          ]

          env {
            name = "access_key"
            value_from {
              secret_key_ref {
                name = kubernetes_secret.minio-credentials.metadata[0].name
                key  = "access-key"
              }
            }
          }

          env {
            name = "secret_key"
            value_from {
              secret_key_ref {
                name = kubernetes_secret.minio-credentials.metadata[0].name
                key  = "secret-key"
              }
            }
          }
        }

        restart_policy = "OnFailure"
      }
    }

    backoff_limit = 4
  }

  depends_on = [kubernetes_service.minio]
}
