package filemanager

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
)

func TestExecutorRunsExplorerStyleFileOperationsInsideRoot(t *testing.T) {
	root := t.TempDir()
	source := filepath.Join(root, "source")
	dest := filepath.Join(root, "dest")
	must(t, os.MkdirAll(source, 0o755))
	must(t, os.MkdirAll(dest, 0o755))
	must(t, os.WriteFile(filepath.Join(source, "alpha.txt"), []byte("alpha"), 0o644))

	executor := newTestExecutor(t, root, true)

	list := execute(t, executor, OperationList, CommandRequest{Path: source}, nil)
	listResponse := decodeResponse[ListResponse](t, list.Response)
	if len(listResponse.Entries) != 1 || listResponse.Entries[0].Name != "alpha.txt" {
		t.Fatalf("unexpected list response: %#v", listResponse.Entries)
	}

	execute(t, executor, OperationCopy, CommandRequest{
		SourcePaths:    []string{filepath.Join(source, "alpha.txt")},
		DestinationDir: dest,
	}, nil)
	if data := readFile(t, filepath.Join(dest, "alpha.txt")); string(data) != "alpha" {
		t.Fatalf("copied content = %q", data)
	}

	execute(t, executor, OperationRename, CommandRequest{
		Path:    filepath.Join(dest, "alpha.txt"),
		NewName: "renamed.txt",
	}, nil)
	if _, err := os.Stat(filepath.Join(dest, "renamed.txt")); err != nil {
		t.Fatalf("renamed file missing: %v", err)
	}

	execute(t, executor, OperationMove, CommandRequest{
		SourcePaths:    []string{filepath.Join(dest, "renamed.txt")},
		DestinationDir: source,
	}, nil)
	if _, err := os.Stat(filepath.Join(source, "renamed.txt")); err != nil {
		t.Fatalf("moved file missing: %v", err)
	}

	execute(t, executor, OperationDelete, CommandRequest{
		Paths:     []string{filepath.Join(source, "renamed.txt")},
		Recursive: false,
	}, nil)
	if _, err := os.Stat(filepath.Join(source, "renamed.txt")); !os.IsNotExist(err) {
		t.Fatalf("expected moved file to be deleted, err=%v", err)
	}
}

func TestExecutorRejectsPathOutsideConfiguredRoot(t *testing.T) {
	root := t.TempDir()
	outside := t.TempDir()
	must(t, os.WriteFile(filepath.Join(outside, "secret.txt"), []byte("secret"), 0o644))

	executor := newTestExecutor(t, root, true)
	result := executor.Execute(context.Background(), command(OperationList, CommandRequest{Path: outside}), nil)

	if result.Status != "FAILED" {
		t.Fatalf("Status = %s, want FAILED", result.Status)
	}
	if result.Error == "" {
		t.Fatal("expected path rejection error")
	}
}

func TestExecutorRejectsCopyIntoSourceDescendant(t *testing.T) {
	root := t.TempDir()
	source := filepath.Join(root, "source")
	destination := filepath.Join(source, "destination")
	must(t, os.MkdirAll(destination, 0o755))
	must(t, os.WriteFile(filepath.Join(source, "keep.txt"), []byte("keep"), 0o644))
	executor := newTestExecutor(t, root, false)

	result := executor.Execute(context.Background(), command(OperationCopy, CommandRequest{
		SourcePaths:    []string{source},
		DestinationDir: destination,
	}), nil)
	if result.Status != "FAILED" || !strings.Contains(result.Error, "inside source directory") {
		t.Fatalf("descendant copy result = %#v", result)
	}
	if data := readFile(t, filepath.Join(source, "keep.txt")); string(data) != "keep" {
		t.Fatalf("source content changed: %q", data)
	}
	if _, err := os.Stat(filepath.Join(destination, filepath.Base(source))); !os.IsNotExist(err) {
		t.Fatalf("recursive copy target was created: %v", err)
	}
}

