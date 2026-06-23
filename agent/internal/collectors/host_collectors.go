package collectors

import (
	"bufio"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"time"

	"castrelyx/agent/internal/envelope"
)

const commandTimeout = 5 * time.Second

var AgentVersion = "unknown"

var UpdateStatusProvider func() (channel string, status string, lastError string)

type networkInterfaceTraffic struct {
	Name    string
	RxBytes uint64
	TxBytes uint64
}

type mountUsage struct {
	Filesystem     string
	MountPoint     string
	TotalBytes     uint64
	UsedBytes      uint64
	AvailableBytes uint64
	UsedPercent    float64
}

type diskIOStats struct {
	Device       string
	ReadOps      uint64
	WriteOps     uint64
	ReadBytes    uint64
	WriteBytes   uint64
	IOTimeMillis uint64
	ReadBps      *float64
	WriteBps     *float64
	ReadIops     *float64
	WriteIops    *float64
	IOUtilPct    *float64
}

type cpuTimes struct {
	User    uint64
	Nice    uint64
	System  uint64
	Idle    uint64
	IOWait  uint64
	IRQ     uint64
	SoftIRQ uint64
	Steal   uint64
}

type socketState struct {
	Protocol      string
	LocalAddress  string
	LocalPort     int
	RemoteAddress string
	RemotePort    int
	State         string
	Inode         string
	ProcessID     *int
	ProcessName   string
}

type processState struct {
	PID                  int
	ParentPID            *int
	Name                 string
	ExecutablePath       string
	User                 string
	MemoryBytes          *uint64
	SocketInodes         []string
	SocketKeys           []string
	ListeningSocketCount int
	ConnectedSocketCount int
}

type packageState struct {
	Name         string
	Version      string
	Architecture string
	Vendor       string
	InstallTime  string
	Source       string
}

type serviceState struct {
	Name              string
	DisplayName       string
	Status            string
	StartupType       string
	BinaryPath        string
	User              string
	LastStateChangeAt string
}

type firewallState struct {
	Backend   string
	Profile   string
	Enabled   *bool
	RuleCount *int
}

type processCollector struct{}

func (processCollector) Name() string { return "process" }

func (processCollector) Collect(context.Context) ([]envelope.Item, error) {
	processes := collectProcessStates()
	sockets := collectSocketStates()
	linkSocketsToProcesses(sockets, processes)

	items := make([]envelope.Item, 0, len(processes))
	for _, process := range processes {
		items = append(items, process.Item())
	}
	return items, nil
}

type serviceCollector struct{}

func (serviceCollector) Name() string { return "service" }

func (serviceCollector) Collect(context.Context) ([]envelope.Item, error) {
	services := collectServiceStates()
	items := make([]envelope.Item, 0, len(services))
	for _, service := range services {
		items = append(items, service.Item())
	}
	return items, nil
}

type packageCollector struct{}

func (packageCollector) Name() string { return "package" }

func (packageCollector) Collect(context.Context) ([]envelope.Item, error) {
	packages := collectPackageStates()
	items := make([]envelope.Item, 0, len(packages))
	for _, pkg := range packages {
		items = append(items, pkg.Item())
	}
	return items, nil
}

type firewallCollector struct{}

func (firewallCollector) Name() string { return "firewall" }

func (firewallCollector) Collect(context.Context) ([]envelope.Item, error) {
	firewalls := collectFirewallStates()
	items := make([]envelope.Item, 0, len(firewalls))
	for _, firewall := range firewalls {
		items = append(items, firewall.Item())
	}
	return items, nil
}

type logTailerCollector struct{}

func (logTailerCollector) Name() string { return "log_tailer" }

func (logTailerCollector) Collect(context.Context) ([]envelope.Item, error) {
	return collectPlatformLogEvents(), nil
}

type agentHealthCollector struct{}

func (agentHealthCollector) Name() string { return "agent_health" }

func (agentHealthCollector) Collect(context.Context) ([]envelope.Item, error) {
	updateChannel := ""
	updateStatus := "unknown"
	updateLastError := ""
	if UpdateStatusProvider != nil {
		updateChannel, updateStatus, updateLastError = UpdateStatusProvider()
	}
	return []envelope.Item{{
		Kind: "event",
		Type: "health",
		Key:  "heartbeat",
		Payload: map[string]any{
			"status":            "ok",
			"collector":         "agent_health",
			"agent_version":     AgentVersion,
			"go_version":        runtime.Version(),
			"os":                runtime.GOOS,
			"architecture":      runtime.GOARCH,
			"process_id":        os.Getpid(),
			"update_channel":    updateChannel,
			"update_status":     updateStatus,
			"update_last_error": nullIfEmpty(updateLastError),
			"observed_at":       time.Now().UTC().Format(time.RFC3339Nano),
		},
	}}, nil
}

func collectNetworkTrafficMetricItems() []envelope.Item {
	stats := collectNetworkTraffic()
	items := make([]envelope.Item, 0, len(stats)*2)
	for _, stat := range stats {
		items = append(items,
			metricItemWithLabels("host.network.rx_bytes", stat.RxBytes, "bytes", map[string]any{
				"interface": stat.Name,
				"direction": "ingress",
			}),
			metricItemWithLabels("host.network.tx_bytes", stat.TxBytes, "bytes", map[string]any{
				"interface": stat.Name,
				"direction": "egress",
			}),
		)
	}
	return items
}

func collectDiskUsageMetricItems() []envelope.Item {
	mounts := collectDiskUsage()
	items := make([]envelope.Item, 0, len(mounts)*4)
	maxUsedPercent := 0.0
	for _, mount := range mounts {
		if mount.UsedPercent > maxUsedPercent {
			maxUsedPercent = mount.UsedPercent
		}
		labels := map[string]any{
			"filesystem":  mount.Filesystem,
			"mount_point": mount.MountPoint,
		}
		items = append(items,
			metricItemWithLabels("host.disk.total_bytes", mount.TotalBytes, "bytes", labels),
			metricItemWithLabels("host.disk.used_bytes", mount.UsedBytes, "bytes", labels),
			metricItemWithLabels("host.disk.available_bytes", mount.AvailableBytes, "bytes", labels),
			metricItemWithLabels("host.disk.used_percent", mount.UsedPercent, "percent", labels),
		)
	}
	if len(mounts) > 0 {
		items = append(items, metricItem("disk.usage", maxUsedPercent, "percent"))
	}
	return items
}

