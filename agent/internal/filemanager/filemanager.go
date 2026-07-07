package filemanager

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"time"
)

const (
	OperationRoots    = "ROOTS"
	OperationList     = "LIST"
	OperationMkdir    = "MKDIR"
	OperationRename   = "RENAME"
	OperationDelete   = "DELETE"
	OperationCopy     = "COPY"
	OperationMove     = "MOVE"
	OperationUpload   = "UPLOAD"
	OperationDownload = "DOWNLOAD"
)

type Root struct {
	Name string `json:"name"`
	Path string `json:"path"`
}

type Config struct {
	BaseURL          string
	AgentID          string
	Roots            []Root
	AllowDelete      bool
	MaxTransferBytes int64
	PollInterval     time.Duration
	TLSConfig        *tls.Config
}

type Command struct {
	ID        string          `json:"id"`
	Operation string          `json:"operation"`
	Request   json.RawMessage `json:"request"`
	CreatedAt string          `json:"created_at,omitempty"`
}

type CheckResponse struct {
	Commands []Command `json:"commands"`
}

type CommandRequest struct {
	Path           string   `json:"path,omitempty"`
	Paths          []string `json:"paths,omitempty"`
	SourcePaths    []string `json:"source_paths,omitempty"`
	DestinationDir string   `json:"destination_dir,omitempty"`
	NewName        string   `json:"new_name,omitempty"`
	TransferID     string   `json:"transfer_id,omitempty"`
	Overwrite      bool     `json:"overwrite,omitempty"`
	Recursive      bool     `json:"recursive,omitempty"`
}

type CommandResult struct {
	Status   string `json:"status"`
	Response any    `json:"response,omitempty"`
	Error    string `json:"error,omitempty"`
}

type FileEntry struct {
	Name       string `json:"name"`
	Path       string `json:"path"`
	Type       string `json:"type"`
	Directory  bool   `json:"directory"`
	SizeBytes  int64  `json:"size_bytes"`
	ModifiedAt string `json:"modified_at,omitempty"`
	ReadOnly   bool   `json:"read_only"`
	Hidden     bool   `json:"hidden"`
	Extension  string `json:"extension,omitempty"`
}

type ListResponse struct {
	Path    string      `json:"path"`
	Roots   []Root      `json:"roots"`
	Entries []FileEntry `json:"entries"`
}

type MutationResponse struct {
	Operation string   `json:"operation"`
	Paths     []string `json:"paths,omitempty"`
	Path      string   `json:"path,omitempty"`
	Message   string   `json:"message,omitempty"`
}

type TransferResponse struct {
	TransferID string `json:"transfer_id"`
	Path       string `json:"path"`
	Name       string `json:"name,omitempty"`
	SizeBytes  int64  `json:"size_bytes"`
}

type Transport interface {
	Download(ctx context.Context, transferID string) ([]byte, error)
	Upload(ctx context.Context, transferID string, name string, contentType string, body []byte) error
}

type Executor struct {
	roots            []Root
	allowDelete      bool
	maxTransferBytes int64
}

type Client struct {
	config     Config
	executor   *Executor
	httpClient *http.Client
}

func NewExecutor(roots []Root, allowDelete bool, maxTransferBytes int64) (*Executor, error) {
	normalized := normalizeRoots(roots)
	if len(normalized) == 0 {
		normalized = DefaultRoots()
	}
	for index := range normalized {
		path, err := filepath.Abs(normalized[index].Path)
		if err != nil {
			return nil, fmt.Errorf("resolve root %q: %w", normalized[index].Path, err)
		}
		normalized[index].Path = filepath.Clean(path)
		if normalized[index].Name == "" {
			normalized[index].Name = normalized[index].Path
		}
	}
	if maxTransferBytes <= 0 {
		maxTransferBytes = 256 * 1024 * 1024
	}
	return &Executor{roots: normalized, allowDelete: allowDelete, maxTransferBytes: maxTransferBytes}, nil
}

func New(config Config) (*Client, error) {
	if config.BaseURL == "" {
		return nil, errors.New("base url is required")
	}
	if config.TLSConfig == nil {
		return nil, errors.New("tls config is required")
	}
	if config.PollInterval <= 0 {
		config.PollInterval = 5 * time.Second
	}
	executor, err := NewExecutor(config.Roots, config.AllowDelete, config.MaxTransferBytes)
	if err != nil {
		return nil, err
	}
	return &Client{
		config:   config,
		executor: executor,
		httpClient: &http.Client{
			Timeout: 90 * time.Second,
			Transport: &http.Transport{
				TLSClientConfig: config.TLSConfig,
			},
		},
	}, nil
}

