package sender

import (
	"context"
	"crypto/tls"
	"errors"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"

	"castrelyx/agent/internal/envelope"
)

func TestSenderReturnsErrorForNonSuccessStatus(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer server.Close()

	tlsConfig := server.Client().Transport.(*http.Transport).TLSClientConfig
	client, err := New(server.URL, tlsConfig.Clone())
	if err != nil {
		t.Fatal(err)
	}
	batch := envelope.NewBatch("agent", "agent-01", "default")

	if err := client.Send(context.Background(), batch); err == nil {
		t.Fatal("Send returned nil error for 500 response")
	}
}

func TestNewRejectsMissingTLSConfig(t *testing.T) {
	if _, err := New("https://manager.local/api/ingest", nil); err == nil {
		t.Fatal("New returned nil error for missing tls config")
	}
}

func TestClassifyTransportErrorDetectsTLSFailure(t *testing.T) {
	err := classifyTransportError(tls.RecordHeaderError{})
	if err == nil {
		t.Fatal("classifyTransportError returned nil")
	}
	var deliveryError *DeliveryError
	if !errors.As(err, &deliveryError) || deliveryError.Permanent() || deliveryError.Code != "tls_authentication" {
		t.Fatalf("TLS error classification = %#v", err)
	}
	if !errors.Is(err, ErrTLSAuthentication) {
		t.Fatalf("TLS error does not wrap ErrTLSAuthentication: %v", err)
	}
}

func TestClassifyTransportErrorKeepsExpiredCertificatesRetryable(t *testing.T) {
	err := classifyTransportError(errors.New("x509: certificate has expired or is not yet valid"))
	var deliveryError *DeliveryError
	if !errors.As(err, &deliveryError) {
		t.Fatalf("error type = %T, want *DeliveryError: %v", err, err)
	}
	if deliveryError.Permanent() || deliveryError.Code != "certificate_expired" {
		t.Fatalf("expired certificate classification = code=%q permanent=%v", deliveryError.Code, deliveryError.Permanent())
	}
	if !errors.Is(err, ErrCertificateExpired) {
		t.Fatalf("error does not wrap ErrCertificateExpired: %v", err)
	}
}

func TestHTTPSenderClassifiesPermanentAndTransientStatuses(t *testing.T) {
	tests := []struct {
		status    int
		permanent bool
	}{
		{status: http.StatusBadRequest, permanent: true},
		{status: http.StatusUnauthorized, permanent: true},
		{status: http.StatusRequestTimeout, permanent: false},
		{status: http.StatusTooManyRequests, permanent: false},
		{status: http.StatusInternalServerError, permanent: false},
		{status: http.StatusServiceUnavailable, permanent: false},
	}
	for _, test := range tests {
		t.Run(http.StatusText(test.status), func(t *testing.T) {
			server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				w.WriteHeader(test.status)
				_, _ = w.Write([]byte("classified response body"))
			}))
			defer server.Close()

			client, err := New(server.URL, server.Client().Transport.(*http.Transport).TLSClientConfig.Clone())
			if err != nil {
				t.Fatal(err)
			}
			err = client.Send(context.Background(), envelope.NewBatch("agent", "agent-01", "default"))
			var deliveryError *DeliveryError
			if !errors.As(err, &deliveryError) {
				t.Fatalf("Send error type = %T, want *DeliveryError: %v", err, err)
			}
			if deliveryError.Permanent() != test.permanent || deliveryError.Code != "http_"+strconv.Itoa(test.status) {
				t.Fatalf("status %d classification = code=%q permanent=%v", test.status, deliveryError.Code, deliveryError.Permanent())
			}
		})
	}
}