func collectDiskIOMetricItems() []envelope.Item {
	stats := collectDiskIOStats()
	items := make([]envelope.Item, 0, len(stats)*5)
	for _, stat := range stats {
		labels := map[string]any{"device": stat.Device}
		if stat.ReadBps != nil || stat.WriteBps != nil || stat.ReadIops != nil || stat.WriteIops != nil || stat.IOUtilPct != nil {
			if stat.ReadBps != nil {
				items = append(items, metricItemWithLabels("host.disk.read_bps", *stat.ReadBps, "Bps", labels))
			}
			if stat.WriteBps != nil {
				items = append(items, metricItemWithLabels("host.disk.write_bps", *stat.WriteBps, "Bps", labels))
			}
			if stat.ReadIops != nil {
				items = append(items, metricItemWithLabels("host.disk.read_iops", *stat.ReadIops, "iops", labels))
			}
			if stat.WriteIops != nil {
				items = append(items, metricItemWithLabels("host.disk.write_iops", *stat.WriteIops, "iops", labels))
			}
			if stat.IOUtilPct != nil {
				items = append(items, metricItemWithLabels("host.disk.io_utilization_pct", *stat.IOUtilPct, "percent", labels))
			}
			continue
		}
		items = append(items,
			metricItemWithLabels("host.disk.read_bytes", stat.ReadBytes, "bytes", labels),
			metricItemWithLabels("host.disk.write_bytes", stat.WriteBytes, "bytes", labels),
			metricItemWithLabels("host.disk.read_ops", stat.ReadOps, "count", labels),
			metricItemWithLabels("host.disk.write_ops", stat.WriteOps, "count", labels),
			metricItemWithLabels("host.disk.io_time_ms", stat.IOTimeMillis, "milliseconds", labels),
		)
	}
	return items
}

func collectLinuxCPUMetricItems(cpuCount int) []envelope.Item {
	first, ok := readProcStatCPU()
	if !ok {
		return nil
	}
	time.Sleep(100 * time.Millisecond)
	second, ok := readProcStatCPU()
	if !ok {
		return nil
	}
	usage, ok := cpuUsagePercent(first, second)
	if !ok {
		return nil
	}
	return []envelope.Item{
		metricItem("cpu.usage", usage, "percent"),
		metricItem("host.cpu.usage_percent", usage, "percent"),
		metricItem("host.cpu.count", cpuCount, "count"),
	}
}

func collectLinuxLoadMetricItems(cpuCount int) []envelope.Item {
	f, err := os.Open("/proc/loadavg")
	if err != nil {
		return nil
	}
	defer f.Close()
	load1, load5, load15, ok := parseLoadAverage(f)
	if !ok {
		return nil
	}
	items := []envelope.Item{
		metricItem("host.load.1", load1, "count"),
		metricItem("host.load.5", load5, "count"),
		metricItem("host.load.15", load15, "count"),
	}
	if cpuCount > 0 {
		items = append(items, metricItem("host.load.normalized_1", load1/float64(cpuCount)*100, "percent"))
	}
	return items
}

func collectWindowsHostMetricItems(cpuCount int) []envelope.Item {
	items := []envelope.Item{}
	if out, err := runPowerShell(`Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average | Select-Object Average | ConvertTo-Json -Compress`); err == nil {
		for _, row := range decodeJSONObjects(out) {
			if usage := jsonFloat(row, "Average"); usage > 0 {
				items = append(items, metricItem("cpu.usage", usage, "percent"))
				items = append(items, metricItem("host.cpu.usage_percent", usage, "percent"))
				break
			}
		}
	}
	if out, err := runPowerShell(`Get-CimInstance Win32_OperatingSystem | Select-Object TotalVisibleMemorySize,FreePhysicalMemory | ConvertTo-Json -Compress`); err == nil {
		for _, row := range decodeJSONObjects(out) {
			total := jsonFloat(row, "TotalVisibleMemorySize") * 1024
			free := jsonFloat(row, "FreePhysicalMemory") * 1024
			if total > 0 {
				items = append(items,
					metricItem("host.memory.total_bytes", total, "bytes"),
					metricItem("host.memory.available_bytes", free, "bytes"),
					metricItem("memory.usage", (total-free)*100/total, "percent"),
				)
				break
			}
		}
	}
	items = append(items, metricItem("host.cpu.count", cpuCount, "count"))
	return items
}

func readProcStatCPU() (cpuTimes, bool) {
	f, err := os.Open("/proc/stat")
	if err != nil {
		return cpuTimes{}, false
	}
	defer f.Close()
	return parseProcStatCPU(f)
}

func parseProcStatCPU(r io.Reader) (cpuTimes, bool) {
	scanner := bufio.NewScanner(r)
	for scanner.Scan() {
		fields := strings.Fields(scanner.Text())
		if len(fields) < 5 || fields[0] != "cpu" {
			continue
		}
		values := make([]uint64, 8)
		for i := 0; i < len(values) && i+1 < len(fields); i++ {
			value, err := strconv.ParseUint(fields[i+1], 10, 64)
			if err != nil {
				return cpuTimes{}, false
			}
			values[i] = value
		}
		return cpuTimes{
			User:    values[0],
			Nice:    values[1],
			System:  values[2],
			Idle:    values[3],
			IOWait:  values[4],
			IRQ:     values[5],
			SoftIRQ: values[6],
			Steal:   values[7],
		}, true
	}
	return cpuTimes{}, false
}

func cpuUsagePercent(first, second cpuTimes) (float64, bool) {
	firstIdle := first.Idle + first.IOWait
	secondIdle := second.Idle + second.IOWait
	firstTotal := first.User + first.Nice + first.System + firstIdle + first.IRQ + first.SoftIRQ + first.Steal
	secondTotal := second.User + second.Nice + second.System + secondIdle + second.IRQ + second.SoftIRQ + second.Steal
	if secondTotal <= firstTotal {
		return 0, false
	}
	totalDelta := secondTotal - firstTotal
	idleDelta := uint64(0)
	if secondIdle > firstIdle {
		idleDelta = secondIdle - firstIdle
	}
	if idleDelta > totalDelta {
		return 0, false
	}
	return float64(totalDelta-idleDelta) * 100 / float64(totalDelta), true
}

