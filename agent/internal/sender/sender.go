package sender

import (
	"bufio"
	"bytes"
	"compress/gzip"
	"context"
	"crypto/tls"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"castrelyx/agent/internal/envelope"
)

var (
	ErrPlaintextEndpoint  = errors.New("ingest endpoint must use https")
	ErrTLSAuthentication  = errors.New("tls authentication failed")
	ErrCertificateExpired = errors.New("TLS peer certificate is expired")
)

const tcpMTLSOperationTimeout = 30 * time.Second

type Client struct {
	endpoint   string
	httpClient *http.Client
}

type TCPMTLSClient struct {
	addr      string
	tlsConfig *tls.Config
	timeout   time.Duration
	maxIdle   time.Duration
	mu        sync.Mutex
	conn      *tls.Conn
	lastUsed  time.Time
}

type DeliveryError struct {
	Code      string
	Err       error
	permanent bool
}

func (e *DeliveryError) Error() string {
	if e.Code == "" {
		return e.Err.Error()
	}
	return e.Code + ": " + e.Err.Error()
}

func (e *DeliveryError) Unwrap() error   { return e.Err }
func (e *DeliveryError) Permanent() bool { return e.permanent }

type ackResponse struct {
	Status  string `json:"status"`
	Code    string `json:"code"`
	Message string `json:"message"`
}

func New(endpoint string, tlsConfig *tls.Config) (*Client, error) {
	parsed, err := url.Parse(endpoint)
	if err != nil {
		return nil, err
	}
	if parsed.Scheme != "https" {
		return nil, ErrPlaintextEndpoint
	}
	if tlsConfig == nil {
		return nil, errors.New("tls config is required")
	}

	return &Client{
		endpoint: strings.TrimRight(endpoint, "/"),
		httpClient: &http.Client{
			Timeout: 30 * time.Second,
			Transport: &http.Transport{
				TLSClientConfig: tlsConfig,
			},
		},
	}, nil
}

func NewTCPMTLS(addr string, tlsConfig *tls.Config) (*TCPMTLSClient, error) {
	return NewTCPMTLSWithMaxIdle(addr, tlsConfig, tcpMTLSOperationTimeout/2)
}

func NewTCPMTLSWithMaxIdle(addr string, tlsConfig *tls.Config, maxIdle time.Duration) (*TCPMTLSClient, error) {
	if addr == "" {
		return nil, errors.New("tcp ingest address is required")
	}
	if _, _, err := net.SplitHostPort(addr); err != nil {
		return nil, fmt.Errorf("invalid tcp ingest address: %w", err)
	}
	if tlsConfig == nil {
		return nil, errors.New("tls config is required")
	}
	if maxIdle <= 0 || maxIdle >= tcpMTLSOperationTimeout {
		return nil, fmt.Errorf("tcp ingest max idle must be positive and less than %s", tcpMTLSOperationTimeout)
	}
	return &TCPMTLSClient{
		addr:      addr,
		tlsConfig: tlsConfig,
		timeout:   tcpMTLSOperationTimeout,
		// The Logparser retires an idle client connection after its read
		// timeout (30 seconds by default). Reconnect well before that boundary
		// while still reusing the connection for a queued burst.
		maxIdle: maxIdle,
	}, nil
}

func (c *Client) Send(ctx context.Context, batch envelope.Batch) error {
	batch.SentAt = time.Now().UTC()

	body, err := encodeGzipBatch(batch)
	if err != nil {
		return err
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.endpoint, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Content-Encoding", "gzip")

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return classifyTransportError(err)
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))

	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		permanent := resp.StatusCode >= 400 && resp.StatusCode < 500 && resp.StatusCode != http.StatusRequestTimeout && resp.StatusCode != http.StatusTooManyRequests
		return &DeliveryError{Code: fmt.Sprintf("http_%d", resp.StatusCode), Err: fmt.Errorf("ingest returned status %d", resp.StatusCode), permanent: permanent}
	}
	return nil
}

func (c *TCPMTLSClient) Send(ctx context.Context, batch envelope.Batch) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	batch.SentAt = time.Now().UTC()
	payload, err := encodeGzipBatch(batch)
	if err != nil {
		return err
	}
	if len(payload) > 8*1024*1024 {
		return &DeliveryError{Code: "frame_too_large", Err: fmt.Errorf("encoded batch is %d bytes", len(payload)), permanent: true}
	}

	reused, retryable, err := c.sendEncoded(ctx, payload)
	if err == nil || !reused || !retryable {
		return err
	}

	// The server may have retired a cached idle TLS connection immediately
	// before this write. Retry exactly once on a fresh connection. Schema 1.1
	// batch IDs let the Logparser deduplicate the rare ACK-loss case.
	_, _, err = c.sendEncoded(ctx, payload)
	return err
}

