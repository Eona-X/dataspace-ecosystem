{{/*
Expand the name of the chart.
*/}}
{{- define "kafka-proxy.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this (by the DNS naming spec).
If release name contains chart name it will be used as a full name.
*/}}
{{- define "kafka-proxy.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "kafka-proxy.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "kafka-proxy.labels" -}}
helm.sh/chart: {{ include "kafka-proxy.chart" . }}
{{ include "kafka-proxy.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/component: kafka-proxy-manager
{{- end }}

{{/*
Selector labels
*/}}
{{- define "kafka-proxy.selectorLabels" -}}
app.kubernetes.io/name: {{ include "kafka-proxy.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Create the name of the service account to use
*/}}
{{- define "kafka-proxy.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "kafka-proxy.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Generate the docker image name
*/}}
{{- define "kafka-proxy.image" -}}
{{- $registry := default .Values.kafkaProxy.manager.image.registry .Values.global.imageRegistry }}
{{- if $registry }}
{{- printf "%s/%s:%s" $registry .Values.kafkaProxy.manager.image.repository .Values.kafkaProxy.manager.image.tag }}
{{- else }}
{{- printf "%s:%s" .Values.kafkaProxy.manager.image.repository .Values.kafkaProxy.manager.image.tag }}
{{- end }}
{{- end }}

{{/*
Generate environment variables
*/}}
{{- define "kafka-proxy.env" -}}
- name: EDC_FS_CONFIG
  value: /app/configuration.properties
- name: KAFKA_PROXY_VAULT_TOKEN
  valueFrom:
    secretKeyRef:
      name: {{ .Values.kafkaProxy.manager.vaultTokenSecret.name }}
      key: {{ .Values.kafkaProxy.manager.vaultTokenSecret.key }}
{{- if .Values.kafkaProxy.manager.oidc.baseUrl }}
{{- $oidc := .Values.kafkaProxy.manager.oidc }}
{{- $baseUrl := $oidc.baseUrl | trimSuffix "/" }}
{{- $issuer := printf "%s/realms/%s" $baseUrl $oidc.realm }}
{{- $jwksUrl := printf "%s/protocol/openid-connect/certs" $issuer }}
{{- $tokenUrl := printf "%s/protocol/openid-connect/token" $issuer }}
{{- $allowedIssuers := $issuer }}
{{- if not (contains "localhost" $baseUrl) }}
{{- $allowedIssuers = printf "%s,http://localhost:8080/realms/%s" $issuer $oidc.realm }}
{{- end }}
- name: INFO_JWKS_URL
  value: {{ $jwksUrl | quote }}
- name: INFO_ALLOWED_ISSUERS
  value: {{ $allowedIssuers | quote }}
- name: INFO_CLIENT_ID
  value: {{ default .Values.kafkaProxy.manager.auth.clientId $oidc.verifier.clientId | quote }}
- name: VERIFIER_JWKS_URL
  value: {{ $jwksUrl | quote }}
- name: VERIFIER_ALLOWED_ISSUERS
  value: {{ $allowedIssuers | quote }}
- name: VERIFIER_CLIENT_ID
  value: {{ default .Values.kafkaProxy.manager.auth.clientId $oidc.verifier.clientId | quote }}
- name: VERIFIER_REQUIRED_SCOPES
  value: {{ $oidc.verifier.requiredScopes | quote }}
- name: PROVIDER_TOKEN_URL
  value: {{ $tokenUrl | quote }}
- name: PROVIDER_CLIENT_ID
  value: {{ $oidc.provider.clientId | quote }}
- name: PROVIDER_SCOPE
  value: {{ $oidc.provider.scope | quote }}
{{- if $oidc.provider.clientSecret }}
- name: PROVIDER_CLIENT_SECRET
  value: {{ $oidc.provider.clientSecret | quote }}
{{- else if $oidc.provider.clientSecretName }}
- name: PROVIDER_CLIENT_SECRET
  valueFrom:
    secretKeyRef:
      name: {{ $oidc.provider.clientSecretName }}
      key: {{ $oidc.provider.clientSecretKey }}
{{- end }}
{{- end }}
{{- if and .Values.kafkaProxy.manager.edc .Values.kafkaProxy.manager.edc.keystore }}
- name: EDC_KEYSTORE
  value: {{ .Values.kafkaProxy.manager.edc.keystore | quote }}
- name: EDC_KEYSTORE_PASSWORD
  value: {{ .Values.kafkaProxy.manager.edc.keystorePassword | quote }}
{{- end }}
{{- if and .Values.kafkaProxy.manager.edc .Values.kafkaProxy.manager.edc.vault .Values.kafkaProxy.manager.edc.vault.clientId }}
- name: EDC_VAULT_CLIENTID
  value: {{ .Values.kafkaProxy.manager.edc.vault.clientId | quote }}
- name: EDC_VAULT_TENANTID
  value: {{ .Values.kafkaProxy.manager.edc.vault.tenantId | quote }}
- name: EDC_VAULT_CLIENTSECRET
  value: {{ .Values.kafkaProxy.manager.edc.vault.clientSecret | quote }}
- name: EDC_VAULT_NAME
  value: {{ .Values.kafkaProxy.manager.edc.vault.name | quote }}
{{- end }}
{{- if .Values.kafkaProxy.manager.participantId }}
- name: EDC_PARTICIPANT_ID
  value: {{ .Values.kafkaProxy.manager.participantId | quote }}
{{- end }}
{{- if and .Values.kafkaProxy.manager.vaultTls.enabled .Values.kafkaProxy.manager.vaultTls.caCert.secret }}
- name: VAULT_SSL_CERT
  value: {{ .Values.kafkaProxy.manager.vaultTls.caCert.path | default "/vault-ca/ca.crt" | quote }}
{{- end }}
{{- end }}

{{/*
Generate volume mounts
*/}}
{{- define "kafka-proxy.volumeMounts" -}}
- name: config
  mountPath: /app/configuration.properties
  subPath: configuration.properties
  readOnly: true
{{- if .Values.persistence.shared.enabled }}
- name: shared-data
  mountPath: {{ .Values.kafkaProxy.manager.sharedDir }}
{{- end }}
{{- if and .Values.kafkaProxy.manager.vaultTls.enabled .Values.kafkaProxy.manager.vaultTls.caCert.secret }}
{{- $certPath := .Values.kafkaProxy.manager.vaultTls.caCert.path | default "/vault-ca/ca.crt" }}
{{- $mountPath := dir $certPath }}
{{- $fileName := base $certPath }}
- name: vault-ca
  mountPath: {{ $certPath }}
  subPath: {{ $fileName }}
  readOnly: true
{{- end }}
{{- range .Values.extraVolumeMounts }}
- {{ toYaml . | nindent 2 }}
{{- end }}
{{- end }}

{{/*
Generate volumes
*/}}
{{- define "kafka-proxy.volumes" -}}
- name: config
  configMap:
    name: {{ include "kafka-proxy.fullname" . }}-config
{{- if .Values.persistence.shared.enabled }}
- name: shared-data
  persistentVolumeClaim:
    claimName: {{ include "kafka-proxy.fullname" . }}-shared
{{- end }}
{{- if and .Values.kafkaProxy.manager.vaultTls.enabled .Values.kafkaProxy.manager.vaultTls.caCert.secret }}
{{- $certPath := .Values.kafkaProxy.manager.vaultTls.caCert.path | default "/vault-ca/ca.crt" }}
{{- $fileName := base $certPath }}
- name: vault-ca
  secret:
    secretName: {{ .Values.kafkaProxy.manager.vaultTls.caCert.secret }}
    items:
      - key: {{ .Values.kafkaProxy.manager.vaultTls.caCert.key }}
        path: {{ $fileName }}
{{- else if and .Values.kafkaProxy.manager.vaultTls.enabled .Values.kafkaProxy.manager.vaultTls.caCert.inline }}
- name: vault-ca
  configMap:
    name: {{ include "kafka-proxy.fullname" . }}-vault-ca
{{- end }}
{{- range .Values.extraVolumes }}
- {{ toYaml . | nindent 2 }}
{{- end }}
{{- end }}