func TestExecutorRejectsMoveIntoSourceDescendant(t *testing.T) {
	root := t.TempDir()
	source := filepath.Join(root, "source")
	destination := filepath.Join(source, "destination")
	must(t, os.MkdirAll(destination, 0o755))
	must(t, os.WriteFile(filepath.Join(source, "keep.txt"), []byte("keep"), 0o644))
	executor := newTestExecutor(t, root, true)

	result := executor.Execute(context.Background(), command(OperationMove, CommandRequest{
		SourcePaths:    []string{source},
		DestinationDir: destination,
		Overwrite:      true,
	}), nil)
	if result.Status != "FAILED" || !strings.Contains(result.Error, "inside source directory") {
		t.Fatalf("descendant move result = %#v", result)
	}
	if data := readFile(t, filepath.Join(source, "keep.txt")); string(data) != "keep" {
		t.Fatalf("source content changed: %q", data)
	}
	if _, err := os.Stat(filepath.Join(destination, "source")); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("recursive move target was created: %v", err)
	}
}

func TestExecutorRejectsSymlinkInsideRecursiveCopy(t *testing.T) {
	root := t.TempDir()
	source := filepath.Join(root, "source")
	destination := filepath.Join(root, "destination")
	outside := filepath.Join(t.TempDir(), "secret.txt")
	must(t, os.MkdirAll(source, 0o755))
	must(t, os.MkdirAll(destination, 0o755))
	must(t, os.WriteFile(outside, []byte("secret"), 0o600))
	if err := os.Symlink(outside, filepath.Join(source, "secret-link")); err != nil {
		t.Skipf("symlink creation is unavailable: %v", err)
	}
	executor := newTestExecutor(t, root, true)

	result := executor.Execute(context.Background(), command(OperationCopy, CommandRequest{
		SourcePaths:    []string{source},
		DestinationDir: destination,
	}), nil)
	if result.Status != "FAILED" || !strings.Contains(result.Error, "symbolic links are not supported") {
		t.Fatalf("symlink copy result = %#v", result)
	}
	if _, err := os.Stat(filepath.Join(destination, "source", "secret-link")); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("symlink target content was copied: %v", err)
	}
}

func TestExecutorRejectsOverwriteCopyOntoSourceWithoutDeletingIt(t *testing.T) {
	root := t.TempDir()
	source := filepath.Join(root, "keep.txt")
	must(t, os.WriteFile(source, []byte("keep"), 0o644))
	executor := newTestExecutor(t, root, true)

	result := executor.Execute(context.Background(), command(OperationCopy, CommandRequest{
		SourcePaths:    []string{source},
		DestinationDir: root,
		Overwrite:      true,
	}), nil)
	if result.Status != "FAILED" || !strings.Contains(result.Error, "differ from source") {
		t.Fatalf("same-path copy result = %#v", result)
	}
	if data := readFile(t, source); string(data) != "keep" {
		t.Fatalf("source content changed: %q", data)
	}
}

func TestExecutorUploadsAndDownloadsThroughTransferTransport(t *testing.T) {
	root := t.TempDir()
	transport := &memoryTransport{downloads: map[string][]byte{"upload-1": []byte("payload")}}
	executor := newTestExecutor(t, root, true)

	upload := execute(t, executor, OperationUpload, CommandRequest{
		Path:       filepath.Join(root, "incoming.txt"),
		TransferID: "upload-1",
	}, transport)
	uploadResponse := decodeResponse[TransferResponse](t, upload.Response)
	if uploadResponse.SizeBytes != int64(len("payload")) {
		t.Fatalf("upload size = %d", uploadResponse.SizeBytes)
	}
	if data := readFile(t, filepath.Join(root, "incoming.txt")); string(data) != "payload" {
		t.Fatalf("uploaded content = %q", data)
	}

	download := execute(t, executor, OperationDownload, CommandRequest{
		Path:       filepath.Join(root, "incoming.txt"),
		TransferID: "download-1",
	}, transport)
	downloadResponse := decodeResponse[TransferResponse](t, download.Response)
	if downloadResponse.Name != "incoming.txt" {
		t.Fatalf("download name = %q", downloadResponse.Name)
	}
	if string(transport.uploads["download-1"]) != "payload" {
		t.Fatalf("downloaded content = %q", transport.uploads["download-1"])
	}
}

