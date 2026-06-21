# GKE Microservices with Istio Service Mesh — A Hands-On Learning Guide

> **⚠️ Important Note for Readers:** Terminal commands in this guide are tailored for **macOS**. If you are on **Windows** or **Linux**, you may need to adjust commands (e.g., use PowerShell equivalents or package managers like `apt` instead of `brew`).

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                    GKE Cluster                              │
│                  Namespace: secure-mesh                     │
│                                                             │
│  ┌──────────────────┐         ┌──────────────────┐         │
│  │  Spring Boot API  │ ──────▶│  Node.js API     │         │
│  │   (Port 8080)     │  mTLS  │   (Port 3000)    │         │
│  │                    │◀────── │                   │         │
│  └──────────────────┘         └──────────────────┘         │
│         │                                │                  │
│         ▼                                ▼                  │
│  ┌──────────────┐              ┌──────────────┐            │
│  │ Istio Sidecar │              │ Istio Sidecar │            │
│  │   (Envoy)     │              │   (Envoy)     │            │
│  └──────────────┘              └──────────────┘            │
└─────────────────────────────────────────────────────────────┘
```

A complete guide to deploying **two microservices** (Spring Boot + Node.js) on **Google Kubernetes Engine (GKE)** with **Istio service mesh** for mTLS encryption and fine-grained access control. The Spring Boot service calls the Node.js service via Kubernetes internal DNS, with all traffic secured through Istio's mutual TLS (mTLS).

---

## What You Will Do

1. Install the required local tools (`gcloud`, `kubectl`, `docker`)
2. Authenticate to Google Cloud and set up your project
3. Create a GKE cluster, build Docker images, update manifests, and deploy services
4. Enforce mTLS STRICT mode for all pod-to-pod communication
5. Verify that inter-service communication works through the mesh
6. Clean up all cloud resources

---

## Prerequisites

Before you begin, ensure the following tools are installed:

- **Google Cloud SDK (`gcloud`)** — GCP authentication & project management → [Install Guide](https://cloud.google.com/sdk/docs/install)
- **`kubectl`** — Kubernetes CLI → `gcloud components install kubectl`
- **`docker`** — Build container images → [Install Docker](https://docs.docker.com/get-docker/)

Verify your installations:

```bash
gcloud --version
kubectl version --client
docker --version
```

---

## Placeholders

Throughout this guide, replace the following placeholders with your actual values:

- **`<YOUR_GCP_PROJECT_ID>`** — Your Google Cloud project ID (must be globally unique, lowercase letters/digits/hyphens only) → e.g. `gke-mtls-mesh-demo`
- **`<YOUR_PROJECT_NAME>`** — A human-readable display name for your project (your choice) → e.g. `GKE mTLS Mesh Demo`
- **`<YOUR_REGION>`** — GCP region for the GKE cluster → e.g. `us-central1`
- **`<YOUR_ZONE>`** — GCP zone for the GKE cluster → e.g. `us-central1-a`
- **`<CLUSTER_NAME>`** — Name of your GKE cluster → e.g. `mesh-demo-cluster`

> **Important:** The `<YOUR_GCP_PROJECT_ID>` must be replaced consistently in **all** commands and **all** files (`k8s/manifests.yaml`, Docker build/push commands, cleanup commands) throughout this guide.

> **Tip:** You can check your current project with `gcloud config get-value project`.

---

## Project Structure

```
gke-microservices-servicesmesh-poc/
├── k8s/
│   ├── manifests.yaml          # Namespace, Deployments & Services
│   └── mesh-security.yaml      # Istio PeerAuthentication & AuthorizationPolicy
├── nodejs-service/
│   ├── Dockerfile              # Multi-stage Node.js 18 build
│   ├── index.js                # Express.js API (port 3000)
│   └── package.json            # Node.js dependencies
├── springboot-service/
│   ├── Dockerfile              # Multi-stage Maven + JDK 17 build
│   ├── pom.xml                 # Spring Boot 3.2.5 dependencies
│   └── src/main/java/com/example/demo/
│       └── DemoApplication.java # Spring Boot app + REST controller (port 8080)
└── .gitignore
```

---

## Step-by-Step Guide

### Step 1 — Authenticate to Google Cloud and Create a Project

Log in to Google Cloud:

```bash
gcloud auth login
```

Create a new GCP project. The project name is **your choice** — pick any unique name that makes sense for you:

```bash
gcloud projects create <YOUR_GCP_PROJECT_ID> --name="<YOUR_PROJECT_NAME>"
```

> **Note:** Replace `<YOUR_GCP_PROJECT_ID>` with a globally unique project ID (e.g. `my-gke-mesh-demo`) and `<YOUR_PROJECT_NAME>` with a human-readable display name (e.g. `GKE mTLS Mesh Demo`). The project ID must be unique across all of Google Cloud and can only contain lowercase letters, digits, and hyphens.

Set the new project as your active project:

```bash
gcloud config set project <YOUR_GCP_PROJECT_ID>
```

Verify your active account and project:

```bash
gcloud auth list
gcloud config get-value project
```

---

### Step 2 — Enable Required APIs

Enable the GKE and Container Registry APIs:

```bash
gcloud services enable container.googleapis.com
gcloud services enable containerregistry.googleapis.com
```

---

### Step 3 — Create GKE Cluster, Build Images, Update Manifests, and Deploy Services

This step covers the complete flow from cluster creation to deployment. Follow the sub-steps in order.

#### 3a — Create the GKE Cluster

Create a standard GKE cluster:

```bash
gcloud container clusters create <CLUSTER_NAME> \
  --zone <YOUR_ZONE> \
  --num-nodes=3 \
  --machine-type=e2-standard-2 \
  --release-channel=regular
