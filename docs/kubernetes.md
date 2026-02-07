# Kubernetes Deployment

This guide covers deploying sql-mcp-server to Kubernetes clusters.

## Prerequisites

- Kubernetes 1.24+
- kubectl configured
- Container registry access

## Docker Build

Build and push the image:

```bash
docker build -t your-registry/sql-mcp-server:v0.1.0 .
docker push your-registry/sql-mcp-server:v0.1.0
```

## Manifests

### ConfigMap

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: sql-mcp-config
  namespace: mcp
data:
  application.yml: |
    sql-mcp:
      transport: http
      connections:
        - name: production
          type: postgresql
          host: postgres.database.svc.cluster.local
          port: 5432
          database: appdb
          username: ${DB_USER}
          password: ${DB_PASS}
          read-only: true
      query:
        default-timeout-ms: 30000
        default-row-limit: 1000
        max-row-limit: 10000
      tables:
        deny-list:
          - "pg_*"
          - "information_schema.*"

    server:
      port: 8080

    logging:
      level:
        root: INFO
```

### Secret

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: sql-mcp-secrets
  namespace: mcp
type: Opaque
stringData:
  DB_USER: "readonly_user"
  DB_PASS: "your-secure-password"
```

### Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: sql-mcp-server
  namespace: mcp
  labels:
    app: sql-mcp-server
spec:
  replicas: 2
  selector:
    matchLabels:
      app: sql-mcp-server
  template:
    metadata:
      labels:
        app: sql-mcp-server
    spec:
      containers:
        - name: sql-mcp-server
          image: your-registry/sql-mcp-server:v0.1.0
          ports:
            - containerPort: 8080
              name: http
          env:
            - name: SQL_MCP_TRANSPORT
              value: "http"
            - name: DB_USER
              valueFrom:
                secretKeyRef:
                  name: sql-mcp-secrets
                  key: DB_USER
            - name: DB_PASS
              valueFrom:
                secretKeyRef:
                  name: sql-mcp-secrets
                  key: DB_PASS
          volumeMounts:
            - name: config
              mountPath: /app/config
              readOnly: true
          args:
            - "--spring.config.location=/app/config/application.yml"
          resources:
            requests:
              memory: "256Mi"
              cpu: "100m"
            limits:
              memory: "512Mi"
              cpu: "500m"
          livenessProbe:
            httpGet:
              path: /health
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /health
              port: 8080
            initialDelaySeconds: 5
            periodSeconds: 5
      volumes:
        - name: config
          configMap:
            name: sql-mcp-config
```

### Service

```yaml
apiVersion: v1
kind: Service
metadata:
  name: sql-mcp-server
  namespace: mcp
spec:
  selector:
    app: sql-mcp-server
  ports:
    - port: 80
      targetPort: 8080
      name: http
  type: ClusterIP
```

### Ingress (optional)

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: sql-mcp-ingress
  namespace: mcp
  annotations:
    nginx.ingress.kubernetes.io/ssl-redirect: "true"
spec:
  ingressClassName: nginx
  tls:
    - hosts:
        - sql-mcp.example.com
      secretName: sql-mcp-tls
  rules:
    - host: sql-mcp.example.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: sql-mcp-server
                port:
                  number: 80
```

## Helm Chart Values

Example `values.yaml` for a Helm chart:

```yaml
replicaCount: 2

image:
  repository: your-registry/sql-mcp-server
  tag: v0.1.0
  pullPolicy: IfNotPresent

service:
  type: ClusterIP
  port: 80

ingress:
  enabled: true
  host: sql-mcp.example.com

resources:
  requests:
    memory: 256Mi
    cpu: 100m
  limits:
    memory: 512Mi
    cpu: 500m

healthCheck:
  path: /health
  port: 8080

livenessProbe:
  enabled: true
  httpGet:
    path: /health
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10
  timeoutSeconds: 5
  failureThreshold: 3

readinessProbe:
  enabled: true
  httpGet:
    path: /health
    port: 8080
  initialDelaySeconds: 5
  periodSeconds: 5
  timeoutSeconds: 3
  failureThreshold: 3

config:
  transport: http
  connections:
    - name: production
      type: postgresql
      host: postgres.database.svc.cluster.local
      port: 5432
      database: appdb

secrets:
  dbUser: readonly_user
  dbPass: ""  # Set via --set secrets.dbPass=xxx
```

## Production Recommendations

### Security

- Always use `read-only: true` for database connections
- Configure table deny-lists to exclude sensitive tables
- Use Kubernetes Secrets or external secret managers (Vault, AWS Secrets Manager)
- Enable TLS for HTTP transport

### High Availability

- Run at least 2 replicas
- Configure PodDisruptionBudget
- Use connection pooling (HikariCP is enabled by default)

### Monitoring

- Mount logs volume for centralized log collection
- Configure structured JSON logging with `spring.profiles.active=prod`
- Integrate with Prometheus (metrics endpoint on roadmap)

### Resource Management

- Set memory limits based on expected query complexity
- Configure query timeouts to prevent runaway queries
- Use row limits to control response sizes

## Claude Desktop Integration (Remote)

Connect to a deployed server via HTTP/SSE:

```json
{
  "mcpServers": {
    "sql": {
      "url": "http://sql-mcp.example.com/sse",
      "transport": "sse"
    }
  }
}
```

For internal Kubernetes access:

```json
{
  "mcpServers": {
    "sql": {
      "url": "http://sql-mcp-server.mcp.svc.cluster.local/sse",
      "transport": "sse"
    }
  }
}
```