func DefaultRoots() []Root {
	if runtime.GOOS == "windows" {
		roots := make([]Root, 0, 3)
		for drive := 'C'; drive <= 'Z'; drive++ {
			path := string(drive) + `:\`
			if _, err := os.Stat(path); err == nil {
				roots = append(roots, Root{Name: string(drive) + ":", Path: path})
			}
		}
		if len(roots) > 0 {
			return roots
		}
		return []Root{{Name: "ProgramData", Path: filepath.Join(os.Getenv("ProgramData"), "Castrelyx")}}
	}
	return []Root{{Name: "Filesystem", Path: "/"}}
}

func (c *Client) Run(ctx context.Context) {
	ticker := time.NewTicker(c.config.PollInterval)
	defer ticker.Stop()
	for {
		c.checkAndExecute(ctx)
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

func (c *Client) checkAndExecute(ctx context.Context) {
	var response CheckResponse
	if err := c.doJSON(ctx, http.MethodPost, "/api/agent/file-manager/check", map[string]any{
		"agent_id": c.config.AgentID,
	}, &response); err != nil {
		return
	}
	for _, command := range response.Commands {
		result := c.executor.Execute(ctx, command, c)
		_ = c.doJSON(ctx, http.MethodPost, "/api/agent/file-manager/commands/"+url.PathEscape(command.ID)+"/result", result, nil)
	}
}

func (c *Client) Download(ctx context.Context, transferID string) ([]byte, error) {
	endpoint, err := c.absoluteURL("/api/agent/file-manager/transfers/" + url.PathEscape(transferID) + "/content")
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return nil, err
	}
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return nil, fmt.Errorf("download transfer returned status %d", resp.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, c.executor.maxTransferBytes+1))
	if err != nil {
		return nil, err
	}
	if int64(len(body)) > c.executor.maxTransferBytes {
		return nil, errors.New("download transfer exceeds max_transfer_bytes")
	}
	return body, nil
}

func (c *Client) Upload(ctx context.Context, transferID string, name string, contentType string, body []byte) error {
	endpoint, err := c.absoluteURL("/api/agent/file-manager/transfers/" + url.PathEscape(transferID) + "/content")
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(body))
	if err != nil {
		return err
	}
	if contentType == "" {
		contentType = "application/octet-stream"
	}
	req.Header.Set("Content-Type", contentType)
	if name != "" {
		req.Header.Set("X-Castrelyx-Filename", name)
	}
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return fmt.Errorf("upload transfer returned status %d", resp.StatusCode)
	}
	return nil
}

func (c *Client) doJSON(ctx context.Context, method string, path string, body any, out any) error {
	endpoint, err := c.absoluteURL(path)
	if err != nil {
		return err
	}
	payload, err := json.Marshal(body)
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, method, endpoint, bytes.NewReader(payload))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		payload, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		return fmt.Errorf("%s returned status %d: %s", path, resp.StatusCode, strings.TrimSpace(string(payload)))
	}
	if out == nil || resp.StatusCode == http.StatusNoContent {
		return nil
	}
	return json.NewDecoder(resp.Body).Decode(out)
}

func (c *Client) absoluteURL(path string) (string, error) {
	if strings.HasPrefix(path, "https://") {
		return path, nil
	}
	if strings.HasPrefix(path, "http://") {
		return "", errors.New("file-manager endpoint must use https")
	}
	if !strings.HasPrefix(path, "/") {
		path = "/" + path
	}
	parsed, err := url.Parse(c.config.BaseURL)
	if err != nil {
		return "", err
	}
	return strings.TrimRight(parsed.String(), "/") + path, nil
}

func (e *Executor) Execute(ctx context.Context, command Command, transport Transport) CommandResult {
	response, err := e.execute(ctx, command, transport)
	if err != nil {
		return CommandResult{Status: "FAILED", Error: err.Error()}
	}
	return CommandResult{Status: "SUCCEEDED", Response: response}
}

func (e *Executor) execute(ctx context.Context, command Command, transport Transport) (any, error) {
	var request CommandRequest
	if len(command.Request) > 0 {
		if err := json.Unmarshal(command.Request, &request); err != nil {
			return nil, fmt.Errorf("invalid command request: %w", err)
		}
	}
	switch strings.ToUpper(command.Operation) {
	case OperationRoots:
		return ListResponse{Path: "", Roots: e.roots, Entries: e.rootEntries()}, nil
	case OperationList:
		return e.list(request.Path)
	case OperationMkdir:
		return e.mkdir(request.Path)
	case OperationRename:
		return e.rename(request.Path, request.NewName)
	case OperationDelete:
		return e.delete(request.Paths, request.Recursive)
	case OperationCopy:
		return e.copy(request.SourcePaths, request.DestinationDir, request.Overwrite)
	case OperationMove:
		return e.move(request.SourcePaths, request.DestinationDir, request.Overwrite)
	case OperationUpload:
		return e.upload(ctx, request, transport)
	case OperationDownload:
		return e.download(ctx, request, transport)
	default:
		return nil, fmt.Errorf("unsupported operation %q", command.Operation)
	}
}

func (e *Executor) rootEntries() []FileEntry {
	entries := make([]FileEntry, 0, len(e.roots))
	for _, root := range e.roots {
		entries = append(entries, FileEntry{
			Name:      root.Name,
			Path:      root.Path,
			Type:      "root",
			Directory: true,
		})
	}
	return entries
}

func (e *Executor) list(path string) (ListResponse, error) {
	if strings.TrimSpace(path) == "" {
		return ListResponse{Path: "", Roots: e.roots, Entries: e.rootEntries()}, nil
	}
	resolved, err := e.resolveExisting(path)
	if err != nil {
		return ListResponse{}, err
	}
	info, err := os.Stat(resolved)
	if err != nil {
		return ListResponse{}, err
	}
	if !info.IsDir() {
		return ListResponse{}, fmt.Errorf("%s is not a directory", resolved)
	}
	children, err := os.ReadDir(resolved)
	if err != nil {
		return ListResponse{}, err
	}
	entries := make([]FileEntry, 0, len(children))
	for _, child := range children {
		entry, err := e.entry(filepath.Join(resolved, child.Name()), child)
		if err == nil {
			entries = append(entries, entry)
		}
	}
	sort.Slice(entries, func(i, j int) bool {
		if entries[i].Directory != entries[j].Directory {
			return entries[i].Directory
		}
		return strings.ToLower(entries[i].Name) < strings.ToLower(entries[j].Name)
	})
	return ListResponse{Path: resolved, Roots: e.roots, Entries: entries}, nil
}

func (e *Executor) mkdir(path string) (MutationResponse, error) {
	target, err := e.resolveNew(path)
	if err != nil {
		return MutationResponse{}, err
	}
	if err := os.MkdirAll(target, 0o755); err != nil {
		return MutationResponse{}, err
	}
	return MutationResponse{Operation: OperationMkdir, Path: target, Message: "directory created"}, nil
}

func (e *Executor) rename(path string, newName string) (MutationResponse, error) {
	if !safeName(newName) {
		return MutationResponse{}, errors.New("new name must not contain path separators")
	}
	source, err := e.resolveExisting(path)
	if err != nil {
		return MutationResponse{}, err
	}
	target := filepath.Join(filepath.Dir(source), newName)
	target, err = e.resolveNew(target)
	if err != nil {
		return MutationResponse{}, err
	}
	if err := os.Rename(source, target); err != nil {
		return MutationResponse{}, err
	}
	return MutationResponse{Operation: OperationRename, Path: target, Paths: []string{source, target}}, nil
}

func (e *Executor) delete(paths []string, recursive bool) (MutationResponse, error) {
	if !e.allowDelete {
		return MutationResponse{}, errors.New("delete is disabled by agent configuration")
	}
	if len(paths) == 0 {
		return MutationResponse{}, errors.New("at least one path is required")
	}
	deleted := make([]string, 0, len(paths))
	for _, path := range paths {
		resolved, err := e.resolveExisting(path)
		if err != nil {
			return MutationResponse{}, err
		}
		info, err := os.Stat(resolved)
		if err != nil {
			return MutationResponse{}, err
		}
		if info.IsDir() && recursive {
			if err := os.RemoveAll(resolved); err != nil {
				return MutationResponse{}, err
			}
		} else if err := os.Remove(resolved); err != nil {
			return MutationResponse{}, err
		}
		deleted = append(deleted, resolved)
	}
	return MutationResponse{Operation: OperationDelete, Paths: deleted}, nil
}

func (e *Executor) copy(paths []string, destinationDir string, overwrite bool) (MutationResponse, error) {
	if len(paths) == 0 {
		return MutationResponse{}, errors.New("at least one source path is required")
	}
	destination, err := e.resolveExisting(destinationDir)
	if err != nil {
		return MutationResponse{}, err
	}
	info, err := os.Stat(destination)
	if err != nil {
		return MutationResponse{}, err
	}
	if !info.IsDir() {
		return MutationResponse{}, errors.New("destination must be a directory")
	}
	written := make([]string, 0, len(paths))
	for _, path := range paths {
		source, err := e.resolveExisting(path)
		if err != nil {
			return MutationResponse{}, err
		}
		target, err := e.resolveNew(filepath.Join(destination, filepath.Base(source)))
		if err != nil {
			return MutationResponse{}, err
		}
		if err := copyPath(source, target, overwrite); err != nil {
			return MutationResponse{}, err
		}
		written = append(written, target)
	}
	return MutationResponse{Operation: OperationCopy, Paths: written}, nil
}

func (e *Executor) move(paths []string, destinationDir string, overwrite bool) (MutationResponse, error) {
	if len(paths) == 0 {
		return MutationResponse{}, errors.New("at least one source path is required")
	}
	destination, err := e.resolveExisting(destinationDir)
	if err != nil {
		return MutationResponse{}, err
	}
	moved := make([]string, 0, len(paths))
	for _, path := range paths {
		source, err := e.resolveExisting(path)
		if err != nil {
			return MutationResponse{}, err
		}
		target, err := e.resolveNew(filepath.Join(destination, filepath.Base(source)))
		if err != nil {
			return MutationResponse{}, err
		}
		if _, err := os.Stat(target); err == nil {
			if !overwrite {
				return MutationResponse{}, fmt.Errorf("target already exists: %s", target)
			}
			if err := os.RemoveAll(target); err != nil {
				return MutationResponse{}, err
			}
		}
		if err := os.Rename(source, target); err != nil {
			if err := copyPath(source, target, overwrite); err != nil {
				return MutationResponse{}, err
			}
			if err := os.RemoveAll(source); err != nil {
				return MutationResponse{}, err
			}
		}
		moved = append(moved, target)
	}
	return MutationResponse{Operation: OperationMove, Paths: moved}, nil
}

func (e *Executor) upload(ctx context.Context, request CommandRequest, transport Transport) (TransferResponse, error) {
	if transport == nil {
		return TransferResponse{}, errors.New("transfer transport is required")
	}
	if request.TransferID == "" {
		return TransferResponse{}, errors.New("transfer_id is required")
	}
	target, err := e.resolveNew(request.Path)
	if err != nil {
		return TransferResponse{}, err
	}
	if _, err := os.Stat(target); err == nil && !request.Overwrite {
		return TransferResponse{}, fmt.Errorf("target already exists: %s", target)
	}
	body, err := transport.Download(ctx, request.TransferID)
	if err != nil {
		return TransferResponse{}, err
	}
	if int64(len(body)) > e.maxTransferBytes {
		return TransferResponse{}, errors.New("transfer exceeds max_transfer_bytes")
	}
	if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
		return TransferResponse{}, err
	}
	if err := os.WriteFile(target, body, 0o644); err != nil {
		return TransferResponse{}, err
	}
	return TransferResponse{TransferID: request.TransferID, Path: target, Name: filepath.Base(target), SizeBytes: int64(len(body))}, nil
}

func (e *Executor) download(ctx context.Context, request CommandRequest, transport Transport) (TransferResponse, error) {
	if transport == nil {
		return TransferResponse{}, errors.New("transfer transport is required")
	}
	if request.TransferID == "" {
		return TransferResponse{}, errors.New("transfer_id is required")
	}
	source, err := e.resolveExisting(request.Path)
	if err != nil {
		return TransferResponse{}, err
	}
	info, err := os.Stat(source)
	if err != nil {
		return TransferResponse{}, err
	}
	if info.IsDir() {
		return TransferResponse{}, errors.New("directory download is not supported yet")
	}
	if info.Size() > e.maxTransferBytes {
		return TransferResponse{}, errors.New("file exceeds max_transfer_bytes")
	}
	body, err := os.ReadFile(source)
	if err != nil {
		return TransferResponse{}, err
	}
	contentType := mime.TypeByExtension(filepath.Ext(source))
	if contentType == "" {
		contentType = "application/octet-stream"
	}
	if err := transport.Upload(ctx, request.TransferID, filepath.Base(source), contentType, body); err != nil {
		return TransferResponse{}, err
	}
	return TransferResponse{TransferID: request.TransferID, Path: source, Name: filepath.Base(source), SizeBytes: info.Size()}, nil
}

func (e *Executor) entry(path string, entry os.DirEntry) (FileEntry, error) {
	info, err := entry.Info()
	if err != nil {
		return FileEntry{}, err
	}
	entryType := "file"
	if info.IsDir() {
		entryType = "directory"
	} else if info.Mode()&os.ModeSymlink != 0 {
		entryType = "symlink"
	}
	return FileEntry{
		Name:       info.Name(),
		Path:       path,
		Type:       entryType,
		Directory:  info.IsDir(),
		SizeBytes:  info.Size(),
		ModifiedAt: info.ModTime().UTC().Format(time.RFC3339),
		ReadOnly:   info.Mode().Perm()&0o200 == 0,
		Hidden:     strings.HasPrefix(info.Name(), "."),
		Extension:  strings.TrimPrefix(filepath.Ext(info.Name()), "."),
	}, nil
}

func (e *Executor) resolveExisting(path string) (string, error) {
	abs, err := filepath.Abs(path)
	if err != nil {
		return "", err
	}
	resolved, err := filepath.EvalSymlinks(abs)
	if err != nil {
		return "", err
	}
	resolved = filepath.Clean(resolved)
	if !e.allowed(resolved) {
		return "", fmt.Errorf("path is outside configured file manager roots: %s", path)
	}
	return resolved, nil
}

func (e *Executor) resolveNew(path string) (string, error) {
	if strings.TrimSpace(path) == "" {
		return "", errors.New("path is required")
	}
	abs, err := filepath.Abs(path)
	if err != nil {
		return "", err
	}
	parent := filepath.Dir(abs)
	resolvedParent, err := filepath.EvalSymlinks(parent)
	if err != nil {
		return "", err
	}
	target := filepath.Clean(filepath.Join(resolvedParent, filepath.Base(abs)))
	if !e.allowed(target) {
		return "", fmt.Errorf("path is outside configured file manager roots: %s", path)
	}
	return target, nil
}

func (e *Executor) allowed(path string) bool {
	for _, root := range e.roots {
		rootPath := root.Path
		if resolvedRoot, err := filepath.EvalSymlinks(root.Path); err == nil {
			rootPath = filepath.Clean(resolvedRoot)
		}
		if sameOrChild(path, rootPath) {
			return true
		}
	}
	return false
}

func sameOrChild(path string, root string) bool {
	cleanPath := filepath.Clean(path)
	cleanRoot := filepath.Clean(root)
	if runtime.GOOS == "windows" {
		cleanPath = strings.ToLower(cleanPath)
		cleanRoot = strings.ToLower(cleanRoot)
	}
	if cleanPath == cleanRoot {
		return true
	}
	rel, err := filepath.Rel(cleanRoot, cleanPath)
	return err == nil && rel != ".." && !strings.HasPrefix(rel, ".."+string(os.PathSeparator)) && !filepath.IsAbs(rel)
}

func safeName(name string) bool {
	name = strings.TrimSpace(name)
	return name != "" && name != "." && name != ".." && !strings.ContainsAny(name, `/\`)
}

func normalizeRoots(roots []Root) []Root {
	out := make([]Root, 0, len(roots))
	seen := map[string]bool{}
	for _, root := range roots {
		if strings.TrimSpace(root.Path) == "" {
			continue
		}
		path := filepath.Clean(root.Path)
		key := path
		if runtime.GOOS == "windows" {
			key = strings.ToLower(key)
		}
		if seen[key] {
			continue
		}
		seen[key] = true
		out = append(out, Root{Name: strings.TrimSpace(root.Name), Path: path})
	}
	return out
}

func copyPath(source string, target string, overwrite bool) error {
	info, err := os.Stat(source)
	if err != nil {
		return err
	}
	if _, err := os.Stat(target); err == nil {
		if !overwrite {
			return fmt.Errorf("target already exists: %s", target)
		}
		if err := os.RemoveAll(target); err != nil {
			return err
		}
	}
	if info.IsDir() {
		return copyDir(source, target, overwrite)
	}
	return copyFile(source, target, info.Mode().Perm())
}

func copyDir(source string, target string, overwrite bool) error {
	return filepath.WalkDir(source, func(path string, entry os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		rel, err := filepath.Rel(source, path)
		if err != nil {
			return err
		}
		dest := filepath.Join(target, rel)
		info, err := entry.Info()
		if err != nil {
			return err
		}
		if entry.IsDir() {
			return os.MkdirAll(dest, info.Mode().Perm())
		}
		if _, err := os.Stat(dest); err == nil && !overwrite {
			return fmt.Errorf("target already exists: %s", dest)
		}
		return copyFile(path, dest, info.Mode().Perm())
	})
}

func copyFile(source string, target string, mode os.FileMode) error {
	input, err := os.Open(source)
	if err != nil {
		return err
	}
	defer input.Close()
	if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
		return err
	}
	output, err := os.OpenFile(target, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, mode)
	if err != nil {
		return err
	}
	if _, err := io.Copy(output, input); err != nil {
		_ = output.Close()
		return err
	}
	return output.Close()
}