```

Get credentials for `kubectl`:

```bash
gcloud container clusters get-credentials <CLUSTER_NAME> \
  --zone <YOUR_ZONE> \
  --project <YOUR_GCP_PROJECT_ID>
```

Verify the cluster is running:

```bash
kubectl get nodes
```

#### 3b — Create the Kubernetes Namespace

Create the `secure-mesh` namespace with Istio sidecar injection enabled:

```bash
kubectl apply -f - <<EOF
apiVersion: v1
kind: Namespace
metadata:
  name: secure-mesh
  labels:
    istio-injection: enabled
EOF
```

> The `istio-injection: enabled` label tells Istio to automatically inject an Envoy sidecar proxy into every pod deployed in this namespace.

#### 3c — Build and Push Docker Images

**Configure Docker for GCR:**

```bash
gcloud auth configure-docker
```

**Build and Push the Node.js Service Image:**

```bash
cd nodejs-service

docker build -t gcr.io/<YOUR_GCP_PROJECT_ID>/nodejs-service:1.0.0 .

docker push gcr.io/<YOUR_GCP_PROJECT_ID>/nodejs-service:1.0.0

cd ..
```

**Node.js Dockerfile:**

```dockerfile
FROM node:18-slim
WORKDIR /app
COPY package*.json ./
RUN npm install --production
COPY index.js .
EXPOSE 3000
CMD ["node", "index.js"]
```

**Build and Push the Spring Boot Service Image:**

```bash
cd springboot-service

docker build -t gcr.io/<YOUR_GCP_PROJECT_ID>/springboot-service:1.0.0 .

docker push gcr.io/<YOUR_GCP_PROJECT_ID>/springboot-service:1.0.0

cd ..
```

**Spring Boot Dockerfile (multi-stage build):**

```dockerfile
FROM maven:3.8.5-openjdk-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /app/target/springboot-service-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

#### 3d — Update Manifests and Deploy Services

Update the image references in `k8s/manifests.yaml` with your actual GCP project ID:

```yaml
# In k8s/manifests.yaml, replace the image paths:

image: gcr.io/<YOUR_GCP_PROJECT_ID>/springboot-service:1.0.0
# ... and ...
image: gcr.io/<YOUR_GCP_PROJECT_ID>/nodejs-service:1.0.0
```

Apply the Kubernetes manifests:

```bash
kubectl apply -f k8s/manifests.yaml
```

