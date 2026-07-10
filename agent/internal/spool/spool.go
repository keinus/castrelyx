package spool

import (
	"bufio"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"sync"
	"time"
)

const (
	legacyQueueFileName       = "queue.ndjson"
	recordsDirName            = "queue"
	deadLetterDirName         = "deadletter"
	maxQuarantineCaptureBytes = 64 * 1024
)

var ErrQueueFull = errors.New("spool queue limit exceeded")

type Options struct {
	MaxRecordBytes int64
	MaxBytes       int64
	MaxRecords     int
	MaxAge         time.Duration
}

type Queue struct {
	dir          string
	recordsDir   string
	deadDir      string
	legacyPath   string
	options      Options
	mu           sync.Mutex
	active       map[string]recordMeta
	activeOrder  []string
	activeHead   int
	dead         map[string]recordMeta
	deadOrder    []string
	activeBytes  int64
	deadBytes    int64
	oldestAt     time.Time
	oldestDeadAt time.Time
}

type recordMeta struct {
	ID         string
	Size       int64
	CreatedAt  time.Time
	ModifiedAt time.Time
}

type Record struct {
	ID        string
	Payload   []byte
	CreatedAt time.Time
}

type Stats struct {
	Records           int
	Bytes             int64
	OldestAt          time.Time
	DeadLetterRecords int
	DeadLetterBytes   int64
	TotalRecords      int
	TotalBytes        int64
}

type diskRecord struct {
	ID        string          `json:"id"`
	Payload   json.RawMessage `json:"payload"`
	CreatedAt time.Time       `json:"created_at"`
	Reason    string          `json:"reason,omitempty"`
}

func Open(dir string, maxRecordBytes int64) (*Queue, error) {
	return OpenWithOptions(dir, Options{
		MaxRecordBytes: maxRecordBytes,
		MaxBytes:       maxRecordBytes * 32,
		MaxRecords:     10_000,
		MaxAge:         7 * 24 * time.Hour,
	})
}

