package collectors

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestWindowsCPUUsagePreservesValidZero(t *testing.T) {
	usage, ok := parseWindowsCPUUsage(`{"Average":0}`)
	if !ok {
		t.Fatal("parseWindowsCPUUsage rejected a valid zero-percent sample")
	}
	if usage != 0 {
		t.Fatalf("usage = %v, want 0", usage)
	}

	if _, ok := parseWindowsCPUUsage(`{"Average":null}`); ok {
		t.Fatal("parseWindowsCPUUsage accepted a null sample")
	}
}

func TestInternalNetworkInterfaceFilteringIsConservative(t *testing.T) {
	tests := []struct {
		name     string
		internal bool
	}{
		{name: "lo", internal: true},
		{name: "veth6f47a1", internal: true},
		{name: "docker0", internal: true},
		{name: "br-2fde3c3c1f2d", internal: true},
		{name: "cali123456", internal: true},
		{name: "eth0", internal: false},
		{name: "eno1", internal: false},
		{name: "br-uplink", internal: false},
		{name: "br0", internal: false},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if got := isInternalNetworkInterface(test.name); got != test.internal {
				t.Fatalf("isInternalNetworkInterface(%q) = %v, want %v", test.name, got, test.internal)
			}
		})
	}
}

func TestFilterDiskIOBackingDuplicatesPrefersPhysicalDevices(t *testing.T) {
	sysBlockRoot := t.TempDir()
	if err := os.MkdirAll(filepath.Join(sysBlockRoot, "dm-0", "slaves", "sda3"), 0o755); err != nil {
		t.Fatal(err)
	}

	stats := []diskIOStats{
		{Device: "sda"},
		{Device: "sdb"},
		{Device: "dm-0"},
	}
	filtered := filterDiskIOBackingDuplicates(stats, sysBlockRoot)

	if len(filtered) != 2 {
		t.Fatalf("filtered stats = %#v, want two devices", filtered)
	}
	if filtered[0].Device != "sda" || filtered[1].Device != "sdb" {
		t.Fatalf("unexpected filtered devices: %#v", filtered)
	}
}

func TestFirewallEnforcementDoesNotTreatRulesetPresenceAsEnabled(t *testing.T) {
	if state := nftablesEnforcementState("table inet filter { chain input { type filter hook input priority 0; policy accept; } }"); state != nil {
		t.Fatalf("accept-only nftables state = %v, want unknown", *state)
	}
	state := nftablesEnforcementState("table inet filter { chain input { type filter hook input priority 0; policy drop; } }")
	if state == nil || !*state {
		t.Fatalf("drop-policy nftables state = %v, want true", state)
	}

	if iptablesEnforcesPolicy("-P INPUT ACCEPT\n-P FORWARD ACCEPT\n-P OUTPUT ACCEPT\n") {
		t.Fatal("accept-only iptables policy reported enforcement")
	}
	if !iptablesEnforcesPolicy("-P INPUT ACCEPT\n-A INPUT -s 203.0.113.5 -j DROP\n") {
		t.Fatal("iptables DROP rule did not report enforcement")
	}
}

func TestHostSnapshotProviderSharesProcessAndPortScanOnce(t *testing.T) {
	scanCount := 0
	provider := newHostSnapshotProviderWithScanner(func() hostProcessSocketSnapshot {
		scanCount++
		processes := []processState{{PID: scanCount, Name: "agent", SocketInodes: []string{"100"}}}
		sockets := []socketState{{Protocol: "tcp", LocalAddress: "127.0.0.1", LocalPort: 8000 + scanCount, State: "listen", Inode: "100"}}
		linkSocketsToProcesses(sockets, processes)
		return hostProcessSocketSnapshot{Processes: processes, Sockets: sockets}
	})

	processItems, err := (processCollector{snapshotProvider: provider}).Collect(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	portItems, err := (portCollector{snapshotProvider: provider}).Collect(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if scanCount != 1 {
		t.Fatalf("scan count = %d, want 1", scanCount)
	}
	if len(processItems) != 1 || len(portItems) != 1 {
		t.Fatalf("process items = %d, port items = %d", len(processItems), len(portItems))
	}
	portPayload := portItems[0].Payload.(map[string]any)
	if portPayload["process_id"] != 1 {
		t.Fatalf("port process_id = %#v, want linked PID 1", portPayload["process_id"])
	}

	_, _ = (processCollector{snapshotProvider: provider}).Collect(context.Background())
	if scanCount != 2 {
		t.Fatalf("next batch scan count = %d, want 2", scanCount)
	}
}

func TestHostSnapshotProviderRefreshesRepeatedSingleCollector(t *testing.T) {
	scanCount := 0
	provider := newHostSnapshotProviderWithScanner(func() hostProcessSocketSnapshot {
		scanCount++
		return hostProcessSocketSnapshot{Processes: []processState{{PID: scanCount}}}
	})
	collector := processCollector{snapshotProvider: provider}

	_, _ = collector.Collect(context.Background())
	_, _ = collector.Collect(context.Background())
	if scanCount != 2 {
		t.Fatalf("scan count = %d, want 2 for two process-only batches", scanCount)
	}
}

func TestHostSnapshotProviderDoesNotCrossCollectionCycle(t *testing.T) {
	scanCount := 0
	provider := newHostSnapshotProviderWithScanner(func() hostProcessSocketSnapshot {
		scanCount++
		return hostProcessSocketSnapshot{Processes: []processState{{PID: scanCount}}}
	})
	process := processCollector{snapshotProvider: provider}
	port := portCollector{snapshotProvider: provider}

	firstCycle := time.Unix(100, 0)
	process.BeginCollectionCycle(firstCycle)
	_, _ = process.Collect(context.Background())
	// The second collector can observe the same cycle token without resetting
	// the snapshot already produced by the first collector.
	port.BeginCollectionCycle(firstCycle)
	_, _ = port.Collect(context.Background())
	if scanCount != 1 {
		t.Fatalf("same-cycle scan count = %d, want 1", scanCount)
	}

	secondCycle := firstCycle.Add(time.Second)
	process.BeginCollectionCycle(secondCycle)
	port.BeginCollectionCycle(secondCycle)
	_, _ = port.Collect(context.Background())
	if scanCount != 2 {
		t.Fatalf("scan count = %d, want 2 across collection cycles", scanCount)
	}
}

func TestBuildSharesSnapshotProviderAcrossProcessAndPortCollectors(t *testing.T) {
	collectors, err := Build([]string{"process", "metric", "port"})
	if err != nil {
		t.Fatal(err)
	}
	process, ok := collectors[0].(processCollector)
	if !ok {
		t.Fatalf("collector 0 type = %T, want processCollector", collectors[0])
	}
	port, ok := collectors[2].(portCollector)
	if !ok {
		t.Fatalf("collector 2 type = %T, want portCollector", collectors[2])
	}
	if process.snapshotProvider == nil || process.snapshotProvider != port.snapshotProvider {
		t.Fatal("process and port collectors do not share one snapshot provider")
	}
}
