resource "tls_private_key" "kafka_ca_key" {
  algorithm = "RSA"
  rsa_bits  = 2048
}

resource "tls_self_signed_cert" "kafka_ca_cert" {
  private_key_pem = tls_private_key.kafka_ca_key.private_key_pem

  subject {
    common_name  = "kafka-ca-local"
    organization = "eonax"
    country      = "FR"
  }

  validity_period_hours = 8760

  allowed_uses = [
    "cert_signing",
    "key_encipherment",
    "digital_signature"
  ]

  is_ca_certificate = true
}

resource "kubernetes_secret" "kafka_ca_secret" {
  metadata {
    name      = "kafka-ca-secret"
    namespace = "default"
  }

  type = "Opaque"

  data = {
    "ca.crt" = tls_self_signed_cert.kafka_ca_cert.cert_pem
  }
}