func parseLoadAverage(r io.Reader) (float64, float64, float64, bool) {
	data, err := io.ReadAll(r)
	if err != nil {
		return 0, 0, 0, false
	}
	fields := strings.Fields(string(data))
	if len(fields) < 3 {
		return 0, 0, 0, false
	}
	load1, err1 := strconv.ParseFloat(fields[0], 64)
	load5, err5 := strconv.ParseFloat(fields[1], 64)
	load15, err15 := strconv.ParseFloat(fields[2], 64)
	if err1 != nil || err5 != nil || err15 != nil {
		return 0, 0, 0, false
	}
	return load1, load5, load15, true
}

func metricItemWithLabels(name string, value any, unit string, labels map[string]any) envelope.Item {
	payload := map[string]any{
		"metric_name": name,
		"value":       value,
		"unit":        unit,
	}
	for key, labelValue := range labels {
		payload[key] = labelValue
	}
	keyParts := []string{name}
	if iface, ok := labels["interface"].(string); ok && iface != "" {
		keyParts = append(keyParts, iface)
	}
	if mountPoint, ok := labels["mount_point"].(string); ok && mountPoint != "" {
		keyParts = append(keyParts, mountPoint)
	}
	if device, ok := labels["device"].(string); ok && device != "" {
		keyParts = append(keyParts, device)
	}
	if direction, ok := labels["direction"].(string); ok && direction != "" {
		keyParts = append(keyParts, direction)
	}
	return envelope.Item{
		Kind:    "metric",
		Type:    strings.TrimPrefix(strings.Split(name, ".")[1], "host"),
		Key:     strings.Join(keyParts, ":"),
		Payload: payload,
	}
}

func collectNetworkTraffic() []networkInterfaceTraffic {
	switch runtime.GOOS {
	case "linux":
		f, err := os.Open("/proc/net/dev")
		if err != nil {
			return nil
		}
		defer f.Close()
		return parseProcNetDev(f)
	case "windows":
		out, err := runPowerShell(`Get-NetAdapterStatistics | Select-Object Name,ReceivedBytes,SentBytes | ConvertTo-Json -Compress`)
		if err != nil {
			return nil
		}
		rows := decodeJSONObjects(out)
		stats := make([]networkInterfaceTraffic, 0, len(rows))
		for _, row := range rows {
			name := jsonString(row, "Name")
			if name == "" {
				continue
			}
			stats = append(stats, networkInterfaceTraffic{
				Name:    name,
				RxBytes: jsonUint64(row, "ReceivedBytes"),
				TxBytes: jsonUint64(row, "SentBytes"),
			})
		}
		return stats
	default:
		return nil
	}
}

func parseProcNetDev(r io.Reader) []networkInterfaceTraffic {
	scanner := bufio.NewScanner(r)
	stats := []networkInterfaceTraffic{}
	lineNumber := 0
	for scanner.Scan() {
		lineNumber++
		if lineNumber <= 2 {
			continue
		}
		line := scanner.Text()
		namePart, valuePart, ok := strings.Cut(line, ":")
		if !ok {
			continue
		}
		fields := strings.Fields(valuePart)
		if len(fields) < 16 {
			continue
		}
		rx, err := strconv.ParseUint(fields[0], 10, 64)
		if err != nil {
			continue
		}
		tx, err := strconv.ParseUint(fields[8], 10, 64)
		if err != nil {
			continue
		}
		stats = append(stats, networkInterfaceTraffic{
			Name:    strings.TrimSpace(namePart),
			RxBytes: rx,
			TxBytes: tx,
		})
	}
	return stats
}

func collectDiskUsage() []mountUsage {
	switch runtime.GOOS {
	case "windows":
		out, err := runPowerShell(`Get-CimInstance Win32_LogicalDisk | Where-Object {$_.DriveType -in 2,3} | Select-Object DeviceID,ProviderName,Size,FreeSpace | ConvertTo-Json -Compress`)
		if err != nil {
			return nil
		}
		rows := decodeJSONObjects(out)
		mounts := make([]mountUsage, 0, len(rows))
		for _, row := range rows {
			total := jsonUint64(row, "Size")
			available := jsonUint64(row, "FreeSpace")
			if total == 0 {
				continue
			}
			used := total - available
			mounts = append(mounts, mountUsage{
				Filesystem:     jsonString(row, "ProviderName"),
				MountPoint:     jsonString(row, "DeviceID"),
				TotalBytes:     total,
				UsedBytes:      used,
				AvailableBytes: available,
				UsedPercent:    float64(used) * 100 / float64(total),
			})
		}
		return mounts
	default:
		out, err := runCommand("df", "-P", "-B1")
		if err != nil {
			return nil
		}
		return parseDfOutput(strings.NewReader(out))
	}
}

func collectDiskIOStats() []diskIOStats {
	switch runtime.GOOS {
	case "linux":
		f, err := os.Open("/proc/diskstats")
		if err != nil {
			return nil
		}
		defer f.Close()
		return parseProcDiskstats(f)
	case "windows":
		return collectWindowsDiskIOStats()
	default:
		return nil
	}
}

func parseProcDiskstats(r io.Reader) []diskIOStats {
	scanner := bufio.NewScanner(r)
	stats := []diskIOStats{}
	for scanner.Scan() {
		fields := strings.Fields(scanner.Text())
		if len(fields) < 14 {
			continue
		}
		device := fields[2]
		if !isDiskIODevice(device) {
			continue
		}
		readOps, okReadOps := parseUintField(fields[3])
		readSectors, okReadSectors := parseUintField(fields[5])
		writeOps, okWriteOps := parseUintField(fields[7])
		writeSectors, okWriteSectors := parseUintField(fields[9])
		ioTimeMillis, okIOTime := parseUintField(fields[12])
		if !okReadOps || !okReadSectors || !okWriteOps || !okWriteSectors || !okIOTime {
			continue
		}
		stats = append(stats, diskIOStats{
			Device:       device,
			ReadOps:      readOps,
			WriteOps:     writeOps,
			ReadBytes:    readSectors * 512,
			WriteBytes:   writeSectors * 512,
			IOTimeMillis: ioTimeMillis,
		})
	}
	return stats
}