func OpenWithOptions(dir string, options Options) (*Queue, error) {
	if options.MaxRecordBytes <= 0 || options.MaxBytes < options.MaxRecordBytes || options.MaxRecords <= 0 || options.MaxAge <= 0 {
		return nil, errors.New("invalid spool limits")
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	q := &Queue{
		dir:        dir,
		recordsDir: filepath.Join(dir, recordsDirName),
		deadDir:    filepath.Join(dir, deadLetterDirName),
		legacyPath: filepath.Join(dir, legacyQueueFileName),
		options:    options,
	}
	if err := os.MkdirAll(q.recordsDir, 0o700); err != nil {
		return nil, err
	}
	if err := os.MkdirAll(q.deadDir, 0o700); err != nil {
		return nil, err
	}
	if err := q.migrateLegacy(); err != nil {
		return nil, err
	}
	if err := q.rebuildIndex(); err != nil {
		return nil, err
	}
	return q, nil
}

func (q *Queue) Push(payload []byte) (string, error) {
	if len(payload) == 0 {
		return "", errors.New("payload is empty")
	}
	if int64(len(payload)) > q.options.MaxRecordBytes {
		return "", fmt.Errorf("payload size %d exceeds max record size %d", len(payload), q.options.MaxRecordBytes)
	}

	q.mu.Lock()
	defer q.mu.Unlock()
	if err := q.expireLocked(time.Now().UTC()); err != nil {
		return "", err
	}
	id, err := newID()
	if err != nil {
		return "", err
	}
	record := diskRecord{ID: id, Payload: append(json.RawMessage(nil), payload...), CreatedAt: time.Now().UTC()}
	encoded, err := json.Marshal(record)
	if err != nil {
		return "", err
	}
	if err := q.makeRoomLocked(int64(len(encoded)), 1); err != nil {
		return "", err
	}
	recordSize, err := q.writeRecordLocked(q.recordsDir, record)
	if err != nil {
		return "", err
	}
	q.insertActiveLocked(recordMeta{ID: id, Size: recordSize, CreatedAt: record.CreatedAt, ModifiedAt: record.CreatedAt})
	return id, nil
}

func (q *Queue) Peek(limit int) ([]Record, error) {
	if limit <= 0 {
		return nil, nil
	}
	q.mu.Lock()
	defer q.mu.Unlock()
	if err := q.expireLocked(time.Now().UTC()); err != nil {
		return nil, err
	}
	records := make([]Record, 0, min(limit, len(q.active)))
	for index := q.activeHead; index < len(q.activeOrder) && len(records) < limit; {
		id := q.activeOrder[index]
		if _, exists := q.active[id]; !exists {
			index++
			continue
		}
		path := filepath.Join(q.recordsDir, id+".json")
		record, err := q.readRecord(path)
		if err != nil {
			if quarantineErr := q.quarantineCorruptLocked(path, err); quarantineErr != nil {
				return nil, errors.Join(
					fmt.Errorf("read spool record %s: %w", filepath.Base(path), err),
					fmt.Errorf("quarantine corrupt spool record: %w", quarantineErr),
				)
			}
			// quarantine removes the current ID from the active index. Keep
			// walking the immutable order slice and skip its tombstone.
			index++
			continue
		}
		records = append(records, Record{ID: record.ID, Payload: append([]byte(nil), record.Payload...), CreatedAt: record.CreatedAt})
		index++
	}
	q.compactActiveOrderLocked()
	return records, nil
}

func (q *Queue) Ack(ids []string) error {
	if len(ids) == 0 {
		return nil
	}
	q.mu.Lock()
	defer q.mu.Unlock()
	for _, id := range ids {
		if err := validateID(id); err != nil {
			return err
		}
		if err := os.Remove(filepath.Join(q.recordsDir, id+".json")); err != nil && !errors.Is(err, os.ErrNotExist) {
			return err
		}
		q.removeActiveLocked(id)
	}
	q.compactActiveOrderLocked()
	return syncDirectory(q.recordsDir)
}

func (q *Queue) Reject(id, reason string) error {
	q.mu.Lock()
	defer q.mu.Unlock()
	if err := validateID(id); err != nil {
		return err
	}
	path := filepath.Join(q.recordsDir, id+".json")
	record, err := q.readRecord(path)
	if err != nil {
		return err
	}
	record.Reason = reason
	recordSize, err := q.writeRecordLocked(q.deadDir, record)
	if err != nil {
		return err
	}
	q.insertDeadLocked(recordMeta{ID: record.ID, Size: recordSize, CreatedAt: record.CreatedAt, ModifiedAt: time.Now().UTC()})
	if err := os.Remove(path); err != nil {
		return err
	}
	q.removeActiveLocked(id)
	q.compactActiveOrderLocked()
	if err := syncDirectory(q.recordsDir); err != nil {
		return err
	}
	return q.pruneDeadLocked(time.Now().UTC(), 0, 0)
}

func (q *Queue) Stats() (Stats, error) {
	q.mu.Lock()
	defer q.mu.Unlock()
	if err := q.expireLocked(time.Now().UTC()); err != nil {
		return Stats{}, err
	}
	return q.statsLocked()
}

func (q *Queue) statsLocked() (Stats, error) {
	stats := Stats{
		Records:           len(q.active),
		Bytes:             q.activeBytes,
		OldestAt:          q.oldestAt,
		DeadLetterRecords: len(q.dead),
		DeadLetterBytes:   q.deadBytes,
	}
	stats.TotalRecords = stats.Records + stats.DeadLetterRecords
	stats.TotalBytes = stats.Bytes + stats.DeadLetterBytes
	return stats, nil
}

func (q *Queue) expireLocked(now time.Time) error {
	if q.oldestAt.IsZero() || now.Sub(q.oldestAt) <= q.options.MaxAge {
		return q.pruneDeadLocked(now, 0, 0)
	}
	ids := append([]string(nil), q.activeOrder[q.activeHead:]...)
	for _, id := range ids {
		meta, exists := q.active[id]
		if !exists || now.Sub(meta.CreatedAt) <= q.options.MaxAge {
			continue
		}
		path := filepath.Join(q.recordsDir, id+".json")
		record, err := q.readRecord(path)
		if err != nil {
			if quarantineErr := q.quarantineCorruptLocked(path, err); quarantineErr != nil {
				return errors.Join(err, quarantineErr)
			}
			continue
		}
		record.Reason = "expired after " + q.options.MaxAge.String()
		recordSize, err := q.writeRecordLocked(q.deadDir, record)
		if err != nil {
			return err
		}
		q.insertDeadLocked(recordMeta{ID: record.ID, Size: recordSize, CreatedAt: record.CreatedAt, ModifiedAt: now})
		if err := os.Remove(path); err != nil {
			return err
		}
		q.removeActiveLocked(record.ID)
		if err := syncDirectory(q.recordsDir); err != nil {
			return err
		}
	}
	q.compactActiveOrderLocked()
	return q.pruneDeadLocked(now, 0, 0)
}

func (q *Queue) migrateLegacy() error {
	q.mu.Lock()
	defer q.mu.Unlock()
	file, err := os.Open(q.legacyPath)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	scanner := bufio.NewScanner(file)
	scanner.Buffer(make([]byte, 0, 64*1024), int(q.options.MaxRecordBytes)+4096)
	for scanner.Scan() {
		if len(strings.TrimSpace(scanner.Text())) == 0 {
			continue
		}
		var record diskRecord
		if err := json.Unmarshal(scanner.Bytes(), &record); err != nil {
			_ = file.Close()
			return fmt.Errorf("invalid legacy spool record: %w", err)
		}
		if _, err := q.writeRecordLocked(q.recordsDir, record); err != nil {
			_ = file.Close()
			return err
		}
	}
	if err := scanner.Err(); err != nil {
		_ = file.Close()
		return err
	}
	if err := file.Close(); err != nil {
		return err
	}
	return os.Remove(q.legacyPath)
}

func (q *Queue) rebuildIndex() error {
	q.mu.Lock()
	defer q.mu.Unlock()

	q.active = map[string]recordMeta{}
	q.activeOrder = nil
	q.activeHead = 0
	q.dead = map[string]recordMeta{}
	q.deadOrder = nil
	q.activeBytes = 0
	q.deadBytes = 0
	q.oldestAt = time.Time{}
	q.oldestDeadAt = time.Time{}

	activePaths, err := q.recordPathsLocked(q.recordsDir)
	if err != nil {
		return err
	}
	type corruptRecord struct {
		path string
		err  error
	}
	corrupt := make([]corruptRecord, 0)
	for _, path := range activePaths {
		record, readErr := q.readRecord(path)
		if readErr != nil {
			corrupt = append(corrupt, corruptRecord{path: path, err: readErr})
			continue
		}
		info, err := os.Stat(path)
		if err != nil {
			return err
		}
		q.insertActiveLocked(recordMeta{ID: record.ID, Size: info.Size(), CreatedAt: record.CreatedAt, ModifiedAt: info.ModTime()})
	}

	deadPaths, err := q.recordPathsLocked(q.deadDir)
	if err != nil {
		return err
	}
	for _, path := range deadPaths {
		info, err := os.Stat(path)
		if err != nil {
			return err
		}
		id := strings.TrimSuffix(filepath.Base(path), ".json")
		q.insertDeadLocked(recordMeta{ID: id, Size: info.Size(), ModifiedAt: info.ModTime()})
	}

	for _, record := range corrupt {
		if err := q.quarantineCorruptLocked(record.path, record.err); err != nil {
			return err
		}
	}
	return q.pruneDeadLocked(time.Now().UTC(), 0, 0)
}

func (q *Queue) insertActiveLocked(meta recordMeta) {
	if previous, exists := q.active[meta.ID]; exists {
		q.activeBytes -= previous.Size
	} else {
		insertAt := sort.SearchStrings(q.activeOrder, meta.ID)
		q.activeOrder = insertSortedID(q.activeOrder, meta.ID)
		// activeHead skips acknowledged tombstones at the front of activeOrder.
		// A clock rollback can generate a new time-prefixed ID before that head;
		// rewind to the insertion point so the new record remains deliverable.
		if insertAt < q.activeHead {
			q.activeHead = insertAt
		}
	}
	q.active[meta.ID] = meta
	q.activeBytes += meta.Size
	if q.oldestAt.IsZero() || (!meta.CreatedAt.IsZero() && meta.CreatedAt.Before(q.oldestAt)) {
		q.oldestAt = meta.CreatedAt
	}
}

func (q *Queue) removeActiveLocked(id string) {
	meta, exists := q.active[id]
	if !exists {
		return
	}
	delete(q.active, id)
	q.activeBytes -= meta.Size
	if q.activeBytes < 0 {
		q.activeBytes = 0
	}
	for q.activeHead < len(q.activeOrder) {
		if _, exists := q.active[q.activeOrder[q.activeHead]]; exists {
			break
		}
		q.activeHead++
	}
	if !meta.CreatedAt.IsZero() && meta.CreatedAt.Equal(q.oldestAt) {
		q.oldestAt = time.Time{}
		if q.activeHead < len(q.activeOrder) {
			q.oldestAt = q.active[q.activeOrder[q.activeHead]].CreatedAt
		}
	}
}

func (q *Queue) compactActiveOrderLocked() {
	if q.activeHead < 1024 || q.activeHead*2 < len(q.activeOrder) {
		return
	}
	compacted := make([]string, 0, len(q.active))
	for _, id := range q.activeOrder[q.activeHead:] {
		if _, exists := q.active[id]; exists {
			compacted = append(compacted, id)
		}
	}
	q.activeOrder = compacted
	q.activeHead = 0
}

func (q *Queue) insertDeadLocked(meta recordMeta) {
	if previous, exists := q.dead[meta.ID]; exists {
		q.deadBytes -= previous.Size
	} else {
		q.deadOrder = insertSortedID(q.deadOrder, meta.ID)
	}
	q.dead[meta.ID] = meta
	q.deadBytes += meta.Size
	if q.oldestDeadAt.IsZero() || (!meta.ModifiedAt.IsZero() && meta.ModifiedAt.Before(q.oldestDeadAt)) {
		q.oldestDeadAt = meta.ModifiedAt
	}
}

func (q *Queue) removeDeadLocked(id string) {
	meta, exists := q.dead[id]
	if !exists {
		return
	}
	delete(q.dead, id)
	q.deadOrder = removeSortedID(q.deadOrder, id)
	q.deadBytes -= meta.Size
	if q.deadBytes < 0 {
		q.deadBytes = 0
	}
	if !meta.ModifiedAt.IsZero() && meta.ModifiedAt.Equal(q.oldestDeadAt) {
		q.oldestDeadAt = time.Time{}
		for _, candidate := range q.dead {
			if q.oldestDeadAt.IsZero() || (!candidate.ModifiedAt.IsZero() && candidate.ModifiedAt.Before(q.oldestDeadAt)) {
				q.oldestDeadAt = candidate.ModifiedAt
			}
		}
	}
}

func insertSortedID(ids []string, id string) []string {
	index := sort.SearchStrings(ids, id)
	if index < len(ids) && ids[index] == id {
		return ids
	}
	ids = append(ids, "")
	copy(ids[index+1:], ids[index:])
	ids[index] = id
	return ids
}

func removeSortedID(ids []string, id string) []string {
	index := sort.SearchStrings(ids, id)
	if index >= len(ids) || ids[index] != id {
		return ids
	}
	copy(ids[index:], ids[index+1:])
	return ids[:len(ids)-1]
}

func (q *Queue) writeRecordLocked(targetDir string, record diskRecord) (int64, error) {
	if err := validateID(record.ID); err != nil {
		return 0, err
	}
	encoded, err := json.Marshal(record)
	if err != nil {
		return 0, err
	}
	finalPath := filepath.Join(targetDir, record.ID+".json")
	if info, err := os.Stat(finalPath); err == nil {
		return info.Size(), nil
	} else if !errors.Is(err, os.ErrNotExist) {
		return 0, err
	}
	tempPath := filepath.Join(targetDir, "."+record.ID+".tmp")
	file, err := os.OpenFile(tempPath, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
	if err != nil {
		return 0, err
	}
	cleanup := func() { _ = os.Remove(tempPath) }
	if _, err := file.Write(encoded); err != nil {
		_ = file.Close()
		cleanup()
		return 0, err
	}
	if err := file.Sync(); err != nil {
		_ = file.Close()
		cleanup()
		return 0, err
	}
	if err := file.Close(); err != nil {
		cleanup()
		return 0, err
	}
	if err := os.Rename(tempPath, finalPath); err != nil {
		cleanup()
		return 0, err
	}
	if err := syncDirectory(targetDir); err != nil {
		return 0, err
	}
	return int64(len(encoded)), nil
}

func (q *Queue) makeRoomLocked(nextBytes int64, nextRecords int) error {
	if err := q.pruneDeadLocked(time.Now().UTC(), nextBytes, nextRecords); err != nil {
		return err
	}
	stats, err := q.statsLocked()
	if err != nil {
		return err
	}
	if stats.TotalRecords+nextRecords > q.options.MaxRecords || stats.TotalBytes+nextBytes > q.options.MaxBytes {
		return fmt.Errorf(
			"%w: records=%d bytes=%d next_record_bytes=%d",
			ErrQueueFull,
			stats.TotalRecords,
			stats.TotalBytes,
			nextBytes,
		)
	}
	return nil
}

func (q *Queue) pruneDeadLocked(now time.Time, reserveBytes int64, reserveRecords int) error {
	removed := false
	if !q.oldestDeadAt.IsZero() && now.Sub(q.oldestDeadAt) > q.options.MaxAge {
		ids := append([]string(nil), q.deadOrder...)
		for _, id := range ids {
			meta, exists := q.dead[id]
			if !exists || now.Sub(meta.ModifiedAt) <= q.options.MaxAge {
				continue
			}
			if err := os.Remove(filepath.Join(q.deadDir, id+".json")); err != nil && !errors.Is(err, os.ErrNotExist) {
				return err
			}
			q.removeDeadLocked(id)
			removed = true
		}
	}
	if removed {
		if err := syncDirectory(q.deadDir); err != nil {
			return err
		}
	}

	for {
		stats, err := q.statsLocked()
		if err != nil {
			return err
		}
		if stats.TotalRecords+reserveRecords <= q.options.MaxRecords && stats.TotalBytes+reserveBytes <= q.options.MaxBytes {
			return nil
		}
		if len(q.deadOrder) == 0 {
			return nil
		}
		id := q.deadOrder[0]
		if err := os.Remove(filepath.Join(q.deadDir, id+".json")); err != nil && !errors.Is(err, os.ErrNotExist) {
			return err
		}
		q.removeDeadLocked(id)
		if err := syncDirectory(q.deadDir); err != nil {
			return err
		}
	}
}

func (q *Queue) quarantineCorruptLocked(path string, readErr error) error {
	info, err := os.Stat(path)
	if err != nil {
		return err
	}
	captureLimit := int64(maxQuarantineCaptureBytes)
	if q.options.MaxRecordBytes > 0 {
		payloadBudget := (q.options.MaxRecordBytes - 512) * 3 / 4
		if payloadBudget < 0 {
			payloadBudget = 0
		}
		if payloadBudget < captureLimit {
			captureLimit = payloadBudget
		}
	}
	file, err := os.Open(path)
	if err != nil {
		return err
	}
	raw, readCaptureErr := io.ReadAll(io.LimitReader(file, captureLimit+1))
	closeErr := file.Close()
	if readCaptureErr != nil {
		return readCaptureErr
	}
	if closeErr != nil {
		return closeErr
	}
	truncated := int64(len(raw)) > captureLimit || info.Size() > captureLimit
	if int64(len(raw)) > captureLimit {
		raw = raw[:captureLimit]
	}
	id, err := newID()
	if err != nil {
		return err
	}
	diagnostic, err := json.Marshal(map[string]any{
		"original_file":     filepath.Base(path),
		"original_size":     info.Size(),
		"content_base64":    base64.StdEncoding.EncodeToString(raw),
		"content_truncated": truncated,
	})
	if err != nil {
		return err
	}
	if int64(len(diagnostic)) > q.options.MaxRecordBytes {
		diagnostic, err = json.Marshal(map[string]any{
			"original_file":     filepath.Base(path),
			"original_size":     info.Size(),
			"content_truncated": true,
		})
		if err != nil {
			return err
		}
	}
	if int64(len(diagnostic)) > q.options.MaxRecordBytes {
		diagnostic = []byte("0")
	}
	reason := "corrupt spool record: " + readErr.Error()
	if len(reason) > 2048 {
		reason = reason[:2048]
	}
	record := diskRecord{
		ID:        id,
		Payload:   diagnostic,
		CreatedAt: time.Now().UTC(),
		Reason:    reason,
	}
	encodedRecord, err := json.Marshal(record)
	if err != nil {
		return err
	}
	originalID := strings.TrimSuffix(filepath.Base(path), ".json")
	reserveBytes := int64(len(encodedRecord))
	reserveRecords := 1
	if originalMeta, indexed := q.active[originalID]; indexed {
		reserveRecords = 0
		reserveBytes -= originalMeta.Size
		if reserveBytes < 0 {
			reserveBytes = 0
		}
	}
	if err := q.makeRoomLocked(reserveBytes, reserveRecords); err != nil {
		return err
	}
	recordSize, err := q.writeRecordLocked(q.deadDir, record)
	if err != nil {
		return err
	}
	q.insertDeadLocked(recordMeta{ID: record.ID, Size: recordSize, CreatedAt: record.CreatedAt, ModifiedAt: record.CreatedAt})
	if err := os.Remove(path); err != nil {
		return err
	}
	q.removeActiveLocked(originalID)
	if err := syncDirectory(q.recordsDir); err != nil {
		return err
	}
	return q.pruneDeadLocked(time.Now().UTC(), 0, 0)
}

func syncDirectory(path string) error {
	if runtime.GOOS == "windows" {
		return nil
	}
	directory, err := os.Open(path)
	if err != nil {
		return err
	}
	defer directory.Close()
	return directory.Sync()
}

func (q *Queue) recordPathsLocked(dir string) ([]string, error) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil, err
	}
	paths := make([]string, 0, len(entries))
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".json") {
			continue
		}
		paths = append(paths, filepath.Join(dir, entry.Name()))
	}
	sort.Strings(paths)
	return paths, nil
}

