terraform {
  required_providers {
    kubernetes = {
      source = "hashicorp/kubernetes"
    }

    helm = {
      source  = "hashicorp/helm"
      version = ">= 3.0.1"
    }

    vault = {
      source = "hashicorp/vault"
    }

    tls = {
      source = "hashicorp/tls"
    }
  }
}