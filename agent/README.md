# Castrelyx Agent

Castrelyx Agent is a lightweight Go service for collecting Linux and Windows host telemetry and sending normalized batches to the Castrelyx manager.

The agent uses a one-time enrollment token only for initial registration. It generates a local ECDSA P-256 private key, submits a CSR to the manager, stores the manager-issued client certificate, and sends future ingest batches over mTLS.

## Run Once

```powershell
go run ./cmd/castrelyx-agent -config ./config.example.yaml -once
```

## Long Running

```powershell
go run ./cmd/castrelyx-agent -config ./config.example.yaml
```

## Notes

- The agent sends outbound-only HTTPS batches authenticated with a manager-issued client certificate.
- Failed batches are written to a local append-only spool file.
- Sensitive payload keys such as `token`, `password`, `secret`, `authorization`, and `api_key` are redacted before enqueue/send.
- Plain HTTP ingest endpoints are rejected.
