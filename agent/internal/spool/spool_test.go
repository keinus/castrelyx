package spool

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestQueuePersistsRecordsAndAcksFIFO(t *testing.T) {
	queue, err := Open(t.TempDir(), 1024*1024)
	if err != nil {
		t.Fatal(err)
	}

	first, err := queue.Push([]byte(`{"n":1}`))
	if err != nil {
		t.Fatal(err)
	}
	second, err := queue.Push([]byte(`{"n":2}`))
	if err != nil {
		t.Fatal(err)
	}

	records, err := queue.Peek(10)
	if err != nil {
		t.Fatal(err)
	}
	if len(records) != 2 {
		t.Fatalf("records length = %d", len(records))
	}
	if records[0].ID != first || records[1].ID != second {
		t.Fatalf("records returned out of order: %#v", records)
	}

	if err := queue.Ack([]string{first}); err != nil {
		t.Fatal(err)
	}

	records, err = queue.Peek(10)
	if err != nil {
		t.Fatal(err)
	}
	if len(records) != 1 || records[0].ID != second {
		t.Fatalf("ack did not remove first record: %#v", records)
	}
}

func TestQueueDoesNotSkipRecordInsertedBeforeActiveHead(t *testing.T) {
	queue, err := Open(t.TempDir(), 1024*1024)
	if err != nil {
		t.Fatal(err)
	}

	createdAt := time.Now().UTC()
	queue.mu.Lock()
	for _, record := range []diskRecord{
		{ID: "00000000000000000002-first", Payload: json.RawMessage(`{"n":2}`), CreatedAt: createdAt},
		{ID: "00000000000000000003-second", Payload: json.RawMessage(`{"n":3}`), CreatedAt: createdAt.Add(time.Second)},
	} {
		size, writeErr := queue.writeRecordLocked(queue.recordsDir, record)
		if writeErr != nil {
			queue.mu.Unlock()
			t.Fatal(writeErr)
		}
		queue.insertActiveLocked(recordMeta{ID: record.ID, Size: size, CreatedAt: record.CreatedAt})
	}
	queue.mu.Unlock()

	if err := queue.Ack([]string{"00000000000000000002-first"}); err != nil {
		t.Fatal(err)
	}

	clockRollbackRecord := diskRecord{
		ID:        "00000000000000000001-clock-rollback",
		Payload:   json.RawMessage(`{"n":1}`),
		CreatedAt: createdAt.Add(-time.Second),
	}
	queue.mu.Lock()
	size, err := queue.writeRecordLocked(queue.recordsDir, clockRollbackRecord)
	if err == nil {
		queue.insertActiveLocked(recordMeta{ID: clockRollbackRecord.ID, Size: size, CreatedAt: clockRollbackRecord.CreatedAt})
	}
	queue.mu.Unlock()
	if err != nil {
		t.Fatal(err)
	}

	records, err := queue.Peek(10)
	if err != nil {
		t.Fatal(err)
	}
	if len(records) != 2 || records[0].ID != clockRollbackRecord.ID || records[1].ID != "00000000000000000003-second" {
		t.Fatalf("records inserted before active head were skipped or reordered: %#v", records)
	}
}

func TestQueueRebuildsIncrementalIndexOnReopen(t *testing.T) {
	dir := t.TempDir()
	options := Options{MaxRecordBytes: 1024, MaxBytes: 16 * 1024, MaxRecords: 10, MaxAge: time.Hour}
	queue, err := OpenWithOptions(dir, options)
	if err != nil {
		t.Fatal(err)
	}
	deadID, err := queue.Push([]byte(`{"n":1}`))
	if err != nil {
		t.Fatal(err)
	}
	pendingID, err := queue.Push([]byte(`{"n":2}`))
	if err != nil {
		t.Fatal(err)
	}
	if err := queue.Reject(deadID, "permanent"); err != nil {
		t.Fatal(err)
	}

	reopened, err := OpenWithOptions(dir, options)
	if err != nil {
		t.Fatal(err)
	}
	stats, err := reopened.Stats()
	if err != nil {
		t.Fatal(err)
	}
	if stats.Records != 1 || stats.DeadLetterRecords != 1 || stats.TotalBytes <= 0 {
		t.Fatalf("rebuilt stats = %#v", stats)
	}
	records, err := reopened.Peek(10)
	if err != nil {
		t.Fatal(err)
	}
	if len(records) != 1 || records[0].ID != pendingID {
		t.Fatalf("rebuilt pending order = %#v", records)
	}
}