func collectWindowsDiskIOStats() []diskIOStats {
	command := `Get-Counter -Counter '\PhysicalDisk(*)\Disk Read Bytes/sec','\PhysicalDisk(*)\Disk Write Bytes/sec','\PhysicalDisk(*)\Disk Reads/sec','\PhysicalDisk(*)\Disk Writes/sec','\PhysicalDisk(*)\% Disk Time' -SampleInterval 1 -MaxSamples 1 | Select-Object -ExpandProperty CounterSamples | Select-Object Path,CookedValue | ConvertTo-Json -Compress`
	out, err := runPowerShell(command)
	if err != nil {
		return nil
	}
	rows := decodeJSONObjects(out)
	byDevice := map[string]*diskIOStats{}
	for _, row := range rows {
		path := strings.ToLower(jsonString(row, "Path"))
		value := jsonFloat(row, "CookedValue")
		device, counter, ok := parseWindowsPhysicalDiskCounterPath(path)
		if !ok || device == "_total" {
			continue
		}
		stat := byDevice[device]
		if stat == nil {
			stat = &diskIOStats{Device: device}
			byDevice[device] = stat
		}
		switch counter {
		case "disk read bytes/sec":
			stat.ReadBps = floatPtr(value)
		case "disk write bytes/sec":
			stat.WriteBps = floatPtr(value)
		case "disk reads/sec":
			stat.ReadIops = floatPtr(value)
		case "disk writes/sec":
			stat.WriteIops = floatPtr(value)
		case "% disk time":
			stat.IOUtilPct = floatPtr(value)
		}
	}
	stats := make([]diskIOStats, 0, len(byDevice))
	for _, stat := range byDevice {
		stats = append(stats, *stat)
	}
	sort.Slice(stats, func(i, j int) bool { return stats[i].Device < stats[j].Device })
	return stats
}

func parseWindowsPhysicalDiskCounterPath(path string) (string, string, bool) {
	start := strings.Index(path, "physicaldisk(")
	if start < 0 {
		return "", "", false
	}
	start += len("physicaldisk(")
	end := strings.Index(path[start:], ")")
	if end < 0 {
		return "", "", false
	}
	device := strings.TrimSpace(path[start : start+end])
	counter := strings.TrimPrefix(path[start+end+1:], "\\")
	if device == "" || counter == "" {
		return "", "", false
	}
	return device, counter, true
}

func isDiskIODevice(device string) bool {
	if device == "" {
		return false
	}
	excludedPrefixes := []string{"loop", "ram", "sr"}
	for _, prefix := range excludedPrefixes {
		if strings.HasPrefix(device, prefix) {
			return false
		}
	}
	return strings.HasPrefix(device, "sd") ||
		strings.HasPrefix(device, "vd") ||
		strings.HasPrefix(device, "xvd") ||
		strings.HasPrefix(device, "nvme") ||
		strings.HasPrefix(device, "dm-") ||
		strings.HasPrefix(device, "hd")
}

func parseUintField(value string) (uint64, bool) {
	parsed, err := strconv.ParseUint(value, 10, 64)
	return parsed, err == nil
}

func floatPtr(value float64) *float64 {
	return &value
}

func parseDfOutput(r io.Reader) []mountUsage {
	scanner := bufio.NewScanner(r)
	mounts := []mountUsage{}
	first := true
	for scanner.Scan() {
		if first {
			first = false
			continue
		}
		fields := strings.Fields(scanner.Text())
		if len(fields) < 6 {
			continue
		}
		total, totalErr := strconv.ParseUint(fields[1], 10, 64)
		used, usedErr := strconv.ParseUint(fields[2], 10, 64)
		available, availableErr := strconv.ParseUint(fields[3], 10, 64)
		if totalErr != nil || usedErr != nil || availableErr != nil {
			continue
		}
		usedPercentText := strings.TrimSuffix(fields[4], "%")
		usedPercent, _ := strconv.ParseFloat(usedPercentText, 64)
		mounts = append(mounts, mountUsage{
			Filesystem:     fields[0],
			MountPoint:     fields[5],
			TotalBytes:     total,
			UsedBytes:      used,
			AvailableBytes: available,
			UsedPercent:    usedPercent,
		})
	}
	return mounts
}

func collectSocketStates() []socketState {
	switch runtime.GOOS {
	case "linux":
		sockets := []socketState{}
		for _, source := range []struct {
			path     string
			protocol string
		}{
			{"/proc/net/tcp", "tcp"},
			{"/proc/net/tcp6", "tcp6"},
			{"/proc/net/udp", "udp"},
			{"/proc/net/udp6", "udp6"},
		} {
			f, err := os.Open(source.path)
			if err != nil {
				continue
			}
			sockets = append(sockets, parseProcNetSockets(f, source.protocol)...)
			_ = f.Close()
		}
		return sockets
	case "windows":
		return collectWindowsSocketStates()
	default:
		return nil
	}
}

func parseProcNetSockets(r io.Reader, protocol string) []socketState {
	scanner := bufio.NewScanner(r)
	sockets := []socketState{}
	first := true
	for scanner.Scan() {
		if first {
			first = false
			continue
		}
		fields := strings.Fields(scanner.Text())
		if len(fields) < 10 {
			continue
		}
		localAddress, localPort, ok := parseProcNetAddress(fields[1])
		if !ok {
			continue
		}
		remoteAddress, remotePort, ok := parseProcNetAddress(fields[2])
		if !ok {
			continue
		}
		state := procSocketState(protocol, fields[3])
		if state == "unknown" {
			continue
		}
		sockets = append(sockets, socketState{
			Protocol:      protocol,
			LocalAddress:  localAddress,
			LocalPort:     localPort,
			RemoteAddress: remoteAddress,
			RemotePort:    remotePort,
			State:         state,
			Inode:         fields[9],
		})
	}
	return sockets
}

func procSocketState(protocol, raw string) string {
	switch raw {
	case "0A":
		return "listen"
	case "01":
		return "established"
	case "02":
		return "syn_sent"
	case "03":
		return "syn_recv"
	case "06":
		return "time_wait"
	case "07":
		if strings.HasPrefix(protocol, "udp") {
			return "listen"
		}
		return "close"
	default:
		return "unknown"
	}
}

