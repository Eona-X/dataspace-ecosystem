variable "account_secret_azurite" {
  description = "Account secret for event hub"
  sensitive   = true
}

variable "account_name_azurite" {
  description = "Account name for event hub"
}

variable "azurite_version" {
  description = "Version of Azurite image to use"
  default     = "3.35.0"
}

variable "eventhub_emulator_version" {
  description = "Version of EventHub Emulator image to use"
  default     = "2.1.0"
}