func TestQueueRejectsRecordsPastLimit(t *testing.T) {
	queue, err := Open(t.TempDir(), 4)
	if err != nil {
		t.Fatal(err)
	}

	if _, err := queue.Push([]byte(`{"too":"large"}`)); err == nil {
		t.Fatal("Push accepted a record larger than the spool limit")
	}
}

func TestOpenWithOptionsRejectsInvalidLimits(t *testing.T) {
	tests := []Options{
		{MaxRecordBytes: 0, MaxBytes: 1024, MaxRecords: 1, MaxAge: time.Hour},
		{MaxRecordBytes: 1024, MaxBytes: 512, MaxRecords: 1, MaxAge: time.Hour},
		{MaxRecordBytes: 1024, MaxBytes: 1024, MaxRecords: 0, MaxAge: time.Hour},
		{MaxRecordBytes: 1024, MaxBytes: 1024, MaxRecords: 1, MaxAge: 0},
	}
	for i, options := range tests {
		if _, err := OpenWithOptions(filepath.Join(t.TempDir(), "queue"), options); err == nil {
			t.Fatalf("case %d accepted invalid options: %#v", i, options)
		}
	}
}

func TestSegmentedQueueEnforcesRecordLimitAndReleasesCapacityAfterAck(t *testing.T) {
	dir := t.TempDir()
	queue, err := OpenWithOptions(dir, Options{
		MaxRecordBytes: 128,
		MaxBytes:       4096,
		MaxRecords:     2,
		MaxAge:         time.Hour,
	})
	if err != nil {
		t.Fatal(err)
	}
	first, err := queue.Push([]byte(`{"n":1}`))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := queue.Push([]byte(`{"n":2}`)); err != nil {
		t.Fatal(err)
	}
	if _, err := queue.Push([]byte(`{"n":3}`)); !errors.Is(err, ErrQueueFull) {
		t.Fatalf("third Push error = %v, want ErrQueueFull", err)
	}
	if err := queue.Ack([]string{first}); err != nil {
		t.Fatal(err)
	}
	if _, err := queue.Push([]byte(`{"n":3}`)); err != nil {
		t.Fatalf("Push after Ack returned error: %v", err)
	}

	entries, err := os.ReadDir(filepath.Join(dir, recordsDirName))
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 2 {
		t.Fatalf("segment files = %d, want 2", len(entries))
	}
	if _, err := os.Stat(filepath.Join(dir, legacyQueueFileName)); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("legacy queue unexpectedly exists: %v", err)
	}
}

func TestSegmentedQueueNeverExceedsConfiguredByteLimit(t *testing.T) {
	const maxBytes = 512
	queue, err := OpenWithOptions(t.TempDir(), Options{
		MaxRecordBytes: 128,
		MaxBytes:       maxBytes,
		MaxRecords:     100,
		MaxAge:         time.Hour,
	})
	if err != nil {
		t.Fatal(err)
	}
	payload := []byte(`"` + strings.Repeat("x", 60) + `"`)
	accepted := 0
	for accepted < 100 {
		_, err := queue.Push(payload)
		if errors.Is(err, ErrQueueFull) {
			break
		}
		if err != nil {
			t.Fatalf("Push returned unexpected error: %v", err)
		}
		accepted++
		stats, err := queue.Stats()
		if err != nil {
			t.Fatal(err)
		}
		if stats.Bytes > maxBytes {
			t.Fatalf("successful Push exceeded MaxBytes: stats=%d limit=%d accepted=%d", stats.Bytes, maxBytes, accepted)
		}
	}
	if accepted == 0 || accepted >= 100 {
		t.Fatalf("byte limit did not admit then stop records: accepted=%d", accepted)
	}
}