func collectWindowsSocketStates() []socketState {
	tcpOut, tcpErr := runPowerShell(`Get-NetTCPConnection | Where-Object {$_.State -in "Listen","Established"} | Select-Object @{Name="Protocol";Expression={"tcp"}},LocalAddress,LocalPort,RemoteAddress,RemotePort,State,OwningProcess | ConvertTo-Json -Compress`)
	udpOut, udpErr := runPowerShell(`Get-NetUDPEndpoint | Select-Object @{Name="Protocol";Expression={"udp"}},LocalAddress,LocalPort,@{Name="RemoteAddress";Expression={"0.0.0.0"}},@{Name="RemotePort";Expression={0}},@{Name="State";Expression={"Listen"}},OwningProcess | ConvertTo-Json -Compress`)
	sockets := []socketState{}
	if tcpErr == nil {
		sockets = append(sockets, parseWindowsSocketRows(tcpOut)...)
	}
	if udpErr == nil {
		sockets = append(sockets, parseWindowsSocketRows(udpOut)...)
	}
	return sockets
}

func parseWindowsSocketRows(raw string) []socketState {
	rows := decodeJSONObjects(raw)
	sockets := make([]socketState, 0, len(rows))
	for _, row := range rows {
		protocol := strings.ToLower(jsonString(row, "Protocol"))
		state := strings.ToLower(jsonString(row, "State"))
		if state == "listen" {
			state = "listen"
		}
		if protocol == "" {
			continue
		}
		pid := jsonInt(row, "OwningProcess")
		socket := socketState{
			Protocol:      protocol,
			LocalAddress:  jsonString(row, "LocalAddress"),
			LocalPort:     jsonInt(row, "LocalPort"),
			RemoteAddress: jsonString(row, "RemoteAddress"),
			RemotePort:    jsonInt(row, "RemotePort"),
			State:         state,
		}
		if pid > 0 {
			socket.ProcessID = &pid
		}
		sockets = append(sockets, socket)
	}
	return sockets
}

func linkSocketsToProcesses(sockets []socketState, processes []processState) {
	inodeToProcessIndex := map[string]int{}
	pidToProcessIndex := map[int]int{}
	for i := range processes {
		pidToProcessIndex[processes[i].PID] = i
		for _, inode := range processes[i].SocketInodes {
			inodeToProcessIndex[inode] = i
		}
	}
	for i := range sockets {
		processIndex, ok := inodeToProcessIndex[sockets[i].Inode]
		if !ok && sockets[i].ProcessID != nil {
			processIndex, ok = pidToProcessIndex[*sockets[i].ProcessID]
		}
		if !ok {
			continue
		}
		pid := processes[processIndex].PID
		sockets[i].ProcessID = &pid
		sockets[i].ProcessName = processes[processIndex].Name
		processes[processIndex].SocketKeys = append(processes[processIndex].SocketKeys, sockets[i].Key())
		if sockets[i].State == "listen" {
			processes[processIndex].ListeningSocketCount++
		} else if sockets[i].IsRemoteConnection() {
			processes[processIndex].ConnectedSocketCount++
		}
	}
}

func (s socketState) IsRemoteConnection() bool {
	if s.State != "established" {
		return false
	}
	return s.RemotePort > 0 && s.RemoteAddress != "" && s.RemoteAddress != "0.0.0.0" && s.RemoteAddress != "::" && s.RemoteAddress != "::0"
}

func (s socketState) Direction() string {
	if s.State == "listen" {
		return "listening"
	}
	if s.IsRemoteConnection() {
		return "connected"
	}
	return "bound"
}

func (s socketState) Key() string {
	return fmt.Sprintf("%s:%s:%d:%s:%d:%s", s.Protocol, s.LocalAddress, s.LocalPort, s.RemoteAddress, s.RemotePort, s.State)
}

func (s socketState) Item() envelope.Item {
	payload := map[string]any{
		"protocol":       s.Protocol,
		"local_address":  s.LocalAddress,
		"local_port":     s.LocalPort,
		"remote_address": s.RemoteAddress,
		"remote_port":    s.RemotePort,
		"state":          s.State,
		"direction":      s.Direction(),
		"process_name":   nullIfEmpty(s.ProcessName),
	}
	if s.ProcessID != nil {
		payload["process_id"] = *s.ProcessID
	} else {
		payload["process_id"] = nil
	}
	if s.Inode != "" {
		payload["socket_inode"] = s.Inode
	}
	return envelope.Item{
		Kind:    "state",
		Type:    "socket",
		Key:     s.Key(),
		Payload: payload,
	}
}

func collectProcessStates() []processState {
	switch runtime.GOOS {
	case "linux":
		return collectLinuxProcessStates()
	case "windows":
		return collectWindowsProcessStates()
	default:
		return nil
	}
}

func collectLinuxProcessStates() []processState {
	entries, err := os.ReadDir("/proc")
	if err != nil {
		return nil
	}
	processes := []processState{}
	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}
		pid, err := strconv.Atoi(entry.Name())
		if err != nil {
			continue
		}
		process, err := readLinuxProcessState(pid)
		if err != nil {
			continue
		}
		processes = append(processes, process)
	}
	sort.Slice(processes, func(i, j int) bool { return processes[i].PID < processes[j].PID })
	return processes
}

func readLinuxProcessState(pid int) (processState, error) {
	statPath := filepath.Join("/proc", strconv.Itoa(pid), "stat")
	statBytes, err := os.ReadFile(statPath)
	if err != nil {
		return processState{}, err
	}
	name, parentPID := parseLinuxProcStat(string(statBytes))
	exePath, _ := os.Readlink(filepath.Join("/proc", strconv.Itoa(pid), "exe"))
	user, memoryBytes := parseLinuxProcStatus(filepath.Join("/proc", strconv.Itoa(pid), "status"))
	return processState{
		PID:            pid,
		ParentPID:      parentPID,
		Name:           name,
		ExecutablePath: exePath,
		User:           user,
		MemoryBytes:    memoryBytes,
		SocketInodes:   readLinuxProcessSocketInodes(pid),
	}, nil
}

func parseLinuxProcStat(raw string) (string, *int) {
	start := strings.Index(raw, "(")
	end := strings.LastIndex(raw, ")")
	if start < 0 || end <= start {
		return "", nil
	}
	name := raw[start+1 : end]
	fields := strings.Fields(raw[end+1:])
	if len(fields) < 2 {
		return name, nil
	}
	ppid, err := strconv.Atoi(fields[1])
	if err != nil {
		return name, nil
	}
	return name, &ppid
}

