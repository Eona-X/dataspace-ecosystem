# Dataspace Ecosystem - Minimum Viable Dataspace

## Prerequisite

- Terraform
- Podman or Docker
- Kind
- cURL or Postman

## Create and prepare a local Kubernetes cluster

```bash
kind create cluster -n dse-cluster --config kind.config.yaml
```

### Install Ingress Controller

We install an Ingress Controller in order to interact with the microservice running in the cluster from the host.

```bash
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml
```
### For Windows users
**Note:** Please do this custom configuration
- Edit the nginx-controller deployment.yaml file by running the following command:
```bash
kubectl patch deployment ingress-nginx-controller -n ingress-nginx \
  --type='json' \
  -p='[{"op": "replace", "path": "/spec/template/spec/containers/0/resources", "value": {"limits": {"cpu": "500m", "memory": "512Mi"}, "requests": {"cpu": "100m", "memory": "90Mi"}}}]'
```
  this will update the ingress-nginx-controller.yaml deployment file with right resource values
- Restart ingress-nginx-controller by running the following command:
```bash
kubectl rollout restart deployment/ingress-nginx-controller -n ingress-nginx
```

Verify that Ingress Controller is up:

```bash
kubectl wait --namespace ingress-nginx \
  --for=condition=ready pod \
  --selector=app.kubernetes.io/component=controller \
  --timeout=90s
```

## Deploy Dataspace Ecosystem components in the cluster

## Using Docker

### Create the Docker images

```bash
./gradlew clean shadowJar dockerize
```

### Load the Docker images in the Cluster

```bash
kind load docker-image \
  control-plane-postgresql-hashicorpvault:latest \
  data-plane-postgresql-hashicorpvault:latest \
  federated-catalog-postgresql-hashicorpvault:latest \
  identity-hub-postgresql-hashicorpvault:latest \
  issuer-service-postgresql-hashicorpvault:latest \
  telemetry-service-postgresql-hashicorpvault:latest \
  telemetry-agent-postgresql-hashicorpvault:latest \
  backend-service-provider:latest \
  telemetry-storage-postgresql-hashicorpvault:latest \
  telemetry-csv-manager-postgresql-hashicorpvault:latest \
  -n dse-cluster
```

## Using Podman

### Create the Docker images

```bash
./gradlew clean podmanize
```

**Note:** You can create the images and load them into the Kubernetes cluster with a single command by running:

```bash
./gradlew clean loadToKind
```

## Deploy the dataspace

Once you have configured the participants you want to deploy using the `participants` field of
the [variables.tf](variables.tf) file,
simply run the following Terraform command to deploy the dataspace:

```bash
terraform -chdir=system-tests init
terraform -chdir=system-tests destroy -auto-approve -var="environment=local"
terraform -chdir=system-tests apply -auto-approve -var="environment=local"
```
To destroy the dataspace run the following command:
```bash
terraform -chdir=system-tests destroy -auto-approve -var="environment=local"
```

## (Optional) Deploy a single connector:

To deploy a single connector, due to the dependency on the Database, one of the following conditions must be met:  
- the full Dataspace is already deployed;
- the Database is already deployed; this can be done by running the following command inside "system-tests" folder:

  ```
  terraform apply -target=module.postgres -auto-approve
  ```

> Please note that in case the Dataspace is already deployed, deploying another connector on top of it may fail on a local machine due to the high consumption of memory. Hence the second approach is preferred.
- Once the Database is deployed, the file *standalone-providers.tf.disabled* should be renamed to *standalone-providers.tf*
- Declare a terraform.tfvars file with at least the following:

```
# Participant Configuration for Self-hosted
participant_name = "your-participant-name"

# Container Images for Self-Hosted Environments
control_plane_image      = "control-plane-postgresql-hashicorpvault"
data_plane_image         = "data-plane-postgresql-hashicorpvault"
identity_hub_image       = "identity-hub-postgresql-hashicorpvault"
telemetry_agent_image    = "telemetry-agent-postgresql-hashicorpvault"

# Kubernetes Configuration (for standalone mode)
# Replace "kind-dse-cluster" with your actual Kubernetes context name (for example, the output of `kubectl config current-context`)
kube_context     = "kind-dse-cluster"
kube_config_path = "~/.kube/config"
# Environment Configuration
environment          = "selfhosted"

# Charts Path (relative to participant module)
charts_path = "../../../charts"

```

- Inside the *participant* folder run:
```
terraform init
terraform destroy
terraform apply -auto-approve
```

## Run the tests

As Eventhub uses the `sb://` protocol and Nginx has issues working with it, we can forward the port of the pods to allow
the system test to connect directly to the Eventhub local pod instances instead of passing through Nginx.

To do this, open a terminal and execute the following `kubectl` command (the terminal needs to stay open during the
execution of the test, otherwise the port forwarding will not work):

```sh
kubectl port-forward eventhubs-0 52717:5672
kubectl port-forward postgresql-0 57521:5432 &
```

Afterwards, you can execute the following command to run the tests:

```bash
./gradlew :system-tests:runner:test -DincludeTags="EndToEndTest"
```

Note: The tests can only be run once. If you want to rerun them, destroy first the dataspace and then re-deploy it