func TestOpenMigratesLegacyQueueIntoOrderedSegments(t *testing.T) {
	dir := t.TempDir()
	createdAt := time.Now().UTC().Add(-time.Minute)
	records := []diskRecord{
		{ID: "00000000000000000001-first", Payload: json.RawMessage(`{"n":1}`), CreatedAt: createdAt},
		{ID: "00000000000000000002-second", Payload: json.RawMessage(`{"n":2}`), CreatedAt: createdAt.Add(time.Second)},
	}
	writeLegacyQueue(t, dir, records)

	queue, err := OpenWithOptions(dir, Options{MaxRecordBytes: 1024, MaxBytes: 16 * 1024, MaxRecords: 10, MaxAge: time.Hour})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(dir, legacyQueueFileName)); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("legacy queue was not removed: %v", err)
	}
	peeked, err := queue.Peek(10)
	if err != nil {
		t.Fatal(err)
	}
	if len(peeked) != 2 || peeked[0].ID != records[0].ID || peeked[1].ID != records[1].ID {
		t.Fatalf("migrated records out of order: %#v", peeked)
	}
	if !bytes.Equal(peeked[0].Payload, records[0].Payload) || !peeked[0].CreatedAt.Equal(records[0].CreatedAt) {
		t.Fatalf("migrated record changed: %#v", peeked[0])
	}
	stats, err := queue.Stats()
	if err != nil {
		t.Fatal(err)
	}
	if stats.Records != 2 || stats.Bytes <= 0 || !stats.OldestAt.Equal(records[0].CreatedAt) {
		t.Fatalf("unexpected migrated stats: %#v", stats)
	}
}

func TestRejectMovesRecordToDeadLetterAndUpdatesStats(t *testing.T) {
	queue, err := Open(t.TempDir(), 1024)
	if err != nil {
		t.Fatal(err)
	}
	id, err := queue.Push([]byte(`{"n":1}`))
	if err != nil {
		t.Fatal(err)
	}
	if err := queue.Reject(id, "permanent delivery failure"); err != nil {
		t.Fatal(err)
	}
	stats, err := queue.Stats()
	if err != nil {
		t.Fatal(err)
	}
	if stats.Records != 0 || stats.DeadLetterRecords != 1 || !stats.OldestAt.IsZero() {
		t.Fatalf("unexpected stats after Reject: %#v", stats)
	}
	record, err := queue.readRecord(filepath.Join(queue.deadDir, id+".json"))
	if err != nil {
		t.Fatal(err)
	}
	if record.Reason != "permanent delivery failure" {
		t.Fatalf("dead-letter reason = %q", record.Reason)
	}
}

func TestPeekExpiresOldRecordsToDeadLetter(t *testing.T) {
	dir := t.TempDir()
	now := time.Now().UTC()
	old := diskRecord{ID: "00000000000000000001-old", Payload: json.RawMessage(`{"old":true}`), CreatedAt: now.Add(-2 * time.Hour)}
	fresh := diskRecord{ID: "00000000000000000002-fresh", Payload: json.RawMessage(`{"fresh":true}`), CreatedAt: now}
	writeLegacyQueue(t, dir, []diskRecord{old, fresh})
	queue, err := OpenWithOptions(dir, Options{MaxRecordBytes: 1024, MaxBytes: 16 * 1024, MaxRecords: 10, MaxAge: time.Hour})
	if err != nil {
		t.Fatal(err)
	}

	records, err := queue.Peek(10)
	if err != nil {
		t.Fatal(err)
	}
	if len(records) != 1 || records[0].ID != fresh.ID {
		t.Fatalf("records after expiry = %#v", records)
	}
	stats, err := queue.Stats()
	if err != nil {
		t.Fatal(err)
	}
	if stats.Records != 1 || stats.DeadLetterRecords != 1 || !stats.OldestAt.Equal(fresh.CreatedAt) {
		t.Fatalf("unexpected expiry stats: %#v", stats)
	}
	expired, err := queue.readRecord(filepath.Join(queue.deadDir, old.ID+".json"))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(expired.Reason, "expired after") {
		t.Fatalf("expiry reason = %q", expired.Reason)
	}
}

