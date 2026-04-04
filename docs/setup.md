# Local setup

## Environment

- OS: Windows
- Kubernetes: Docker Desktop Kubernetes
- kubectl: required
- Helm: required for PostgreSQL and monitoring stack

## Namespaces

Apply base namespaces:

```powershell
kubectl apply -f infrastructure\namespaces.yaml