func parseLinuxProcStatus(path string) (string, *uint64) {
	f, err := os.Open(path)
	if err != nil {
		return "", nil
	}
	defer f.Close()
	scanner := bufio.NewScanner(f)
	user := ""
	var memoryBytes *uint64
	for scanner.Scan() {
		line := scanner.Text()
		if strings.HasPrefix(line, "Uid:") {
			fields := strings.Fields(line)
			if len(fields) > 1 {
				user = fields[1]
			}
		}
		if strings.HasPrefix(line, "VmRSS:") {
			fields := strings.Fields(line)
			if len(fields) > 1 {
				value, err := strconv.ParseUint(fields[1], 10, 64)
				if err == nil {
					bytes := value * 1024
					memoryBytes = &bytes
				}
			}
		}
	}
	return user, memoryBytes
}

func readLinuxProcessSocketInodes(pid int) []string {
	fdDir := filepath.Join("/proc", strconv.Itoa(pid), "fd")
	entries, err := os.ReadDir(fdDir)
	if err != nil {
		return nil
	}
	inodes := []string{}
	for _, entry := range entries {
		target, err := os.Readlink(filepath.Join(fdDir, entry.Name()))
		if err != nil {
			continue
		}
		if strings.HasPrefix(target, "socket:[") && strings.HasSuffix(target, "]") {
			inodes = append(inodes, strings.TrimSuffix(strings.TrimPrefix(target, "socket:["), "]"))
		}
	}
	return inodes
}

func collectWindowsProcessStates() []processState {
	out, err := runPowerShell(`Get-CimInstance Win32_Process | Select-Object ProcessId,ParentProcessId,Name,ExecutablePath,WorkingSetSize | ConvertTo-Json -Compress`)
	if err != nil {
		return nil
	}
	rows := decodeJSONObjects(out)
	processes := make([]processState, 0, len(rows))
	for _, row := range rows {
		pid := jsonInt(row, "ProcessId")
		if pid <= 0 {
			continue
		}
		parent := jsonInt(row, "ParentProcessId")
		var parentPID *int
		if parent > 0 {
			parentPID = &parent
		}
		memory := jsonUint64(row, "WorkingSetSize")
		var memoryPtr *uint64
		if memory > 0 {
			memoryPtr = &memory
		}
		processes = append(processes, processState{
			PID:            pid,
			ParentPID:      parentPID,
			Name:           jsonString(row, "Name"),
			ExecutablePath: jsonString(row, "ExecutablePath"),
			MemoryBytes:    memoryPtr,
		})
	}
	return processes
}

func (p processState) Item() envelope.Item {
	payload := map[string]any{
		"pid":                    p.PID,
		"parent_pid":             nil,
		"name":                   p.Name,
		"executable_path":        nullIfEmpty(p.ExecutablePath),
		"command_line":           nil,
		"user":                   nullIfEmpty(p.User),
		"cpu_percent":            nil,
		"memory_bytes":           nil,
		"started_at":             nil,
		"socket_keys":            p.SocketKeys,
		"listening_socket_count": p.ListeningSocketCount,
		"connected_socket_count": p.ConnectedSocketCount,
	}
	if p.ParentPID != nil {
		payload["parent_pid"] = *p.ParentPID
	}
	if p.MemoryBytes != nil {
		payload["memory_bytes"] = *p.MemoryBytes
	}
	return envelope.Item{
		Kind:    "state",
		Type:    "process",
		Key:     strconv.Itoa(p.PID),
		Payload: payload,
	}
}

func collectPackageStates() []packageState {
	switch runtime.GOOS {
	case "linux":
		for _, candidate := range []struct {
			command string
			args    []string
			parser  func(io.Reader) []packageState
		}{
			{"dpkg-query", []string{"-W", "-f=${binary:Package}\\t${Version}\\t${Architecture}\\n"}, parseDpkgPackages},
			{"rpm", []string{"-qa", "--qf", "%{NAME}\\t%{VERSION}-%{RELEASE}\\t%{ARCH}\\n"}, parseRpmPackages},
			{"pacman", []string{"-Q"}, parsePacmanPackages},
			{"apk", []string{"info", "-vv"}, parseApkPackages},
		} {
			out, err := runCommand(candidate.command, candidate.args...)
			if err != nil || strings.TrimSpace(out) == "" {
				continue
			}
			packages := candidate.parser(strings.NewReader(out))
			if len(packages) > 0 {
				return packages
			}
		}
	case "windows":
		out, err := runPowerShell(`$paths = 'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*','HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*'; Get-ItemProperty $paths -ErrorAction SilentlyContinue | Where-Object {$_.DisplayName} | Select-Object DisplayName,DisplayVersion,Publisher,InstallDate | ConvertTo-Json -Compress`)
		if err != nil {
			return nil
		}
		rows := decodeJSONObjects(out)
		packages := make([]packageState, 0, len(rows))
		for _, row := range rows {
			name := jsonString(row, "DisplayName")
			if name == "" {
				continue
			}
			packages = append(packages, packageState{
				Name:        name,
				Version:     jsonString(row, "DisplayVersion"),
				Vendor:      jsonString(row, "Publisher"),
				InstallTime: jsonString(row, "InstallDate"),
				Source:      "registry",
			})
		}
		return packages
	}
	return nil
}

func parseDpkgPackages(r io.Reader) []packageState {
	return parseTabularPackages(r, "dpkg")
}

func parseRpmPackages(r io.Reader) []packageState {
	return parseTabularPackages(r, "rpm")
}

func parseTabularPackages(r io.Reader, source string) []packageState {
	scanner := bufio.NewScanner(r)
	packages := []packageState{}
	for scanner.Scan() {
		fields := strings.Split(scanner.Text(), "\t")
		if len(fields) < 2 || strings.TrimSpace(fields[0]) == "" {
			continue
		}
		pkg := packageState{
			Name:    strings.TrimSpace(fields[0]),
			Version: strings.TrimSpace(fields[1]),
			Source:  source,
		}
		if len(fields) > 2 {
			pkg.Architecture = strings.TrimSpace(fields[2])
		}
		packages = append(packages, pkg)
	}
	return packages
}

func parsePacmanPackages(r io.Reader) []packageState {
	scanner := bufio.NewScanner(r)
	packages := []packageState{}
	for scanner.Scan() {
		fields := strings.Fields(scanner.Text())
		if len(fields) < 2 {
			continue
		}
		packages = append(packages, packageState{Name: fields[0], Version: fields[1], Source: "pacman"})
	}
	return packages
}

