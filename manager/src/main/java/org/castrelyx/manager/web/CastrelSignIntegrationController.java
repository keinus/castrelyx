package org.castrelyx.manager.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.castrelyx.manager.integration.CastrelSignClient;
import org.castrelyx.manager.integration.IntegrationConfig;
import org.castrelyx.manager.integration.IntegrationService;
import org.castrelyx.manager.integration.IntegrationUpdateRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/integrations/castrelsign")
public class CastrelSignIntegrationController {
  private final IntegrationService integrationService;
  private final CastrelSignClient client;

  public CastrelSignIntegrationController(IntegrationService integrationService, CastrelSignClient client) {
    this.integrationService = integrationService;
    this.client = client;
  }

  @GetMapping
  public IntegrationConfig get() {
    return integrationService.get("castrelsign");
  }

  @PutMapping
  public IntegrationConfig update(@RequestBody IntegrationUpdateRequest request) {
    return integrationService.upsert("castrelsign", request);
  }

  @PostMapping("/test")
  public Map<String, Object> test() {
    return client.test();
  }

  @GetMapping("/tokens")
  public List<?> tokens() {
    return normalizeList(client.listEnrollmentTokens());
  }

  @PostMapping("/tokens")
  @ResponseStatus(HttpStatus.CREATED)
  public Object createToken(@RequestBody(required = false) Map<String, Object> request) {
    return normalize(client.createEnrollmentToken(toEnrollmentTokenRequest(request == null ? Map.of() : request)));
  }

  @PostMapping("/tokens/{id}/revoke")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revokeToken(@PathVariable long id) {
    client.revokeEnrollmentToken(id);
  }

  @GetMapping("/agents")
  public List<?> agents() {
    return normalizeList(client.listAgents());
  }

  @GetMapping("/certificates")
  public List<?> certificates() {
    return normalizeList(client.listCertificates());
  }

  @GetMapping("/audit-events")
  public List<?> auditEvents() {
    return normalizeList(client.listAuditEvents());
  }

  @PostMapping("/agents/{agentId}/block")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void blockAgent(@PathVariable String agentId) {
    client.blockAgent(agentId);
  }

  @PostMapping("/agents/{agentId}/reactivate")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void reactivateAgent(@PathVariable String agentId) {
    client.reactivateAgent(agentId);
  }

  @GetMapping("/agent-releases")
  public List<?> agentReleases() {
    return normalizeList(client.listAgentReleases());
  }

  @PostMapping(value = "/agent-releases", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public Object createAgentRelease(@RequestParam("version") String version,
      @RequestParam("os") String os,
      @RequestParam("arch") String arch,
      @RequestParam(name = "channel", defaultValue = "stable") String channel,
      @RequestParam(name = "publish", defaultValue = "false") boolean publish,
      @RequestParam("artifact") MultipartFile artifact) throws IOException {
    if (publish) {
      return normalize(client.publishAgentRelease(version, os, arch, channel, artifact.getBytes(), artifact.getOriginalFilename()));
    }
    return normalize(client.createAgentRelease(version, os, arch, channel, artifact.getBytes(), artifact.getOriginalFilename()));
  }

  @PostMapping("/agent-releases/{id}/activate")
  public Object activateAgentRelease(@PathVariable long id) {
    return normalize(client.activateAgentRelease(id));
  }

  @PostMapping("/agent-releases/{id}/revoke")
  public Object revokeAgentRelease(@PathVariable long id) {
    return normalize(client.revokeAgentRelease(id));
  }

  @GetMapping("/agent-update-policies")
  public List<?> agentUpdatePolicies() {
    return normalizeList(client.listAgentUpdatePolicies());
  }

  @PostMapping("/agent-update-policy")
  public Object updateAgentPolicy(@RequestBody(required = false) Map<String, Object> request) {
    return normalize(client.updateAgentPolicy(request == null ? Map.of() : request));
  }

  @GetMapping("/agent-update-attempts")
  public List<?> agentUpdateAttempts() {
    return normalizeList(client.listAgentUpdateAttempts());
  }

  @PostMapping("/enrollment-packages")
  public ResponseEntity<byte[]> createEnrollmentPackage(@RequestBody EnrollmentPackageRequest request) throws IOException {
    IntegrationConfig config = integrationService.get("castrelsign");
    if (config.baseUrl() == null || config.baseUrl().isBlank()) {
      throw new IllegalArgumentException("CastrelSign baseUrl is required");
    }
    String agentId = request == null || request.agentId() == null ? "" : request.agentId().trim();
    int ttlSeconds = request == null || request.ttlSeconds() == null ? 3600 : request.ttlSeconds();
    int maxUses = 1;
    String tenantId = request == null || request.tenantId() == null || request.tenantId().isBlank()
        ? "default"
        : request.tenantId().trim();
    String connectHost = hostName(config.baseUrl());
    String tlsServerName = tlsServerName(config.baseUrl());
    String tcpIngestAddr = connectHost + ":9443";
    if (!agentId.isBlank()) {
      rejectBlockedAgentPackage(agentId);
    }
    Map<String, Object> tokenRequest = new LinkedHashMap<>();
    tokenRequest.put("name", agentId.isBlank() ? "hostname auto enrollment" : agentId + " initial enrollment");
    if (!agentId.isBlank()) {
      tokenRequest.put("agent_id", agentId);
    }
    tokenRequest.put("ttl_seconds", ttlSeconds);
    tokenRequest.put("max_uses", maxUses);
    Map<?, ?> createdToken = client.createEnrollmentToken(tokenRequest);
    String enrollmentToken = stringValue(createdToken, "token");
    if (enrollmentToken == null || enrollmentToken.isBlank()) {
      throw new IllegalStateException("CastrelSign did not return enrollment token plaintext");
    }
    byte[] zip = packageZip(
        agentId,
        tenantId,
        config.baseUrl(),
        enrollmentToken,
        tlsServerName,
        tcpIngestAddr,
        client.rootCaPem(),
        client.agentUpdatePublicKeyPem());
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"castrelsign-" + safeFilename(agentId) + "-enrollment.zip\"")
        .contentType(MediaType.parseMediaType("application/zip"))
        .body(zip);
  }