This creates:
- **Namespace:** `secure-mesh` (with Istio sidecar injection enabled)
- **Spring Boot Deployment** (1 replica) + **Service** (ClusterIP, port 8080)
- **Node.js Deployment** (1 replica) + **Service** (ClusterIP, port 3000)

Verify the deployments:

```bash
kubectl get all -n secure-mesh
```

Wait until both pods show `READY 2/2` (the second container is the Istio Envoy sidecar):

```bash
kubectl get pods -n secure-mesh -w
```

> **Note:** You should see `2/2` under READY for each pod — this confirms the Istio sidecar proxy has been injected alongside your application container.

---

### Step 4 — Enforce mTLS STRICT Mode

Apply the Istio `PeerAuthentication` policy to enforce mutual TLS across the entire namespace:

```bash
kubectl apply -f k8s/mesh-security.yaml
```

This file contains two resources:

**PeerAuthentication (mTLS STRICT):**

```yaml
apiVersion: security.istio.io/v1
kind: PeerAuthentication
metadata:
  name: default
  namespace: secure-mesh
spec:
  mtls:
    mode: STRICT
```

> In `STRICT` mode, all pod-to-pod communication within the namespace **must** use mTLS. Plain-text connections are immediately rejected at the Envoy proxy sidecar boundary.

**AuthorizationPolicy (Service-to-Service Access Control):**

```yaml
apiVersion: security.istio.io/v1
kind: AuthorizationPolicy
metadata:
  name: nodejs-policy
  namespace: secure-mesh
spec:
  selector:
    matchLabels:
      app: nodejs-service
  action: ALLOW
  rules:
  - from:
    - source:
        principals: ["cluster.local/ns/secure-mesh/sa/default"]
```

> This policy ensures that **only** the Spring Boot service (via the `default` service account in the `secure-mesh` namespace) can reach the Node.js service. All other traffic sources are denied.

---

### Step 5 — Verify the Setup

#### 5a — Test Node.js Service Call from Spring Boot

First, verify that the Spring Boot service can reach the Node.js service from **within the cluster** using `kubectl exec`:

```bash
kubectl exec -it deploy/springboot-deploy -n secure-mesh -c springboot -- \
  curl http://nodejs-service.secure-mesh.svc.cluster.local:3000/ --max-time 5
```

**Expected output:**

```json
{"service":"nodejs-service","status":"ok"}
```

> This confirms that the Spring Boot pod can resolve the Node.js service via Kubernetes DNS (`nodejs-service.secure-mesh.svc.cluster.local`) and receive a response over the Istio service mesh.

Next, test the Spring Boot `/invoke-backend` endpoint which internally calls the Node.js service and returns a combined response:

```bash
kubectl exec -it deploy/springboot-deploy -n secure-mesh -c springboot -- \
  curl http://localhost:8080/invoke-backend --max-time 5
```

**Expected output:**

```
Spring Boot received: {"service":"nodejs-service","status":"ok"}
```

> This confirms the full chain: Spring Boot receives the request → calls Node.js via `http://nodejs-service.secure-mesh.svc.cluster.local:3000/data` → returns the combined response.

#### 5b — Test from Your Local Machine (via Port-Forward)

Port-forward the Spring Boot service to your local machine:

```bash
kubectl port-forward svc/springboot-service 8080:8080 -n secure-mesh
```

In a new terminal, invoke the backend:

```bash
curl http://localhost:8080/invoke-backend
```

**Expected output:**

```
Spring Boot received: {"service":"nodejs-service","status":"ok"}
```

This confirms that:
- ✅ Spring Boot is running and healthy
- ✅ Spring Boot can reach Node.js via Kubernetes DNS (`http://nodejs-service.secure-mesh.svc.cluster.local:3000/data`)
- ✅ Istio mTLS is working (traffic between services is encrypted)

#### 5c — Verify mTLS is Enforced

Exec into a pod and attempt a plain-text (non-mTLS) connection to the Node.js service:

```bash
kubectl exec -it deploy/springboot-deploy -n secure-mesh -c springboot -- \
  curl http://nodejs-service.secure-mesh.svc.cluster.local:3000/ --max-time 5
```