func parseApkPackages(r io.Reader) []packageState {
	scanner := bufio.NewScanner(r)
	packages := []packageState{}
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" {
			continue
		}
		name, version, ok := strings.Cut(line, "-")
		if !ok {
			continue
		}
		packages = append(packages, packageState{Name: name, Version: version, Source: "apk"})
	}
	return packages
}

func (p packageState) Item() envelope.Item {
	return envelope.Item{
		Kind: "state",
		Type: "package",
		Key:  p.Name,
		Payload: map[string]any{
			"name":         p.Name,
			"version":      nullIfEmpty(p.Version),
			"vendor":       nullIfEmpty(p.Vendor),
			"architecture": nullIfEmpty(p.Architecture),
			"install_time": nullIfEmpty(p.InstallTime),
			"source":       p.Source,
		},
	}
}

func collectServiceStates() []serviceState {
	switch runtime.GOOS {
	case "linux":
		out, err := runCommand("systemctl", "list-units", "--type=service", "--all", "--no-pager", "--no-legend")
		if err != nil {
			return nil
		}
		return parseSystemctlServices(strings.NewReader(out))
	case "windows":
		out, err := runPowerShell(`Get-Service | Select-Object Name,DisplayName,Status,StartType | ConvertTo-Json -Compress`)
		if err != nil {
			return nil
		}
		rows := decodeJSONObjects(out)
		services := make([]serviceState, 0, len(rows))
		for _, row := range rows {
			status := strings.ToLower(jsonString(row, "Status"))
			if status == "running" {
				status = "running"
			} else if status != "" {
				status = "stopped"
			} else {
				status = "unknown"
			}
			services = append(services, serviceState{
				Name:        jsonString(row, "Name"),
				DisplayName: jsonString(row, "DisplayName"),
				Status:      status,
				StartupType: strings.ToLower(jsonString(row, "StartType")),
			})
		}
		return services
	default:
		return nil
	}
}

func parseSystemctlServices(r io.Reader) []serviceState {
	scanner := bufio.NewScanner(r)
	services := []serviceState{}
	for scanner.Scan() {
		fields := strings.Fields(scanner.Text())
		if len(fields) < 4 {
			continue
		}
		status := "unknown"
		switch fields[2] {
		case "active":
			if fields[3] == "running" {
				status = "running"
			} else {
				status = fields[3]
			}
		case "inactive":
			status = "stopped"
		case "failed":
			status = "failed"
		default:
			status = fields[2]
		}
		displayName := ""
		if len(fields) > 4 {
			displayName = strings.Join(fields[4:], " ")
		}
		services = append(services, serviceState{
			Name:        fields[0],
			DisplayName: displayName,
			Status:      status,
			StartupType: "unknown",
		})
	}
	return services
}

func (s serviceState) Item() envelope.Item {
	return envelope.Item{
		Kind: "state",
		Type: "service",
		Key:  s.Name,
		Payload: map[string]any{
			"name":                 s.Name,
			"display_name":         nullIfEmpty(s.DisplayName),
			"status":               nullIfEmpty(s.Status),
			"startup_type":         nullIfEmpty(s.StartupType),
			"binary_path":          nullIfEmpty(s.BinaryPath),
			"user":                 nullIfEmpty(s.User),
			"last_state_change_at": nullIfEmpty(s.LastStateChangeAt),
		},
	}
}

func collectFirewallStates() []firewallState {
	switch runtime.GOOS {
	case "linux":
		return collectLinuxFirewallStates()
	case "windows":
		out, err := runPowerShell(`Get-NetFirewallProfile | Select-Object Name,Enabled | ConvertTo-Json -Compress`)
		if err != nil {
			return nil
		}
		rows := decodeJSONObjects(out)
		states := make([]firewallState, 0, len(rows))
		for _, row := range rows {
			enabled := jsonBool(row, "Enabled")
			states = append(states, firewallState{
				Backend: "windows_firewall",
				Profile: jsonString(row, "Name"),
				Enabled: &enabled,
			})
		}
		return states
	default:
		return nil
	}
}

func collectLinuxFirewallStates() []firewallState {
	states := []firewallState{}
	if out, err := runCommand("ufw", "status"); err == nil {
		enabled := strings.Contains(strings.ToLower(out), "status: active")
		ruleCount := countNonHeaderLines(out)
		states = append(states, firewallState{Backend: "ufw", Enabled: &enabled, RuleCount: &ruleCount})
	}
	if out, err := runCommand("firewall-cmd", "--state"); err == nil {
		enabled := strings.Contains(strings.ToLower(out), "running")
		states = append(states, firewallState{Backend: "firewalld", Enabled: &enabled})
	}
	if out, err := runCommand("nft", "list", "ruleset"); err == nil && strings.TrimSpace(out) != "" {
		enabled := true
		ruleCount := strings.Count(out, "\n")
		states = append(states, firewallState{Backend: "nftables", Enabled: &enabled, RuleCount: &ruleCount})
	}
	if out, err := runCommand("iptables", "-S"); err == nil && strings.TrimSpace(out) != "" {
		enabled := true
		ruleCount := countNonHeaderLines(out)
		states = append(states, firewallState{Backend: "iptables", Enabled: &enabled, RuleCount: &ruleCount})
	}
	return states
}

func countNonHeaderLines(text string) int {
	count := 0
	for _, line := range strings.Split(text, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(strings.ToLower(line), "status:") {
			continue
		}
		count++
	}
	return count
}

func (f firewallState) Item() envelope.Item {
	key := f.Backend
	if f.Profile != "" {
		key += ":" + f.Profile
	}
	payload := map[string]any{
		"backend":    f.Backend,
		"profile":    nullIfEmpty(f.Profile),
		"enabled":    nil,
		"rule_count": nil,
	}
	if f.Enabled != nil {
		payload["enabled"] = *f.Enabled
	}
	if f.RuleCount != nil {
		payload["rule_count"] = *f.RuleCount
	}
	return envelope.Item{Kind: "state", Type: "firewall", Key: key, Payload: payload}
}

func collectPlatformLogEvents() []envelope.Item {
	switch runtime.GOOS {
	case "linux":
		return collectLinuxLogEvents()
	case "windows":
		return collectWindowsLogEvents()
	default:
		return nil
	}
}