func (c *TCPMTLSClient) sendEncoded(ctx context.Context, payload []byte) (bool, bool, error) {
	tlsConn, reused, err := c.connection(ctx)
	if err != nil {
		return false, false, err
	}

	if deadline, ok := ctx.Deadline(); ok {
		_ = tlsConn.SetDeadline(deadline)
	} else {
		_ = tlsConn.SetDeadline(time.Now().Add(c.timeout))
	}

	var lengthPrefix [4]byte
	binary.BigEndian.PutUint32(lengthPrefix[:], uint32(len(payload)))
	if _, err := tlsConn.Write(lengthPrefix[:]); err != nil {
		c.closeConnection()
		return reused, true, err
	}
	if _, err := tlsConn.Write(payload); err != nil {
		c.closeConnection()
		return reused, true, err
	}

	line, err := bufio.NewReaderSize(io.LimitReader(tlsConn, 16*1024), 4096).ReadString('\n')
	if err != nil {
		c.closeConnection()
		if errors.Is(err, io.EOF) {
			return reused, true, errors.New("tcp ingest closed before ack")
		}
		return reused, true, err
	}

	var ack ackResponse
	if err := json.Unmarshal([]byte(strings.TrimSpace(line)), &ack); err != nil {
		c.closeConnection()
		return reused, false, fmt.Errorf("invalid tcp ingest ack: %w", err)
	}
	c.lastUsed = time.Now()
	switch ack.Status {
	case "accepted":
		return reused, false, nil
	case "error":
		if ack.Code == "" {
			ack.Code = "unknown"
		}
		if ack.Message == "" {
			ack.Message = "server returned NACK"
		}
		permanent := ack.Code != "queue_full" && ack.Code != "busy" && ack.Code != "retry_later"
		if permanent {
			c.closeConnection()
		}
		return reused, false, &DeliveryError{Code: ack.Code, Err: fmt.Errorf("tcp ingest nack: %s", ack.Message), permanent: permanent}
	default:
		c.closeConnection()
		return reused, false, fmt.Errorf("unexpected tcp ingest ack status %q", ack.Status)
	}
}

func (c *TCPMTLSClient) connection(ctx context.Context) (*tls.Conn, bool, error) {
	if c.conn != nil {
		if c.maxIdle <= 0 || c.lastUsed.IsZero() || time.Since(c.lastUsed) < c.maxIdle {
			return c.conn, true, nil
		}
		c.closeConnection()
	}
	dialer := &net.Dialer{Timeout: c.timeout, KeepAlive: 30 * time.Second}
	rawConn, err := dialer.DialContext(ctx, "tcp", c.addr)
	if err != nil {
		return nil, false, err
	}
	tlsConn := tls.Client(rawConn, c.tlsConfig)
	if err := tlsConn.HandshakeContext(ctx); err != nil {
		_ = rawConn.Close()
		return nil, false, classifyTransportError(err)
	}
	c.conn = tlsConn
	return tlsConn, false, nil
}

func (c *TCPMTLSClient) closeConnection() {
	if c.conn != nil {
		_ = c.conn.Close()
		c.conn = nil
	}
	c.lastUsed = time.Time{}
}

func encodeGzipBatch(batch envelope.Batch) ([]byte, error) {
	var body bytes.Buffer
	gz, err := gzip.NewWriterLevel(&body, gzip.BestSpeed)
	if err != nil {
		return nil, err
	}
	if err := json.NewEncoder(gz).Encode(batch); err != nil {
		gz.Close()
		return nil, err
	}
	if err := gz.Close(); err != nil {
		return nil, err
	}
	return body.Bytes(), nil
}

func classifyTransportError(err error) error {
	text := strings.ToLower(err.Error())
	switch {
	case strings.Contains(text, "certificate has expired") || strings.Contains(text, "expired"):
		return &DeliveryError{Code: "certificate_expired", Err: fmt.Errorf("%w: %v", ErrCertificateExpired, err), permanent: false}
	case strings.Contains(text, "certificate") || strings.Contains(text, "tls") || strings.Contains(text, "x509"):
		return &DeliveryError{Code: "tls_authentication", Err: fmt.Errorf("%w: %v", ErrTLSAuthentication, err), permanent: false}
	default:
		return err
	}
}
