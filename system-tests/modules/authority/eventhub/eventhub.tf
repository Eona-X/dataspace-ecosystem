locals {
  event_hub_image = "mcr.microsoft.com/azure-messaging/eventhubs-emulator:${var.eventhub_emulator_version}"
  blob_server     = "local-eventhub-azurite"
  metadata_server = "local-eventhub-azurite"
  accept_eula     = "Y"
}

resource "kubernetes_config_map" "eventhubs-config" {
  metadata {
    name = "eventhubs-config"
  }

  data = {
    "Config.json" = <<-EOT
      {
          "UserConfig": {
              "NamespaceConfig": [
              {
                  "Type": "EventHub",
                  "Name": "emulatorNs1",
                  "Entities": [
                  {
                      "Name": "eh1",
                      "PartitionCount": "2",
                      "ConsumerGroups": [
                      {
                          "Name": "cg1"
                      }
                      ]
                  }
                  ]
              }
              ],
              "LoggingConfig": {
                  "Type": "File"
              }
          }
      }
    EOT
  }
}

resource "kubernetes_stateful_set" "eventhubs" {
  metadata {
    name = "eventhubs"
  }

  spec {
    service_name = "eventhubs"
    replicas     = 1

    selector {
      match_labels = {
        app = "eventhubs"
      }
    }

    template {
      metadata {
        labels = {
          app = "eventhubs"
        }
      }

      spec {
        container {
          name  = "eventhubs"
          image = local.event_hub_image

          port {
            container_port = 5672
            name           = "amqp"
          }

          port {
            container_port = 9092
            name           = "http"
          }

          env {
            name  = "STORAGE_ACCOUNT_NAME"
            value = var.account_name_azurite
          }

          env {
            name  = "STORAGE_ACCOUNT_KEY"
            value = var.account_secret_azurite
          }


          env {
            name  = "BLOB_SERVER"
            value = kubernetes_service.azurite.metadata.0.name
          }

          env {
            name  = "METADATA_SERVER"
            value = kubernetes_service.azurite.metadata.0.name
          }

          env {
            name  = "ACCEPT_EULA"
            value = local.accept_eula
          }

          volume_mount {
            mount_path = "/Eventhubs_Emulator/ConfigFiles/"
            name       = "config-volume"
            read_only  = true
          }
        }

        volume {
          name = "config-volume"
          config_map {
            name = kubernetes_config_map.eventhubs-config.metadata.0.name
          }
        }
      }
    }
  }
}

resource "kubernetes_service" "eventhubs" {
  metadata {
    name = "eventhubs"
  }

  spec {
    selector = {
      app = "eventhubs"
    }

    port {
      port        = 5672
      target_port = 5672
      name        = "amqp"
    }

    port {
      port        = 9092
      target_port = 9092
      name        = "kafka"
    }
  }
}