func TestQueueSerializesConcurrentPushesWithoutLosingRecords(t *testing.T) {
	queue, err := OpenWithOptions(t.TempDir(), Options{MaxRecordBytes: 512, MaxBytes: 1024 * 1024, MaxRecords: 100, MaxAge: time.Hour})
	if err != nil {
		t.Fatal(err)
	}
	const workers = 32
	errorsCh := make(chan error, workers)
	var wg sync.WaitGroup
	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, err := queue.Push([]byte(`{"ok":true}`))
			errorsCh <- err
		}()
	}
	wg.Wait()
	close(errorsCh)
	for err := range errorsCh {
		if err != nil {
			t.Fatal(err)
		}
	}
	stats, err := queue.Stats()
	if err != nil {
		t.Fatal(err)
	}
	if stats.Records != workers {
		t.Fatalf("concurrent records = %d, want %d", stats.Records, workers)
	}
}

func TestQueueTotalLimitsIncludeDeadLettersAndPruneOldest(t *testing.T) {
	t.Run("record cap", func(t *testing.T) {
		queue, err := OpenWithOptions(t.TempDir(), Options{
			MaxRecordBytes: 128,
			MaxBytes:       16 * 1024,
			MaxRecords:     2,
			MaxAge:         time.Hour,
		})
		if err != nil {
			t.Fatal(err)
		}
		first, err := queue.Push([]byte(`{"n":1}`))
		if err != nil {
			t.Fatal(err)
		}
		if err := queue.Reject(first, "permanent first"); err != nil {
			t.Fatal(err)
		}
		second, err := queue.Push([]byte(`{"n":2}`))
		if err != nil {
			t.Fatal(err)
		}
		if err := queue.Reject(second, "permanent second"); err != nil {
			t.Fatal(err)
		}
		if _, err := os.Stat(filepath.Join(queue.deadDir, first+".json")); err != nil {
			t.Fatalf("oldest dead letter missing before pressure: %v", err)
		}
		if _, err := queue.Push([]byte(`{"n":3}`)); err != nil {
			t.Fatalf("Push did not reclaim dead-letter capacity: %v", err)
		}
		stats, err := queue.Stats()
		if err != nil {
			t.Fatal(err)
		}
		if stats.Records != 1 || stats.DeadLetterRecords != 1 || stats.TotalRecords != 2 || stats.TotalBytes > queue.options.MaxBytes {
			t.Fatalf("unexpected total stats after record pruning: %#v", stats)
		}
		if _, err := os.Stat(filepath.Join(queue.deadDir, first+".json")); !errors.Is(err, os.ErrNotExist) {
			t.Fatalf("oldest dead letter was not pruned: %v", err)
		}
		if _, err := os.Stat(filepath.Join(queue.deadDir, second+".json")); err != nil {
			t.Fatalf("newest dead letter was pruned instead: %v", err)
		}
	})

	t.Run("byte cap", func(t *testing.T) {
		queue, err := OpenWithOptions(t.TempDir(), Options{
			MaxRecordBytes: 128,
			MaxBytes:       700,
			MaxRecords:     10,
			MaxAge:         time.Hour,
		})
		if err != nil {
			t.Fatal(err)
		}
		payload := []byte(`"` + strings.Repeat("x", 100) + `"`)
		first, err := queue.Push(payload)
		if err != nil {
			t.Fatal(err)
		}
		if err := queue.Reject(first, "permanent first"); err != nil {
			t.Fatal(err)
		}
		second, err := queue.Push(payload)
		if err != nil {
			t.Fatal(err)
		}
		if err := queue.Reject(second, "permanent second"); err != nil {
			t.Fatal(err)
		}
		before, err := queue.Stats()
		if err != nil {
			t.Fatal(err)
		}
		if before.DeadLetterRecords != 2 || before.TotalBytes >= queue.options.MaxBytes {
			t.Fatalf("test setup did not retain two dead letters below cap: %#v", before)
		}
		if _, err := queue.Push(payload); err != nil {
			t.Fatalf("Push did not reclaim byte capacity: %v", err)
		}
		after, err := queue.Stats()
		if err != nil {
			t.Fatal(err)
		}
		if after.Records != 1 || after.DeadLetterRecords != 1 || after.TotalBytes > queue.options.MaxBytes {
			t.Fatalf("unexpected total stats after byte pruning: before=%#v after=%#v", before, after)
		}
		if _, err := os.Stat(filepath.Join(queue.deadDir, first+".json")); !errors.Is(err, os.ErrNotExist) {
			t.Fatalf("oldest dead letter was not pruned for bytes: %v", err)
		}
		if _, err := os.Stat(filepath.Join(queue.deadDir, second+".json")); err != nil {
			t.Fatalf("newest dead letter was pruned for bytes: %v", err)
		}
	})
}