func collectLinuxLogEvents() []envelope.Item {
	sources := []string{
		"/var/log/auth.log",
		"/var/log/secure",
		"/var/log/syslog",
		"/var/log/messages",
	}
	items := []envelope.Item{}
	for _, source := range sources {
		lines := tailFileLines(source, 50)
		for _, line := range lines {
			if strings.TrimSpace(line) == "" {
				continue
			}
			items = append(items, buildLogEventItem("linux", source, line))
		}
	}
	if out, err := runCommand("journalctl", "-n", "50", "--no-pager", "--output", "short-iso"); err == nil {
		for _, line := range strings.Split(out, "\n") {
			if strings.TrimSpace(line) != "" {
				items = append(items, buildLogEventItem("linux", "journald", line))
			}
		}
	}
	return items
}

func collectWindowsLogEvents() []envelope.Item {
	sources := []string{
		"Security",
		"System",
		"Application",
		"Windows PowerShell",
		"Microsoft-Windows-PowerShell/Operational",
		"Microsoft-Windows-TerminalServices-LocalSessionManager/Operational",
		"Microsoft-Windows-Windows Defender/Operational",
	}
	items := []envelope.Item{}
	for _, source := range sources {
		command := fmt.Sprintf(`Get-WinEvent -LogName %q -MaxEvents 20 -ErrorAction SilentlyContinue | Select-Object TimeCreated,Id,ProviderName,LevelDisplayName,Message | ConvertTo-Json -Compress`, source)
		out, err := runPowerShell(command)
		if err != nil {
			continue
		}
		rows := decodeJSONObjects(out)
		for _, row := range rows {
			message := jsonString(row, "Message")
			if message == "" {
				continue
			}
			item := buildLogEventItem("windows", source, message)
			payload := item.Payload.(map[string]any)
			payload["event_id"] = jsonInt(row, "Id")
			payload["provider"] = nullIfEmpty(jsonString(row, "ProviderName"))
			payload["level"] = nullIfEmpty(jsonString(row, "LevelDisplayName"))
			payload["time_created"] = nullIfEmpty(jsonString(row, "TimeCreated"))
			items = append(items, item)
		}
	}
	return items
}

func buildLogEventItem(platform, sourceName, message string) envelope.Item {
	dedup := hashText(platform + "\x00" + sourceName + "\x00" + message)
	return envelope.Item{
		Kind: "event",
		Type: "log",
		Key:  sourceName + ":" + dedup[:16],
		Payload: map[string]any{
			"event_type":  "system",
			"platform":    platform,
			"source":      "agent",
			"source_name": sourceName,
			"observed_at": time.Now().UTC().Format(time.RFC3339Nano),
			"actor":       nil,
			"action":      nil,
			"outcome":     "unknown",
			"message":     message,
			"raw_ref":     nil,
			"dedup_key":   dedup,
		},
	}
}

func tailFileLines(path string, maxLines int) []string {
	file, err := os.Open(path)
	if err != nil {
		return nil
	}
	defer file.Close()
	stat, err := file.Stat()
	if err != nil {
		return nil
	}
	size := stat.Size()
	offset := size - 64*1024
	if offset < 0 {
		offset = 0
	}
	if _, err := file.Seek(offset, io.SeekStart); err != nil {
		return nil
	}
	data, err := io.ReadAll(file)
	if err != nil {
		return nil
	}
	lines := strings.Split(string(data), "\n")
	if len(lines) > maxLines {
		lines = lines[len(lines)-maxLines:]
	}
	return lines
}

func runPowerShell(command string) (string, error) {
	shell := "powershell.exe"
	if _, err := exec.LookPath(shell); err != nil {
		shell = "powershell"
	}
	return runCommand(shell, "-NoProfile", "-NonInteractive", "-Command", command)
}

func runCommand(name string, args ...string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), commandTimeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, name, args...)
	out, err := cmd.Output()
	if err != nil {
		return "", err
	}
	return string(out), nil
}

func decodeJSONObjects(raw string) []map[string]any {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return nil
	}
	rows := []map[string]any{}
	if err := json.Unmarshal([]byte(raw), &rows); err == nil {
		return rows
	}
	row := map[string]any{}
	if err := json.Unmarshal([]byte(raw), &row); err == nil {
		return []map[string]any{row}
	}
	return nil
}

func jsonString(row map[string]any, key string) string {
	value, ok := row[key]
	if !ok || value == nil {
		return ""
	}
	switch v := value.(type) {
	case string:
		return v
	case float64:
		if v == float64(int64(v)) {
			return strconv.FormatInt(int64(v), 10)
		}
		return strconv.FormatFloat(v, 'f', -1, 64)
	case bool:
		return strconv.FormatBool(v)
	default:
		return fmt.Sprint(v)
	}
}

func jsonInt(row map[string]any, key string) int {
	value, ok := row[key]
	if !ok || value == nil {
		return 0
	}
	switch v := value.(type) {
	case float64:
		return int(v)
	case int:
		return v
	case string:
		i, _ := strconv.Atoi(v)
		return i
	default:
		return 0
	}
}

func jsonUint64(row map[string]any, key string) uint64 {
	value, ok := row[key]
	if !ok || value == nil {
		return 0
	}
	switch v := value.(type) {
	case float64:
		return uint64(v)
	case uint64:
		return v
	case int:
		return uint64(v)
	case string:
		i, _ := strconv.ParseUint(v, 10, 64)
		return i
	default:
		return 0
	}
}

func jsonFloat(row map[string]any, key string) float64 {
	value, ok := row[key]
	if !ok || value == nil {
		return 0
	}
	switch v := value.(type) {
	case float64:
		return v
	case int:
		return float64(v)
	case uint64:
		return float64(v)
	case string:
		f, _ := strconv.ParseFloat(v, 64)
		return f
	default:
		return 0
	}
}

func jsonBool(row map[string]any, key string) bool {
	value, ok := row[key]
	if !ok || value == nil {
		return false
	}
	switch v := value.(type) {
	case bool:
		return v
	case string:
		b, _ := strconv.ParseBool(v)
		return b
	default:
		return false
	}
}

func nullIfEmpty(value string) any {
	if value == "" {
		return nil
	}
	return value
}

func hashText(value string) string {
	hash := sha256.Sum256([]byte(value))
	return hex.EncodeToString(hash[:])
}