func TestExecutorUsesStreamingTransportAndAtomicallyReplacesTarget(t *testing.T) {
	root := t.TempDir()
	target := filepath.Join(root, "incoming.txt")
	must(t, os.WriteFile(target, []byte("old-content"), 0o644))
	transport := &streamingMemoryTransport{downloads: map[string][]byte{"upload-1": []byte("new-content")}}
	executor := newTestExecutor(t, root, true)

	upload := execute(t, executor, OperationUpload, CommandRequest{
		Path:       target,
		TransferID: "upload-1",
		Overwrite:  true,
	}, transport)
	if !transport.downloadStreamed {
		t.Fatal("expected streaming download path")
	}
	if response := decodeResponse[TransferResponse](t, upload.Response); response.SizeBytes != int64(len("new-content")) {
		t.Fatalf("upload size = %d", response.SizeBytes)
	}
	if data := readFile(t, target); string(data) != "new-content" {
		t.Fatalf("uploaded content = %q", data)
	}

	execute(t, executor, OperationDownload, CommandRequest{
		Path:       target,
		TransferID: "download-1",
	}, transport)
	if !transport.uploadStreamed {
		t.Fatal("expected streaming upload path")
	}
	if data := transport.uploads["download-1"]; string(data) != "new-content" {
		t.Fatalf("downloaded content = %q", data)
	}
}

func TestExecutorRejectsOversizeStreamingUploadWithoutReplacingTarget(t *testing.T) {
	root := t.TempDir()
	target := filepath.Join(root, "incoming.txt")
	must(t, os.WriteFile(target, []byte("keep-me"), 0o644))
	executor, err := NewExecutor([]Root{{Name: "test", Path: root}}, true, 8)
	must(t, err)
	transport := &streamingMemoryTransport{downloads: map[string][]byte{"oversize": []byte("123456789")}}

	result := executor.Execute(context.Background(), command(OperationUpload, CommandRequest{
		Path:       target,
		TransferID: "oversize",
		Overwrite:  true,
	}), transport)

	if result.Status != "FAILED" {
		t.Fatalf("Status = %s, want FAILED", result.Status)
	}
	if data := readFile(t, target); string(data) != "keep-me" {
		t.Fatalf("target was partially replaced: %q", data)
	}
	if matches, err := filepath.Glob(filepath.Join(root, ".incoming.txt-*.tmp")); err != nil || len(matches) != 0 {
		t.Fatalf("temporary files left behind: %v, err=%v", matches, err)
	}
}

func TestExecutorRejectsOversizeFileBeforeStreamingDownload(t *testing.T) {
	root := t.TempDir()
	source := filepath.Join(root, "oversize.bin")
	must(t, os.WriteFile(source, []byte("123456789"), 0o644))
	executor, err := NewExecutor([]Root{{Name: "test", Path: root}}, true, 8)
	must(t, err)
	transport := &streamingMemoryTransport{}

	result := executor.Execute(context.Background(), command(OperationDownload, CommandRequest{
		Path:       source,
		TransferID: "download-oversize",
	}), transport)

	if result.Status != "FAILED" {
		t.Fatalf("Status = %s, want FAILED", result.Status)
	}
	if transport.uploadStreamed {
		t.Fatal("oversize file should be rejected before starting transport")
	}
}

