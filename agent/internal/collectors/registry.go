package collectors

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"net"
	"os"
	"runtime"
	"strconv"
	"strings"
	"time"

	"castrelyx/agent/internal/agent"
	"castrelyx/agent/internal/envelope"
)

func Build(names []string) ([]agent.Collector, error) {
	collectors := make([]agent.Collector, 0, len(names))
	for _, name := range names {
		switch name {
		case "identity":
			collectors = append(collectors, identityCollector{})
		case "metric":
			collectors = append(collectors, metricCollector{})
		case "network":
			collectors = append(collectors, networkCollector{})
		case "port":
			collectors = append(collectors, portCollector{})
		case "process":
			collectors = append(collectors, processCollector{})
		case "service":
			collectors = append(collectors, serviceCollector{})
		case "package":
			collectors = append(collectors, packageCollector{})
		case "firewall":
			collectors = append(collectors, firewallCollector{})
		case "log_tailer":
			collectors = append(collectors, logTailerCollector{})
		case "agent_health":
			collectors = append(collectors, agentHealthCollector{})
		default:
			return nil, fmt.Errorf("unknown collector %q", name)
		}
	}
	return collectors, nil
}

type identityCollector struct{}

func (identityCollector) Name() string { return "identity" }

func (identityCollector) Collect(context.Context) ([]envelope.Item, error) {
	hostname, _ := os.Hostname()
	payload := map[string]any{
		"hostname":      hostname,
		"os":            runtime.GOOS,
		"architecture":  runtime.GOARCH,
		"observed_at":   time.Now().UTC().Format(time.RFC3339Nano),
		"collector":     "identity",
		"schema_target": "assets",
	}
	return []envelope.Item{{
		Kind:    "asset",
		Type:    "identity",
		Key:     hostname,
		Payload: payload,
	}}, nil
}

type metricCollector struct{}

func (metricCollector) Name() string { return "metric" }

func (metricCollector) Collect(context.Context) ([]envelope.Item, error) {
	var mem runtime.MemStats
	runtime.ReadMemStats(&mem)
	items := []envelope.Item{
		{
			Kind: "metric",
			Type: "runtime",
			Key:  "agent.runtime.goroutines",
			Payload: map[string]any{
				"metric_name": "agent.runtime.goroutines",
				"value":       runtime.NumGoroutine(),
				"unit":        "count",
			},
		},
		{
			Kind: "metric",
			Type: "runtime",
			Key:  "agent.runtime.heap_alloc_bytes",
			Payload: map[string]any{
				"metric_name": "agent.runtime.heap_alloc_bytes",
				"value":       mem.HeapAlloc,
				"unit":        "bytes",
			},
		},
		{
			Kind: "metric",
			Type: "runtime",
			Key:  "agent.host.cpu_count",
			Payload: map[string]any{
				"metric_name": "agent.host.cpu_count",
				"value":       runtime.NumCPU(),
				"unit":        "count",
			},
		},
	}

	if runtime.GOOS == "linux" {
		if f, err := os.Open("/proc/meminfo"); err == nil {
			values := parseMemInfo(f)
			_ = f.Close()
			if total, ok := values["MemTotal"]; ok {
				items = append(items, metricItem("host.memory.total_bytes", total, "bytes"))
			}
			if available, ok := values["MemAvailable"]; ok {
				items = append(items, metricItem("host.memory.available_bytes", available, "bytes"))
			}
		}
	}

	items = append(items, collectNetworkTrafficMetricItems()...)
	items = append(items, collectDiskUsageMetricItems()...)

	return items, nil
}

type networkCollector struct{}

func (networkCollector) Name() string { return "network" }

func (networkCollector) Collect(context.Context) ([]envelope.Item, error) {
	interfaces, err := net.Interfaces()
	if err != nil {
		return nil, err
	}
	items := make([]envelope.Item, 0, len(interfaces))
	for _, iface := range interfaces {
		addrs, _ := iface.Addrs()
		addresses := make([]string, 0, len(addrs))
		for _, addr := range addrs {
			addresses = append(addresses, addr.String())
		}
		status := "down"
		if iface.Flags&net.FlagUp != 0 {
			status = "up"
		}
		items = append(items, envelope.Item{
			Kind: "state",
			Type: "interface",
			Key:  iface.Name,
			Payload: map[string]any{
				"name":        iface.Name,
				"mac_address": iface.HardwareAddr.String(),
				"mtu":         iface.MTU,
				"flags":       iface.Flags.String(),
				"oper_status": status,
				"addresses":   addresses,
			},
		})
	}
	return items, nil
}

