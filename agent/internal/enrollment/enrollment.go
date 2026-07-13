package enrollment

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"time"
)

type Request struct {
	AgentID  string `json:"agent_id"`
	Hostname string `json:"hostname"`
	Version  string `json:"version"`
	CSRPem   string `json:"csr_pem"`
}

type Response struct {
	AgentID       string `json:"agent_id"`
	CACertPEM     string `json:"ca_cert_pem"`
	ClientCertPEM string `json:"client_cert_pem"`
	ExpiresAt     string `json:"expires_at"`
	IngestURL     string `json:"ingest_url"`
}

type Error struct {
	StatusCode int
	Message    string
}

func (e *Error) Error() string {
	return fmt.Sprintf("enrollment failed with status %d: %s", e.StatusCode, e.Message)
}

type Client struct {
	managerURL string
	token      string
	serverName string
	httpClient *http.Client
}

func New(managerURL, token, serverName string, transport http.RoundTripper) *Client {
	if transport == nil {
		transport = &http.Transport{
			TLSClientConfig: &tls.Config{
				MinVersion: tls.VersionTLS12,
				ServerName: serverName,
			},
		}
	}
	return &Client{
		managerURL: strings.TrimRight(managerURL, "/"),
		token:      token,
		serverName: serverName,
		httpClient: &http.Client{
			Timeout:   30 * time.Second,
			Transport: transport,
		},
	}
}

func (c *Client) Enroll(ctx context.Context, request Request) (Response, error) {
	return c.post(ctx, "/api/agent/enroll", request, true)
}

func (c *Client) Renew(ctx context.Context, request Request) (Response, error) {
	return c.post(ctx, "/api/agent/renew", request, false)
}

func (c *Client) CloseIdleConnections() {
	c.httpClient.CloseIdleConnections()
}

func (c *Client) post(ctx context.Context, path string, request Request, useToken bool) (Response, error) {
	if request.AgentID == "" {
		return Response{}, fmt.Errorf("agent_id is required")
	}
	if request.CSRPem == "" {
		return Response{}, fmt.Errorf("csr_pem is required")
	}
	endpoint := c.managerURL + path
	parsed, err := url.Parse(endpoint)
	if err != nil {
		return Response{}, err
	}
	if parsed.Scheme != "https" {
		return Response{}, fmt.Errorf("enrollment endpoint must use https")
	}

	body, err := json.Marshal(request)
	if err != nil {
		return Response{}, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(body))
	if err != nil {
		return Response{}, err
	}
	req.Header.Set("Content-Type", "application/json")
	if useToken && c.token != "" {
		req.Header.Set("Authorization", "Bearer "+c.token)
	}

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return Response{}, err
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return Response{}, &Error{StatusCode: resp.StatusCode, Message: resp.Status}
	}

	var enrollment Response
	if err := json.NewDecoder(resp.Body).Decode(&enrollment); err != nil {
		return Response{}, err
	}
	if enrollment.CACertPEM == "" || enrollment.ClientCertPEM == "" {
		return Response{}, fmt.Errorf("enrollment response missing certificate material")
	}
	if enrollment.IngestURL == "" {
		enrollment.IngestURL = c.managerURL + "/api/agent/ingest"
	}
	ingestURL, err := url.Parse(enrollment.IngestURL)
	if err != nil {
		return Response{}, err
	}
	if ingestURL.Scheme != "https" {
		return Response{}, fmt.Errorf("ingest_url must use https")
	}
	return enrollment, nil
}
