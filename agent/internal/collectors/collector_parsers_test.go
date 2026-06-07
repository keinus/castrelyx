package collectors

import (
	"strings"
	"testing"
)

func TestParseProcNetDevExtractsInterfaceTrafficBytes(t *testing.T) {
	input := `Inter-|   Receive                                                |  Transmit
 face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
    lo: 1000      10    0    0    0     0          0         0 2000      20    0    0    0     0       0          0
  eth0: 123456    11    0    0    0     0          0         0 654321    22    0    0    0     0       0          0
`

	stats := parseProcNetDev(strings.NewReader(input))

	if len(stats) != 2 {
		t.Fatalf("stats length = %d", len(stats))
	}
	if stats[1].Name != "eth0" || stats[1].RxBytes != 123456 || stats[1].TxBytes != 654321 {
		t.Fatalf("unexpected eth0 stats: %#v", stats[1])
	}
}

func TestParseDfOutputExtractsMountUsage(t *testing.T) {
	input := `Filesystem 1B-blocks Used Available Use% Mounted on
/dev/sda1 1000 400 600 40% /
/dev/sdb1 2000 1500 500 75% /data
`

	mounts := parseDfOutput(strings.NewReader(input))

	if len(mounts) != 2 {
		t.Fatalf("mounts length = %d", len(mounts))
	}
	if mounts[1].MountPoint != "/data" || mounts[1].TotalBytes != 2000 || mounts[1].UsedBytes != 1500 || mounts[1].AvailableBytes != 500 {
		t.Fatalf("unexpected mount usage: %#v", mounts[1])
	}
}

func TestParseProcNetSocketsExtractsListenAndEstablishedSockets(t *testing.T) {
	input := `  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
   0: 0100007F:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000   100        0 12345 1 0000000000000000 100 0 0 10 0
   1: 0A00000A:9C40 0200000A:01BB 01 00000000:00000000 00:00000000 00000000   100        0 12346 1 0000000000000000 100 0 0 10 0
`

	sockets := parseProcNetSockets(strings.NewReader(input), "tcp")

	if len(sockets) != 2 {
		t.Fatalf("sockets length = %d", len(sockets))
	}
	if sockets[0].State != "listen" || sockets[0].LocalPort != 8080 || sockets[0].RemoteAddress != "0.0.0.0" {
		t.Fatalf("unexpected listening socket: %#v", sockets[0])
	}
	if sockets[1].State != "established" || sockets[1].LocalAddress != "10.0.0.10" || sockets[1].RemoteAddress != "10.0.0.2" || sockets[1].RemotePort != 443 {
		t.Fatalf("unexpected established socket: %#v", sockets[1])
	}
}

func TestLinkSocketsToProcessesAddsProcessIdentityAndSocketKeys(t *testing.T) {
	sockets := []socketState{
		{
			Protocol:      "tcp",
			LocalAddress:  "127.0.0.1",
			LocalPort:     8080,
			RemoteAddress: "0.0.0.0",
			RemotePort:    0,
			State:         "listen",
			Inode:         "12345",
		},
		{
			Protocol:      "tcp",
			LocalAddress:  "10.0.0.10",
			LocalPort:     40000,
			RemoteAddress: "10.0.0.2",
			RemotePort:    443,
			State:         "established",
			Inode:         "12346",
		},
	}
	processes := []processState{
		{PID: 100, Name: "nginx", SocketInodes: []string{"12345"}},
		{PID: 200, Name: "agent", SocketInodes: []string{"12346"}},
	}

	linkSocketsToProcesses(sockets, processes)

	if sockets[0].ProcessID == nil || *sockets[0].ProcessID != 100 || sockets[0].ProcessName != "nginx" {
		t.Fatalf("listening socket not linked: %#v", sockets[0])
	}
	if sockets[1].ProcessID == nil || *sockets[1].ProcessID != 200 || sockets[1].ProcessName != "agent" {
		t.Fatalf("established socket not linked: %#v", sockets[1])
	}
	if len(processes[0].SocketKeys) != 1 || processes[0].ListeningSocketCount != 1 {
		t.Fatalf("process listening sockets not summarized: %#v", processes[0])
	}
	if len(processes[1].SocketKeys) != 1 || processes[1].ConnectedSocketCount != 1 {
		t.Fatalf("process connected sockets not summarized: %#v", processes[1])
	}
}

func TestParsePackageInventoryExtractsNamesAndVersions(t *testing.T) {
	dpkg := "bash\t5.2.15-2+b2\tamd64\ncoreutils\t9.1-1\tamd64\n"
	packages := parseDpkgPackages(strings.NewReader(dpkg))

	if len(packages) != 2 {
		t.Fatalf("packages length = %d", len(packages))
	}
	if packages[0].Name != "bash" || packages[0].Version != "5.2.15-2+b2" || packages[0].Source != "dpkg" {
		t.Fatalf("unexpected package: %#v", packages[0])
	}
}

func TestParseSystemctlServiceOutputExtractsServiceState(t *testing.T) {
	input := "ssh.service loaded active running OpenBSD Secure Shell server\ncron.service loaded inactive dead Regular background program processing daemon\n"

	services := parseSystemctlServices(strings.NewReader(input))

	if len(services) != 2 {
		t.Fatalf("services length = %d", len(services))
	}
	if services[0].Name != "ssh.service" || services[0].Status != "running" {
		t.Fatalf("unexpected service: %#v", services[0])
	}
	if services[1].Status != "stopped" {
		t.Fatalf("inactive service status = %q", services[1].Status)
	}
}

func TestBuildLogEventItemCreatesPlatformLogEvent(t *testing.T) {
	item := buildLogEventItem("linux", "/var/log/auth.log", "SSH login failed for alice")

	if item.Kind != "event" || item.Type != "log" {
		t.Fatalf("unexpected log item metadata: %#v", item)
	}
	payload := item.Payload.(map[string]any)
	if payload["platform"] != "linux" || payload["source_name"] != "/var/log/auth.log" || payload["message"] != "SSH login failed for alice" {
		t.Fatalf("unexpected log payload: %#v", payload)
	}
}