func (q *Queue) readRecord(path string) (diskRecord, error) {
	info, err := os.Stat(path)
	if err != nil {
		return diskRecord{}, err
	}
	if info.Size() > q.options.MaxRecordBytes+4096 {
		return diskRecord{}, fmt.Errorf("record file exceeds limit: %d", info.Size())
	}
	encoded, err := os.ReadFile(path)
	if err != nil {
		return diskRecord{}, err
	}
	var record diskRecord
	if err := json.Unmarshal(encoded, &record); err != nil {
		return diskRecord{}, err
	}
	if record.ID == "" || len(record.Payload) == 0 || record.CreatedAt.IsZero() {
		return diskRecord{}, errors.New("record is incomplete")
	}
	expectedID := strings.TrimSuffix(filepath.Base(path), ".json")
	if record.ID != expectedID {
		return diskRecord{}, fmt.Errorf("record id %q does not match file %q", record.ID, expectedID)
	}
	if err := validateID(record.ID); err != nil {
		return diskRecord{}, err
	}
	return record, nil
}

func validateID(id string) error {
	if id == "" || strings.ContainsAny(id, `/\\`) || strings.Contains(id, "..") {
		return errors.New("invalid spool record id")
	}
	return nil
}

func newID() (string, error) {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return fmt.Sprintf("%020d-%s", time.Now().UTC().UnixNano(), hex.EncodeToString(b)), nil
}