> If mTLS is working correctly, the request should succeed because it goes through the Envoy sidecar (which handles mTLS automatically).

#### 5d — Check Istio Configuration

Verify the mTLS status:

```bash
istioctl x describe pod <POD_NAME> -n secure-mesh
```

Check proxy configuration:

```bash
istioctl proxy-status
```

View the AuthorizationPolicy:

```bash
kubectl get authorizationpolicy -n secure-mesh
kubectl describe authorizationpolicy nodejs-policy -n secure-mesh
```

View the PeerAuthentication:

```bash
kubectl get peerauthentication -n secure-mesh
kubectl describe peerauthentication default -n secure-mesh
```

---

### Step 6 — Inspect Sidecar Logs

To confirm the Istio sidecar is active, check the logs:

```bash
# Application logs
kubectl logs deploy/springboot-deploy -n secure-mesh -c springboot

# Istio sidecar (Envoy proxy) logs
kubectl logs deploy/springboot-deploy -n secure-mesh -c istio-proxy
```

---

## Cleanup

Delete all resources to avoid incurring charges:

```bash
# Delete Kubernetes resources
kubectl delete namespace secure-mesh

# Delete the GKE cluster
gcloud container clusters delete <CLUSTER_NAME> \
  --zone <YOUR_ZONE> \
  --project <YOUR_GCP_PROJECT_ID>
```

Delete container images from GCR (optional):

```bash
gcloud container images delete gcr.io/<YOUR_GCP_PROJECT_ID>/springboot-service:1.0.0 --force-delete-tags
gcloud container images delete gcr.io/<YOUR_GCP_PROJECT_ID>/nodejs-service:1.0.0 --force-delete-tags
```

---

## Troubleshooting

### Pod stuck in `Pending` or `CrashLoopBackOff`

```bash
kubectl describe pod <POD_NAME> -n secure-mesh
kubectl logs <POD_NAME> -n secure-mesh
```

Common causes:
- Image pull errors → Verify the image exists in GCR and the project ID is correct
- Insufficient resources → Check node capacity with `kubectl describe nodes`

### Istio sidecar not injected (pod shows `1/2` READY)

```bash
kubectl get namespace secure-mesh --show-labels
```

Ensure the namespace has `istio-injection=enabled`. If not, label it:

```bash
kubectl label namespace secure-mesh istio-injection=enabled
```

Then restart the deployments:

```bash
kubectl rollout restart deployment -n secure-mesh
```

### Connection refused between services

```bash
# Verify services exist and have endpoints
kubectl get svc -n secure-mesh
kubectl get endpoints -n secure-mesh

# Check if AuthorizationPolicy is blocking traffic
kubectl logs deploy/nodejs-deploy -n secure-mesh -c istio-proxy | grep -i deny
```

### `curl` fails from outside the mesh

This is expected. When mTLS is set to `STRICT`, external plain-text connections are rejected. Always test inter-service communication from within the mesh (via `kubectl exec` or `kubectl port-forward`).

---

## Key Concepts

- **Istio Sidecar Injection** — Automatically adds an Envoy proxy container to each pod, intercepting all network traffic
- **mTLS (mutual TLS)** — Both client and server authenticate each other using certificates, encrypting all traffic
- **PeerAuthentication** — Istio resource that defines the mTLS mode (STRICT, PERMISSIVE, DISABLE) for a namespace or workload
- **AuthorizationPolicy** — Istio resource that defines fine-grained access control rules (who can call which service)
- **ClusterIP Service** — Kubernetes service type that exposes the service only within the cluster (no external access)
- **Kubernetes DNS** — Services are reachable via `<service-name>.<namespace>.svc.cluster.local:<port>`

---

## Tech Stack

- **Cloud Provider** — Google Cloud Platform (GCP)
- **Container Orchestration** — Google Kubernetes Engine (GKE)
- **Service Mesh** — Istio 1.30.1
- **Frontend API** — Spring Boot 3.2.5 (Java 17)
- **Backend API** — Node.js 18.x / Express 4.19.2
- **Container Registry** — Google Container Registry (GCR)
- **Security** — Istio mTLS STRICT + AuthorizationPolicy