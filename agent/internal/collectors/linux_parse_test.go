package collectors

import (
	"strings"
	"testing"
)

func TestParseMemInfoExtractsByteValues(t *testing.T) {
	values := parseMemInfo(strings.NewReader("MemTotal:       16384 kB\nMemAvailable:    4096 kB\n"))

	if values["MemTotal"] != 16*1024*1024 {
		t.Fatalf("MemTotal = %d", values["MemTotal"])
	}
	if values["MemAvailable"] != 4*1024*1024 {
		t.Fatalf("MemAvailable = %d", values["MemAvailable"])
	}
}

func TestParseProcNetPortsExtractsListeningSocket(t *testing.T) {
	input := `  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
   0: 0100007F:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000   100        0 12345 1 0000000000000000 100 0 0 10 0
   1: 0100007F:2328 0100007F:0050 01 00000000:00000000 00:00000000 00000000   100        0 12346 1 0000000000000000 100 0 0 10 0
`
	ports := parseProcNetPorts(strings.NewReader(input), "tcp")

	if len(ports) != 1 {
		t.Fatalf("ports length = %d", len(ports))
	}
	if ports[0].Protocol != "tcp" || ports[0].LocalPort != 8080 || ports[0].LocalAddress != "127.0.0.1" {
		t.Fatalf("unexpected port: %#v", ports[0])
	}
}

func TestParseProcNetAddressFormatsIPv6(t *testing.T) {
	address, port, ok := parseProcNetAddress("00000000000000000000000001000000:1F90")
	if !ok {
		t.Fatal("parseProcNetAddress returned ok=false")
	}
	if address != "::1" || port != 8080 {
		t.Fatalf("address = %q port = %d", address, port)
	}
}