  private void rejectBlockedAgentPackage(String agentId) {
    List<?> agents = client.listAgents();
    if (agents == null) {
      return;
    }
    for (Object value : agents) {
      if (!(value instanceof Map<?, ?> agent)) {
        continue;
      }
      String currentAgentId = stringValue(agent, "agent_id", "agentId");
      String status = stringValue(agent, "status");
      if (agentId.equals(currentAgentId) && "BLOCKED".equals(status)) {
        throw new IllegalArgumentException("blocked agents must be reactivated before issuing a new enrollment package");
      }
    }
  }

  private static List<?> normalizeList(List<?> values) {
    return values.stream().map(CastrelSignIntegrationController::normalize).toList();
  }

  private static Object normalize(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> normalized = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        normalized.put(camelKey(String.valueOf(entry.getKey())), normalize(entry.getValue()));
      }
      return normalized;
    }
    if (value instanceof List<?> list) {
      return list.stream().map(CastrelSignIntegrationController::normalize).toList();
    }
    return value;
  }

  private static Map<String, Object> toEnrollmentTokenRequest(Map<String, Object> request) {
    Map<String, Object> payload = new LinkedHashMap<>();
    putIfPresent(payload, "name", first(request, "name", "description"));
    putIfPresent(payload, "agent_id", first(request, "agentId", "agent_id"));
    putIfPresent(payload, "ttl_seconds", first(request, "ttlSeconds", "ttl_seconds"));
    putIfPresent(payload, "max_uses", first(request, "maxUses", "max_uses"));
    return payload;
  }

  private static void putIfPresent(Map<String, Object> target, String key, Object value) {
    if (value != null) {
      target.put(key, value);
    }
  }

  private static Object first(Map<String, Object> source, String firstKey, String secondKey) {
    Object first = source.get(firstKey);
    return first == null ? source.get(secondKey) : first;
  }

  private static String camelKey(String key) {
    StringBuilder builder = new StringBuilder();
    boolean upperNext = false;
    for (int i = 0; i < key.length(); i++) {
      char c = key.charAt(i);
      if (c == '_') {
        upperNext = true;
      } else if (upperNext) {
        builder.append(Character.toUpperCase(c));
        upperNext = false;
      } else {
        builder.append(c);
      }
    }
    return builder.toString();
  }

  private static byte[] packageZip(String agentId, String tenantId, String managerUrl, String token,
      String tlsServerName, String tcpIngestAddr, String caPem, String updatePublicKeyPem) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
      addEntry(zip, "agent.yaml", agentYaml(agentId.isBlank() ? "__HOSTNAME__" : agentId, tenantId, managerUrl, token, tlsServerName, tcpIngestAddr));
      addEntry(zip, "certs/ca.pem", caPem == null ? "" : caPem);
      addEntry(zip, "certs/update_public_key.pem", updatePublicKeyPem == null ? "" : updatePublicKeyPem);
      addEntry(zip, "bin/castrelyx-agent-linux-amd64", requiredResource("agent-binaries/castrelyx-agent-linux-amd64"));
      addEntry(zip, "bin/castrelyx-agent-windows-amd64.exe", requiredResource("agent-binaries/castrelyx-agent-windows-amd64.exe"));
      addEntry(zip, "install.bat", batchInstall());
      addEntry(zip, "install.ps1", powershellInstall());
      addEntry(zip, "install.sh", shellInstall());
      addEntry(zip, "install.md", installGuide(agentId));
    }
    return out.toByteArray();
  }

  private static void addEntry(ZipOutputStream zip, String name, String content) throws IOException {
    addEntry(zip, name, content.getBytes(StandardCharsets.UTF_8));
  }

  private static void addEntry(ZipOutputStream zip, String name, byte[] content) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(content);
    zip.closeEntry();
  }

  private static byte[] requiredResource(String path) throws IOException {
    try (InputStream in = CastrelSignIntegrationController.class.getClassLoader().getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("missing packaged agent binary: " + path);
      }
      return in.readAllBytes();
    }
  }

  private static String agentYaml(String agentId, String tenantId, String managerUrl, String token, String tlsServerName, String tcpIngestAddr) {
    return """
        manager_url: %s
        enrollment_token: %s
        agent_id: %s
        tenant_id: %s
        tls_server_name: %s
        ingest_transport: tcp_mtls
        tcp_ingest_addr: %s
        tcp_ingest_server_name: %s
        batch_interval: 30s
        sender_flush_interval: 2s
        max_spool_record_bytes: 8mb
        max_spool_bytes: 256mb
        max_spool_records: 10000
        max_spool_age: 168h
        max_batch_items: 1000
        max_batch_bytes: 4mb
        max_item_bytes: 512kb
        log_message_max_bytes: 1024
        collector_interval_identity: 1h
        collector_interval_metric: 30s
        collector_interval_network: 5m
        collector_interval_process: 2m
        collector_interval_service: 5m
        collector_interval_port: 2m
        collector_interval_package: 12h
        collector_interval_firewall: 10m
        collector_interval_log_tailer: 30s
        collector_interval_agent_health: 30s
        collector_full_interval_identity: 24h
        collector_full_interval_network: 1h
        collector_full_interval_process: 15m
        collector_full_interval_service: 1h
        collector_full_interval_port: 15m
        collector_full_interval_package: 24h
        collector_full_interval_firewall: 1h
        update_enabled: true
        update_channel: stable
        update_check_interval: 6h
        update_public_key_path: ./update_public_key.pem
        remote_tasks_enabled: true
        remote_task_interval: 10s
        file_manager_enabled: false
        file_manager_allow_delete: false
        file_manager_max_transfer_bytes: 256mb
        file_manager_poll_interval: 30s
        file_manager_roots:
          - __FILE_MANAGER_ROOT__
        collectors:
          - identity
          - metric
          - network
          - process
          - service
          - port
          - package
          - firewall
          - log_tailer
          - agent_health
        """.formatted(managerUrl, token, agentId, tenantId, tlsServerName, tcpIngestAddr, tlsServerName);
  }

  private static String batchInstall() {
    return """
        @echo off
        setlocal
        set SCRIPT_DIR=%~dp0
        powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%install.ps1" %*
        exit /b %ERRORLEVEL%
        """;
  }

  private static String powershellInstall() {
    return """
        $ErrorActionPreference = 'Stop'

        function Assert-Administrator {
          $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
          $principal = New-Object Security.Principal.WindowsPrincipal($identity)
          if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
            throw 'Run install.bat as Administrator.'
          }
        }

        Assert-Administrator
        $packageDir = Split-Path -Parent $MyInvocation.MyCommand.Path
        $installRoot = Join-Path $env:ProgramData 'Castrelyx'
        $binDir = Join-Path $installRoot 'bin'
        $certDir = Join-Path $installRoot 'certs'
        $spoolDir = Join-Path $installRoot 'spool'
        $updateDir = Join-Path $installRoot 'update'
        $fileManagerDir = Join-Path $installRoot 'files'
        $configPath = Join-Path $installRoot 'agent.yaml'
        $serviceName = 'CastrelyxAgent'
        $agentExe = Join-Path $binDir 'castrelyx-agent.exe'
        $sourceExe = Join-Path $packageDir 'bin\\castrelyx-agent-windows-amd64.exe'
        $sourceCa = Join-Path $packageDir 'certs\\ca.pem'
        $sourceUpdateKey = Join-Path $packageDir 'certs\\update_public_key.pem'
        $sourceConfig = Join-Path $packageDir 'agent.yaml'

        foreach ($path in @($installRoot, $binDir, $certDir, $spoolDir, $updateDir, $fileManagerDir)) {
          New-Item -ItemType Directory -Force -Path $path | Out-Null
        }
        foreach ($required in @($sourceExe, $sourceCa, $sourceUpdateKey, $sourceConfig)) {
          if (-not (Test-Path $required)) {
            throw "Missing package file: $required"
          }
        }

        Copy-Item -Path $sourceExe -Destination $agentExe -Force
        Copy-Item -Path $sourceCa -Destination (Join-Path $certDir 'ca.pem') -Force
        Copy-Item -Path $sourceUpdateKey -Destination (Join-Path $installRoot 'update_public_key.pem') -Force
        $hostname = [System.Net.Dns]::GetHostName()
        $agentYaml = Get-Content $sourceConfig -Raw
        $renderedConfig = $agentYaml.Replace('__HOSTNAME__', $hostname).Replace('__FILE_MANAGER_ROOT__', $fileManagerDir)
        $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
        [System.IO.File]::WriteAllText($configPath, $renderedConfig, $utf8NoBom)
        & icacls.exe $configPath /inheritance:r /grant:r 'SYSTEM:F' 'Administrators:F' | Out-Null

        $binaryPath = '"' + $agentExe + '" -config "' + $configPath + '"'
        $existing = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
        if ($existing) {
          if ($existing.Status -ne 'Stopped') {
            Stop-Service -Name $serviceName -Force -ErrorAction SilentlyContinue
            Start-Sleep -Seconds 2
          }
          & sc.exe config $serviceName binPath= $binaryPath start= auto | Out-Null
          Set-Service -Name $serviceName -StartupType Automatic
        } else {
          New-Service -Name $serviceName -BinaryPathName $binaryPath -DisplayName 'Castrelyx Agent' -Description 'Collects host telemetry for Castrelyx.' -StartupType Automatic | Out-Null
        }
        & sc.exe failure $serviceName reset= 86400 actions= restart/5000/restart/5000/restart/30000 | Out-Null
        & sc.exe failureflag $serviceName 1 | Out-Null
        Start-Service -Name $serviceName
        Write-Host "Agent ID set to $hostname."
        Write-Host "Installed Castrelyx Agent service: $serviceName"
        Write-Host "Config: $configPath"
        """;
  }

  private static String shellInstall() {
    return """
        #!/usr/bin/env sh
        set -eu

        if [ "$(id -u)" -ne 0 ]; then
          echo "Run install.sh as root or with sudo." >&2
          exit 1
        fi
        if ! command -v systemctl >/dev/null 2>&1; then
          echo "systemctl is required to install the Castrelyx Agent service." >&2
          exit 1
        fi

        package_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
        case "$(uname -m)" in
          x86_64|amd64)
            source_bin="$package_dir/bin/castrelyx-agent-linux-amd64"
            ;;
          *)
            echo "Unsupported Linux architecture: $(uname -m)" >&2
            exit 1
            ;;
        esac
        source_config="$package_dir/agent.yaml"
        source_ca="$package_dir/certs/ca.pem"
        source_update_key="$package_dir/certs/update_public_key.pem"
        config_dir="/etc/castrelyx"
        config_path="$config_dir/agent.yaml"
        state_dir="/var/lib/castrelyx-agent"
        cert_dir="$state_dir/certs"
        spool_dir="$state_dir/spool"
        update_dir="$state_dir/update"
        file_manager_dir="$state_dir/files"
        agent_bin="/usr/local/bin/castrelyx-agent"
        service_path="/etc/systemd/system/castrelyx-agent.service"

        for required in "$source_bin" "$source_config" "$source_ca" "$source_update_key"; do
          if [ ! -f "$required" ]; then
            echo "Missing package file: $required" >&2
            exit 1
          fi
        done

        install -d -o root -g root -m 0750 "$config_dir"
        install -d -o root -g root -m 0700 "$state_dir" "$cert_dir" "$spool_dir" "$update_dir" "$file_manager_dir"
        install -d -o root -g root -m 0755 /usr/local/bin
        install -o root -g root -m 0755 "$source_bin" "$agent_bin"
        install -o root -g root -m 0644 "$source_ca" "$cert_dir/ca.pem"
        install -o root -g root -m 0644 "$source_update_key" "$config_dir/update_public_key.pem"
        agent_hostname="$(hostname)"
        escaped_hostname="$(printf '%s' "$agent_hostname" | sed 's/[\\\\/&]/\\\\&/g')"
        sed -e "s/__HOSTNAME__/$escaped_hostname/g" -e 's|__FILE_MANAGER_ROOT__|/var/lib/castrelyx-agent/files|g' "$source_config" > "$config_path"
        chown root:root "$config_path" "$cert_dir/ca.pem" "$config_dir/update_public_key.pem"
        chmod 600 "$config_path"
        chmod 0644 "$cert_dir/ca.pem" "$config_dir/update_public_key.pem"
        cat > "$service_path" <<'SERVICE'
        [Unit]
        Description=Castrelyx Agent
        Wants=network-online.target
        After=network-online.target

        [Service]
        Type=simple
        User=root
        Group=root
        ExecStart=/usr/local/bin/castrelyx-agent -config /etc/castrelyx/agent.yaml
        Restart=always
        RestartSec=5s
        TimeoutStopSec=30s
        WorkingDirectory=/var/lib/castrelyx-agent
        UMask=0077

        # Keep host /proc, /proc/net, journal and service/package visibility for collectors.
        NoNewPrivileges=true
        PrivateTmp=true
        ProtectSystem=strict
        ProtectHome=read-only
        ProtectKernelTunables=true
        ProtectKernelModules=true
        ProtectControlGroups=true
        ProtectHostname=true
        ProtectClock=true
        LockPersonality=true
        MemoryDenyWriteExecute=true
        SystemCallArchitectures=native
        RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6 AF_NETLINK
        ReadOnlyPaths=/etc/castrelyx /var/log
        # The binary directory remains writable only so the signed self-updater can replace the agent.
        ReadWritePaths=/var/lib/castrelyx-agent /usr/local/bin

        LimitNOFILE=65536
        TasksMax=512
        MemoryMax=512M
        CPUQuota=50%

        [Install]
        WantedBy=multi-user.target
        SERVICE
        chown root:root "$service_path"
        chmod 0644 "$service_path"
        systemctl daemon-reload
        if systemctl is-active --quiet castrelyx-agent; then
          systemctl restart castrelyx-agent
        else
          systemctl enable --now castrelyx-agent
        fi
        echo "Agent ID set to $agent_hostname."
        echo "Installed Castrelyx Agent service: castrelyx-agent"
        echo "Config: $config_path"
        """;
  }

  private static String installGuide(String agentId) {
    String displayAgentId = agentId == null || agentId.isBlank() ? "host name auto" : agentId;
    return """
        # CastrelSign enrollment package

        Agent ID: %s

        This package contains agent.yaml, certs/ca.pem, certs/update_public_key.pem, agent binaries, and service install scripts.
        It intentionally does not contain client.key or client.pem. The agent creates the private key locally and stores the issued client certificate after first enrollment.
        After enrollment, telemetry is sent to the LogParser TCP/mTLS ingest endpoint configured in agent.yaml.

        ## Windows

        1. Extract the ZIP on the target host.
        2. Run install.bat as Administrator.
        3. The script installs files under %%ProgramData%%\\Castrelyx, registers the CastrelyxAgent automatic service, and starts it.

        ## Linux

        1. Extract the ZIP on the target host.
        2. Run sudo sh ./install.sh.
        3. The script installs /usr/local/bin/castrelyx-agent, writes /etc/castrelyx/agent.yaml, registers castrelyx-agent.service, enables it, and starts it.
        """.formatted(displayAgentId);
  }

  private static String hostName(String baseUrl) {
    return URI.create(baseUrl).getHost();
  }

  private static String tlsServerName(String baseUrl) {
    String host = hostName(baseUrl);
    if (host != null && (host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+") || host.contains(":"))) {
      return "localhost";
    }
    return host;
  }

  private static String stringValue(Map<?, ?> source, String key) {
    Object value = source.get(key);
    return value == null ? null : String.valueOf(value);
  }

  private static String stringValue(Map<?, ?> source, String firstKey, String secondKey) {
    String first = stringValue(source, firstKey);
    return first == null ? stringValue(source, secondKey) : first;
  }

  private static String safeFilename(String value) {
    if (value == null || value.isBlank()) {
      return "hostname-auto";
    }
    return value.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  public record EnrollmentPackageRequest(
      String agentId,
      String tenantId,
      Integer ttlSeconds,
      Integer maxUses,
      String tlsServerName) {
  }
}
