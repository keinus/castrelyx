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
  "time"

  "castrelyx/agent/internal/envelope"
)

var (
  ErrPlaintextEndpoint  = errors.New("ingest endpoint must use https")
  ErrTLSAuthentication  = errors.New("tls authentication failed")
  ErrCertificateExpired = errors.New("client certificate is expired")
)

type Client struct {
  endpoint   string
  httpClient *http.Client
}

type TCPMTLSClient struct {
  addr      string
  tlsConfig *tls.Config
  timeout   time.Duration
}

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
  if addr == "" {
    return nil, errors.New("tcp ingest address is required")
  }
  if _, _, err := net.SplitHostPort(addr); err != nil {
    return nil, fmt.Errorf("invalid tcp ingest address: %w", err)
  }
  if tlsConfig == nil {
    return nil, errors.New("tls config is required")
  }
  return &TCPMTLSClient{
    addr:      addr,
    tlsConfig: tlsConfig,
    timeout:   30 * time.Second,
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

  if resp.StatusCode < 200 || resp.StatusCode > 299 {
    return fmt.Errorf("ingest returned status %d", resp.StatusCode)
  }
  return nil
}

func (c *TCPMTLSClient) Send(ctx context.Context, batch envelope.Batch) error {
  batch.SentAt = time.Now().UTC()
  payload, err := encodeGzipBatch(batch)
  if err != nil {
    return err
  }
  if uint64(len(payload)) > uint64(^uint32(0)) {
    return errors.New("encoded batch is too large for tcp frame")
  }

  dialer := &net.Dialer{Timeout: c.timeout}
  rawConn, err := dialer.DialContext(ctx, "tcp", c.addr)
  if err != nil {
    return err
  }
  defer rawConn.Close()

  tlsConn := tls.Client(rawConn, c.tlsConfig)
  defer tlsConn.Close()

  if deadline, ok := ctx.Deadline(); ok {
    _ = tlsConn.SetDeadline(deadline)
  } else {
    _ = tlsConn.SetDeadline(time.Now().Add(c.timeout))
  }

  if err := tlsConn.HandshakeContext(ctx); err != nil {
    return classifyTransportError(err)
  }

  var lengthPrefix [4]byte
  binary.BigEndian.PutUint32(lengthPrefix[:], uint32(len(payload)))
  if _, err := tlsConn.Write(lengthPrefix[:]); err != nil {
    return err
  }
  if _, err := tlsConn.Write(payload); err != nil {
    return err
  }

  line, err := bufio.NewReader(tlsConn).ReadString('\n')
  if err != nil {
    if errors.Is(err, io.EOF) {
      return errors.New("tcp ingest closed before ack")
    }
    return err
  }

  var ack ackResponse
  if err := json.Unmarshal([]byte(strings.TrimSpace(line)), &ack); err != nil {
    return fmt.Errorf("invalid tcp ingest ack: %w", err)
  }
  switch ack.Status {
  case "accepted":
    return nil
  case "error":
    if ack.Code == "" {
      ack.Code = "unknown"
    }
    if ack.Message == "" {
      ack.Message = "server returned NACK"
    }
    return fmt.Errorf("tcp ingest nack %s: %s", ack.Code, ack.Message)
  default:
    return fmt.Errorf("unexpected tcp ingest ack status %q", ack.Status)
  }
}

func encodeGzipBatch(batch envelope.Batch) ([]byte, error) {
  var body bytes.Buffer
  gz := gzip.NewWriter(&body)
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
    return fmt.Errorf("%w: %v", ErrCertificateExpired, err)
  case strings.Contains(text, "certificate") || strings.Contains(text, "tls") || strings.Contains(text, "x509"):
    return fmt.Errorf("%w: %v", ErrTLSAuthentication, err)
  default:
    return err
  }
}
