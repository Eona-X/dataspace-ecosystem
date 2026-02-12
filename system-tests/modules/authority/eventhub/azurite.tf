locals {
  azurite_image = "mcr.microsoft.com/azure-storage/azurite:${var.azurite_version}"
}

resource "kubernetes_stateful_set" "azurite" {
  metadata {
    name = "azurite"
  }

  spec {
    service_name = "azurite"
    replicas     = 1

    selector {
      match_labels = {
        app = "azurite"
      }
    }

    template {
      metadata {
        labels = {
          app = "azurite"
        }
      }

      spec {
        container {
          name  = "azurite"
          image = local.azurite_image
          args  = ["azurite", "--skipApiVersionCheck", "--blobHost", "0.0.0.0", "--queueHost", "0.0.0.0", "--tableHost", "0.0.0.0"]

          port {
            container_port = 10000
            name           = "blob"
          }

          port {
            container_port = 10001
            name           = "queue"
          }

          port {
            container_port = 10002
            name           = "table"
          }
        }
      }
    }
  }
}

resource "kubernetes_service" "azurite" {
  metadata {
    name = "azurite"
  }

  spec {
    selector = {
      app = "azurite"
    }

    port {
      port        = 10000
      target_port = 10000
      protocol    = "TCP"
      name        = "blob"
    }

    port {
      port        = 10001
      target_port = 10001
      protocol    = "TCP"
      name        = "queue"
    }

    port {
      port        = 10002
      target_port = 10002
      protocol    = "TCP"
      name        = "table"
    }
  }
}

resource "kubernetes_stateful_set" "azurite-report-storage" {
  metadata {
    name = "azurite-report-storage"
  }

  spec {
    service_name = "azurite-report-storage"
    replicas     = 1

    selector {
      match_labels = {
        app = "azurite-report-storage"
      }
    }

    template {
      metadata {
        labels = {
          app = "azurite-report-storage"
        }
      }

      spec {
        container {
          name  = "azurite-report-storage"
          image = local.azurite_image
          args  = ["azurite", "--skipApiVersionCheck", "--blobHost", "0.0.0.0", "--queueHost", "0.0.0.0", "--tableHost", "0.0.0.0"]

          port {
            container_port = 10000
            name           = "blob"
          }

          port {
            container_port = 10001
            name           = "queue"
          }

          port {
            container_port = 10002
            name           = "table"
          }

          env {
            name  = "AZURITE_ACCOUNTS"
            value = "${var.account_name_azurite}:${var.account_secret_azurite}"
          }

        }
      }
    }
  }
}

resource "kubernetes_job" "create_blob_container" {
  metadata {
    name = "create-blob-container"
  }

  spec {
    backoff_limit = 5

    template {
      metadata {
        labels = {
          app = "create-blob-container"
        }
      }

      spec {
        restart_policy = "OnFailure"

        container {
          name  = "create-blob-container"
          image = "mcr.microsoft.com/azure-cli"
          command = [
            "sh", "-c",
            <<-EOT
              echo "Waiting for Azurite service to respond on port 10000..."
              until curl -s http://azurite-report-storage:10000/${var.account_name_azurite}?comp=list >/dev/null 2>&1; do
                echo "Azurite not ready, sleeping 5s..."
                sleep 5
              done

              echo "Azurite is ready. Creating container..."
              az storage container create \
                --name reports \
                --account-name ${var.account_name_azurite} \
                --account-key ${var.account_secret_azurite} \
                --connection-string "DefaultEndpointsProtocol=http;AccountName=${var.account_name_azurite};AccountKey=${var.account_secret_azurite};BlobEndpoint=http://azurite-report-storage:10000/${var.account_name_azurite};"
              echo "Container created (or already exists)."
            EOT
          ]
        }
      }
    }
  }

  depends_on = [kubernetes_stateful_set.azurite-report-storage]
}


resource "kubernetes_service" "azurite-report-storage" {
  metadata {
    name = "azurite-report-storage"
  }

  spec {
    selector = {
      app = "azurite-report-storage"
    }

    port {
      port        = 10000
      target_port = 10000
      protocol    = "TCP"
      name        = "blob"
    }

    port {
      port        = 10001
      target_port = 10001
      protocol    = "TCP"
      name        = "queue"
    }

    port {
      port        = 10002
      target_port = 10002
      protocol    = "TCP"
      name        = "table"
    }
  }
}