func TestPeekQuarantinesCorruptSegmentAndContinuesWithLaterValidRecord(t *testing.T) {
	queue, err := OpenWithOptions(t.TempDir(), Options{
		MaxRecordBytes: 1024,
		MaxBytes:       16 * 1024,
		MaxRecords:     10,
		MaxAge:         time.Hour,
	})
	if err != nil {
		t.Fatal(err)
	}
	corruptID, err := queue.Push([]byte(`{"n":1}`))
	if err != nil {
		t.Fatal(err)
	}
	validID, err := queue.Push([]byte(`{"n":2}`))
	if err != nil {
		t.Fatal(err)
	}
	corruptPath := filepath.Join(queue.recordsDir, corruptID+".json")
	if err := os.WriteFile(corruptPath, []byte(`{"truncated":`), 0o600); err != nil {
		t.Fatal(err)
	}

	records, err := queue.Peek(10)
	if err != nil {
		t.Fatalf("Peek was blocked by corrupt segment: %v", err)
	}
	if len(records) != 1 || records[0].ID != validID || !bytes.Equal(records[0].Payload, []byte(`{"n":2}`)) {
		t.Fatalf("later valid record was not returned: %#v", records)
	}
	if _, err := os.Stat(corruptPath); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("corrupt active segment was not removed: %v", err)
	}
	stats, err := queue.Stats()
	if err != nil {
		t.Fatal(err)
	}
	if stats.Records != 1 || stats.DeadLetterRecords != 1 || stats.TotalRecords != 2 {
		t.Fatalf("unexpected quarantine stats: %#v", stats)
	}
	deadPaths, err := queue.recordPathsLocked(queue.deadDir)
	if err != nil {
		t.Fatal(err)
	}
	if len(deadPaths) != 1 {
		t.Fatalf("quarantine segments = %d, want 1", len(deadPaths))
	}
	quarantined, err := queue.readRecord(deadPaths[0])
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(quarantined.Reason, "corrupt spool record") {
		t.Fatalf("quarantine reason = %q", quarantined.Reason)
	}
	var diagnostic map[string]any
	if err := json.Unmarshal(quarantined.Payload, &diagnostic); err != nil {
		t.Fatal(err)
	}
	if diagnostic["original_file"] != filepath.Base(corruptPath) || diagnostic["content_base64"] == "" {
		t.Fatalf("quarantine diagnostic = %#v", diagnostic)
	}
}