func TestClientStreamingTransfersEnforceLimitAndReuseDrainedConnection(t *testing.T) {
	var mu sync.Mutex
	var uploaded []byte
	remoteAddresses := map[string]struct{}{}
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		remoteAddresses[r.RemoteAddr] = struct{}{}
		mu.Unlock()
		switch r.URL.Path {
		case "/api/agent/file-manager/transfers/good/content":
			_, _ = io.WriteString(w, "payload")
		case "/api/agent/file-manager/transfers/oversize/content":
			_, _ = io.WriteString(w, "123456789")
		case "/api/agent/file-manager/transfers/send/content":
			body, _ := io.ReadAll(r.Body)
			mu.Lock()
			uploaded = body
			mu.Unlock()
			_, _ = io.WriteString(w, "response body that must be drained")
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	tlsConfig := server.Client().Transport.(*http.Transport).TLSClientConfig.Clone()
	client, err := New(Config{
		BaseURL:          server.URL,
		AgentID:          "agent-1",
		Roots:            []Root{{Name: "test", Path: t.TempDir()}},
		MaxTransferBytes: 8,
		TLSConfig:        tlsConfig,
	})
	must(t, err)

	var destination bytes.Buffer
	written, err := client.DownloadTo(context.Background(), "good", &destination, 8)
	must(t, err)
	if written != 7 || destination.String() != "payload" {
		t.Fatalf("download = %q (%d bytes)", destination.String(), written)
	}
	if _, err := client.DownloadTo(context.Background(), "oversize", io.Discard, 8); err == nil {
		t.Fatal("expected oversize download to fail")
	}
	must(t, client.UploadFrom(context.Background(), "send", "payload.bin", "application/octet-stream", strings.NewReader("payload"), 7))
	mu.Lock()
	defer mu.Unlock()
	if string(uploaded) != "payload" {
		t.Fatalf("uploaded body = %q", uploaded)
	}
	if len(remoteAddresses) != 1 {
		t.Fatalf("HTTP connection was not reused after draining responses: %v", remoteAddresses)
	}
}

func TestExecutorRejectsUnsafeRename(t *testing.T) {
	root := t.TempDir()
	must(t, os.WriteFile(filepath.Join(root, "alpha.txt"), []byte("alpha"), 0o644))
	executor := newTestExecutor(t, root, true)

	result := executor.Execute(context.Background(), command(OperationRename, CommandRequest{
		Path:    filepath.Join(root, "alpha.txt"),
		NewName: "../escape.txt",
	}), nil)

	if result.Status != "FAILED" {
		t.Fatalf("Status = %s, want FAILED", result.Status)
	}
}

func TestDeleteDisabledAlsoPreventsOverwriteMoveAndRootRemoval(t *testing.T) {
	root := t.TempDir()
	sourceDir := filepath.Join(root, "source")
	destinationDir := filepath.Join(root, "destination")
	must(t, os.MkdirAll(sourceDir, 0o755))
	must(t, os.MkdirAll(destinationDir, 0o755))
	source := filepath.Join(sourceDir, "item.txt")
	renameTarget := filepath.Join(sourceDir, "rename-target.txt")
	target := filepath.Join(destinationDir, "item.txt")
	must(t, os.WriteFile(source, []byte("source"), 0o644))
	must(t, os.WriteFile(renameTarget, []byte("rename-keep"), 0o644))
	must(t, os.WriteFile(target, []byte("keep"), 0o644))
	executor := newTestExecutor(t, root, false)

	commands := []struct {
		operation string
		request   CommandRequest
		transport Transport
	}{
		{OperationDelete, CommandRequest{Paths: []string{target}}, nil},
		{OperationCopy, CommandRequest{SourcePaths: []string{source}, DestinationDir: destinationDir, Overwrite: true}, nil},
		{OperationMove, CommandRequest{SourcePaths: []string{source}, DestinationDir: destinationDir, Overwrite: true}, nil},
		{OperationUpload, CommandRequest{Path: target, TransferID: "replace", Overwrite: true}, &memoryTransport{downloads: map[string][]byte{"replace": []byte("new")}}},
		{OperationRename, CommandRequest{Path: source, NewName: filepath.Base(renameTarget)}, nil},
	}
	for _, candidate := range commands {
		result := executor.Execute(context.Background(), command(candidate.operation, candidate.request), candidate.transport)
		if result.Status != "FAILED" {
			t.Fatalf("%s status = %s, want FAILED", candidate.operation, result.Status)
		}
	}
	if data := readFile(t, source); string(data) != "source" {
		t.Fatalf("source changed: %q", data)
	}
	if data := readFile(t, target); string(data) != "keep" {
		t.Fatalf("target changed: %q", data)
	}
	if data := readFile(t, renameTarget); string(data) != "rename-keep" {
		t.Fatalf("rename target changed: %q", data)
	}

	deleteEnabled := newTestExecutor(t, root, true)
	result := deleteEnabled.Execute(context.Background(), command(OperationDelete, CommandRequest{Paths: []string{root}, Recursive: true}), nil)
	if result.Status != "FAILED" {
		t.Fatalf("configured root delete status = %s, want FAILED", result.Status)
	}
	if _, err := os.Stat(root); err != nil {
		t.Fatalf("configured root was removed: %v", err)
	}
}

func newTestExecutor(t *testing.T, root string, allowDelete bool) *Executor {
	t.Helper()
	executor, err := NewExecutor([]Root{{Name: "test", Path: root}}, allowDelete, 1024*1024)
	if err != nil {
		t.Fatal(err)
	}
	return executor
}

func execute(t *testing.T, executor *Executor, operation string, request CommandRequest, transport Transport) CommandResult {
	t.Helper()
	result := executor.Execute(context.Background(), command(operation, request), transport)
	if result.Status != "SUCCEEDED" {
		t.Fatalf("%s failed: %s", operation, result.Error)
	}
	return result
}

func command(operation string, request CommandRequest) Command {
	payload, _ := json.Marshal(request)
	return Command{ID: "cmd-1", Operation: operation, Request: payload}
}

func decodeResponse[T any](t *testing.T, value any) T {
	t.Helper()
	payload, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	var out T
	if err := json.Unmarshal(payload, &out); err != nil {
		t.Fatal(err)
	}
	return out
}

func readFile(t *testing.T, path string) []byte {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return data
}

func must(t *testing.T, err error) {
	t.Helper()
	if err != nil {
		t.Fatal(err)
	}
}

type memoryTransport struct {
	downloads map[string][]byte
	uploads   map[string][]byte
}

type streamingMemoryTransport struct {
	downloads        map[string][]byte
	uploads          map[string][]byte
	downloadStreamed bool
	uploadStreamed   bool
}

func (t *streamingMemoryTransport) Download(_ context.Context, transferID string) ([]byte, error) {
	return t.downloads[transferID], nil
}

func (t *streamingMemoryTransport) Upload(_ context.Context, transferID string, _ string, _ string, body []byte) error {
	if t.uploads == nil {
		t.uploads = map[string][]byte{}
	}
	t.uploads[transferID] = append([]byte(nil), body...)
	return nil
}

func (t *streamingMemoryTransport) DownloadTo(_ context.Context, transferID string, destination io.Writer, maxBytes int64) (int64, error) {
	t.downloadStreamed = true
	return copyWithLimit(destination, bytes.NewReader(t.downloads[transferID]), maxBytes)
}

func (t *streamingMemoryTransport) UploadFrom(_ context.Context, transferID string, _ string, _ string, body io.Reader, _ int64) error {
	t.uploadStreamed = true
	payload, err := io.ReadAll(body)
	if err != nil {
		return err
	}
	if t.uploads == nil {
		t.uploads = map[string][]byte{}
	}
	t.uploads[transferID] = payload
	return nil
}

func (t *memoryTransport) Download(_ context.Context, transferID string) ([]byte, error) {
	return t.downloads[transferID], nil
}

func (t *memoryTransport) Upload(_ context.Context, transferID string, _ string, _ string, body []byte) error {
	if t.uploads == nil {
		t.uploads = map[string][]byte{}
	}
	t.uploads[transferID] = body
	return nil
}
