package filemanager

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
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