func TestPushUsesIncrementalIndexWithoutRescanningPendingFiles(t *testing.T) {
	queue, err := OpenWithOptions(t.TempDir(), Options{
		MaxRecordBytes: 1024,
		MaxBytes:       16 * 1024,
		MaxRecords:     10,
		MaxAge:         time.Hour,
	})
	if err != nil {
		t.Fatal(err)
	}
	firstID, err := queue.Push([]byte(`{"n":1}`))
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(queue.recordsDir, firstID+".json"), []byte(`{"truncated":`), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := queue.Push([]byte(`{"n":2}`)); err != nil {
		t.Fatalf("indexed Push re-read an unrelated pending file: %v", err)
	}
	stats, err := queue.Stats()
	if err != nil {
		t.Fatal(err)
	}
	if stats.Records != 2 || stats.DeadLetterRecords != 0 {
		t.Fatalf("incremental index changed before Peek: %#v", stats)
	}
	records, err := queue.Peek(10)
	if err != nil {
		t.Fatal(err)
	}
	if len(records) != 1 || !bytes.Equal(records[0].Payload, []byte(`{"n":2}`)) {
		t.Fatalf("Peek did not quarantine corrupt record and preserve valid record: %#v", records)
	}
}

func TestQuarantineBoundsOversizedCorruptRecordAndPreservesCaps(t *testing.T) {
	queue, err := OpenWithOptions(t.TempDir(), Options{
		MaxRecordBytes: 1024,
		MaxBytes:       16 * 1024,
		MaxRecords:     10,
		MaxAge:         time.Hour,
	})
	if err != nil {
		t.Fatal(err)
	}
	id, err := queue.Push([]byte(`{"n":1}`))
	if err != nil {
		t.Fatal(err)
	}
	corruptPath := filepath.Join(queue.recordsDir, id+".json")
	const corruptSize = int64(32 * 1024 * 1024)
	if err := os.Truncate(corruptPath, corruptSize); err != nil {
		t.Fatal(err)
	}
	if records, err := queue.Peek(10); err != nil || len(records) != 0 {
		t.Fatalf("Peek oversized corrupt record = records=%#v err=%v", records, err)
	}
	stats, err := queue.Stats()
	if err != nil {
		t.Fatal(err)
	}
	if stats.Records != 0 || stats.DeadLetterRecords != 1 || stats.TotalBytes > queue.options.MaxBytes {
		t.Fatalf("quarantine did not preserve configured caps: %#v", stats)
	}
	deadPaths, err := queue.recordPathsLocked(queue.deadDir)
	if err != nil || len(deadPaths) != 1 {
		t.Fatalf("dead-letter paths = %v err=%v", deadPaths, err)
	}
	quarantined, err := queue.readRecord(deadPaths[0])
	if err != nil {
		t.Fatal(err)
	}
	if int64(len(quarantined.Payload)) > queue.options.MaxRecordBytes {
		t.Fatalf("diagnostic payload exceeds record cap: %d", len(quarantined.Payload))
	}
	var diagnostic map[string]any
	if err := json.Unmarshal(quarantined.Payload, &diagnostic); err != nil {
		t.Fatal(err)
	}
	if diagnostic["content_truncated"] != true || diagnostic["original_size"] != float64(corruptSize) {
		t.Fatalf("bounded diagnostic metadata = %#v", diagnostic)
	}
	captured, err := base64.StdEncoding.DecodeString(diagnostic["content_base64"].(string))
	if err != nil {
		t.Fatal(err)
	}
	if len(captured) > maxQuarantineCaptureBytes {
		t.Fatalf("captured corrupt content = %d bytes", len(captured))
	}
}

func writeLegacyQueue(t *testing.T, dir string, records []diskRecord) {
	t.Helper()
	var data []byte
	for _, record := range records {
		encoded, err := json.Marshal(record)
		if err != nil {
			t.Fatal(err)
		}
		data = append(data, encoded...)
		data = append(data, '\n')
	}
	if err := os.WriteFile(filepath.Join(dir, legacyQueueFileName), data, 0o600); err != nil {
		t.Fatal(err)
	}
}