type noopCollector struct {
	name string
}

func (c noopCollector) Name() string { return c.name }

func (c noopCollector) Collect(context.Context) ([]envelope.Item, error) {
	return nil, nil
}

type portCollector struct{}

func (portCollector) Name() string { return "port" }

func (portCollector) Collect(context.Context) ([]envelope.Item, error) {
	sockets := collectSocketStates()
	processes := collectProcessStates()
	linkSocketsToProcesses(sockets, processes)

	items := make([]envelope.Item, 0, len(sockets))
	for _, socket := range sockets {
		items = append(items, socket.Item())
	}
	return items, nil
}

type portState struct {
	Protocol     string
	LocalAddress string
	LocalPort    int
	State        string
}

func metricItem(name string, value any, unit string) envelope.Item {
	return envelope.Item{
		Kind: "metric",
		Type: "host",
		Key:  name,
		Payload: map[string]any{
			"metric_name": name,
			"value":       value,
			"unit":        unit,
		},
	}
}

func parseMemInfo(r io.Reader) map[string]uint64 {
	values := map[string]uint64{}
	scanner := bufio.NewScanner(r)
	for scanner.Scan() {
		fields := strings.Fields(scanner.Text())
		if len(fields) < 2 {
			continue
		}
		key := strings.TrimSuffix(fields[0], ":")
		value, err := strconv.ParseUint(fields[1], 10, 64)
		if err != nil {
			continue
		}
		if len(fields) > 2 && strings.EqualFold(fields[2], "kb") {
			value *= 1024
		}
		values[key] = value
	}
	return values
}

func parseProcNetPorts(r io.Reader, protocol string) []portState {
	scanner := bufio.NewScanner(r)
	ports := []portState{}
	first := true
	for scanner.Scan() {
		if first {
			first = false
			continue
		}
		fields := strings.Fields(scanner.Text())
		if len(fields) < 4 {
			continue
		}
		state := fields[3]
		if protocol == "tcp" || protocol == "tcp6" {
			if state != "0A" {
				continue
			}
		}
		if protocol == "udp" || protocol == "udp6" {
			if state != "07" {
				continue
			}
		}
		address, port, ok := parseProcNetAddress(fields[1])
		if !ok {
			continue
		}
		ports = append(ports, portState{
			Protocol:     protocol,
			LocalAddress: address,
			LocalPort:    port,
			State:        "listen",
		})
	}
	return ports
}

func parseProcNetAddress(value string) (string, int, bool) {
	addressHex, portHex, ok := strings.Cut(value, ":")
	if !ok {
		return "", 0, false
	}
	port64, err := strconv.ParseInt(portHex, 16, 32)
	if err != nil {
		return "", 0, false
	}
	if len(addressHex) == 8 {
		b0, _ := strconv.ParseUint(addressHex[0:2], 16, 8)
		b1, _ := strconv.ParseUint(addressHex[2:4], 16, 8)
		b2, _ := strconv.ParseUint(addressHex[4:6], 16, 8)
		b3, _ := strconv.ParseUint(addressHex[6:8], 16, 8)
		return fmt.Sprintf("%d.%d.%d.%d", b3, b2, b1, b0), int(port64), true
	}
	if len(addressHex) == 32 {
		ipBytes := make([]byte, 16)
		for i := 0; i < 16; i++ {
			value, err := strconv.ParseUint(addressHex[i*2:i*2+2], 16, 8)
			if err != nil {
				return "", 0, false
			}
			ipBytes[i] = byte(value)
		}
		for i := 0; i < 16; i += 4 {
			ipBytes[i], ipBytes[i+3] = ipBytes[i+3], ipBytes[i]
			ipBytes[i+1], ipBytes[i+2] = ipBytes[i+2], ipBytes[i+1]
		}
		return net.IP(ipBytes).String(), int(port64), true
	}
	return addressHex, int(port64), true
}
