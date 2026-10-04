# Server 21 HTTPS endpoint

Deployed on 2026-10-04 (Asia/Seoul). The public Manager URL is
`https://192.168.50.21` on TCP 443. The Manager's HTTP endpoint remains available
inside the Compose network and at host loopback `127.0.0.1:8780`.

`manager-https` terminates TLS and proxies to `manager:8780`. The deployment uses
the verified official nginx 1.30.5 image pinned to
`nginx@sha256:0985e772fb9f729e6fa0980da05fca5d9c468e870eed43071545afa9d2e27d94`.
`nginx.conf` forwards WebSocket upgrade headers, permits streaming transfers,
resolves the Manager's current container address and sets Secure, HttpOnly and
SameSite=Lax on the Manager session cookie. The Manager receives
`SERVER_FORWARD_HEADERS_STRATEGY=native` and its HTTPS public base URL.

The deployment merges `compose.example.yml` settings into the existing
`/home/keinus/castrelyx/docker-compose.override.yml`. The `!override` tag replaces
the old public 8780 binding, rather than adding another binding. Existing
Logparser overrides and other services are retained. See the official
[Compose merge reference](https://docs.docker.com/reference/compose-file/merge/),
[nginx WebSocket guide](https://nginx.org/en/docs/http/websocket.html) and
[cookie flag directive](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_cookie_flags).

Runtime configuration is `/etc/castrelyx/manager-https/nginx.conf`. TLS material
is under `/etc/castrelyx/manager-https/tls`; the private key is root-readable only.
The dedicated server certificate has IP SAN `192.168.50.21`, DNS SAN
`castrelyx-manager.internal`, serverAuth usage and one-year validity. It is signed
by the existing internal CA; the CA private key is not mounted into the proxy.
Clients must trust this internal CA to avoid certificate warnings. Public CA
and leaf certificate copies are available on server 21 under
`/home/keinus/.codex-releases/manager-https-public/`.

`install-server21.py` is a first-install deployment script for this exact server,
not a general installer or a certificate renewal job. It requires root, PyYAML,
OpenSSL, Compose supporting `!override`, the pinned image already pulled, and the
existing CA files. It backs up the current Compose files, checks that unrelated
services are unchanged, validates nginx, recreates only Manager and its HTTPS
proxy, and restores the prior Manager endpoint if readiness fails. It refuses
to overwrite an existing HTTPS directory. Review existing settings before reuse.

The deployment backup is
`/home/keinus/.codex-releases/manager-https-20261004-025415/`.

```sh
cd /home/keinus/castrelyx
sudo docker compose ps manager manager-https
sudo docker compose exec -T manager-https nginx -t
sudo curl --cacert /etc/castrelyx/manager-https/tls/ca.crt \
  https://192.168.50.21/api/setup/status
sudo openssl x509 -in /etc/castrelyx/manager-https/tls/server.crt \
  -noout -dates -ext subjectAltName
```

For certificate renewal, issue a replacement using the same required SANs,
replace the leaf/key/fullchain files in the mounted TLS directory, validate with
`nginx -t`, then run `docker compose exec -T manager-https nginx -s reload`.
Renewal is manual; no renewal scheduler was installed.

For rollback, stop `manager-https`, restore the saved override, and run
`docker compose up -d --no-deps --no-build --pull never manager`. This restores
the previous public HTTP binding. Keep any newer unrelated deployment edits when
applying a rollback after additional changes.
