package agent

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"castrelyx/agent/internal/envelope"
	"castrelyx/agent/internal/spool"
)

type fakeCollector struct{}

func (fakeCollector) Name() string { return "fake" }

func (fakeCollector) Collect(context.Context) ([]envelope.Item, error) {
	return []envelope.Item{
		{Kind: "event", Type: "test", Key: "fake", Payload: map[string]any{"ok": true}},
	}, nil
}

type fakeSender struct {
	err     error
	batches []envelope.Batch
}

func (s *fakeSender) Send(_ context.Context, batch envelope.Batch) error {
	if s.err != nil {
		return s.err
	}
	s.batches = append(s.batches, batch)
	return nil
}

func TestRunOnceSendsCollectorBatch(t *testing.T) {
	q, err := spool.Open(t.TempDir(), 1024*1024)
	if err != nil {
		t.Fatal(err)
	}
	sender := &fakeSender{}
	a := New(Config{
		AgentID:  "agent-01",
		TenantID: "default",
	}, sender, q, []Collector{fakeCollector{}})

	if err := a.RunOnce(context.Background()); err != nil {
		t.Fatalf("RunOnce returned error: %v", err)
	}
	if len(sender.batches) != 1 {
		t.Fatalf("sent batches = %d", len(sender.batches))
	}
	if sender.batches[0].SourceID != "agent-01" || len(sender.batches[0].Items) != 1 {
		t.Fatalf("unexpected batch: %#v", sender.batches[0])
	}
}

func TestStartupCollectionHealthRejectsCriticalCollectorErrors(t *testing.T) {
	queue, err := spool.Open(t.TempDir(), 1024*1024)
	if err != nil {
		t.Fatal(err)
	}
	identity := &failingCollector{name: "identity"}
	metric := &countingCollector{name: "metric"}
	a := New(Config{AgentID: "agent-01"}, &fakeSender{}, queue, []Collector{identity, metric})

	if err := a.CollectOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	if a.StartupCollectionHealthy() {
		t.Fatal("collector_error-only identity result approved startup probation")
	}
	a.lastCollected["identity"] = time.Now().Add(-2 * collectorFailureRetryInterval)
	a.lastCollected["metric"] = time.Now().Add(-2 * collectorFailureRetryInterval)
	if err := a.CollectOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	if !a.StartupCollectionHealthy() {
		t.Fatal("recovered identity and metric collectors did not approve startup probation")
	}
}

func TestRunOnceSpoolsBatchWhenSendFails(t *testing.T) {
	q, err := spool.Open(t.TempDir(), 1024*1024)
	if err != nil {
		t.Fatal(err)
	}
	sender := &fakeSender{err: errors.New("offline")}
	a := New(Config{
		AgentID:  "agent-01",
		TenantID: "default",
	}, sender, q, []Collector{fakeCollector{}})

	if err := a.RunOnce(context.Background()); err == nil {
		t.Fatal("RunOnce returned nil error for failed send")
	}

	records, err := q.Peek(10)
	if err != nil {
		t.Fatal(err)
	}
	if len(records) != 1 {
		t.Fatalf("spooled records = %d", len(records))
	}

	var batch envelope.Batch
	if err := json.Unmarshal(records[0].Payload, &batch); err != nil {
		t.Fatalf("spooled payload is not a batch: %v", err)
	}
	if batch.SourceID != "agent-01" || len(batch.Items) != 1 {
		t.Fatalf("unexpected spooled batch: %#v", batch)
	}
}

func TestFlushPendingSendsSpooledBatchesAndAcks(t *testing.T) {
	q, err := spool.Open(t.TempDir(), 1024*1024)
	if err != nil {
		t.Fatal(err)
	}
	batch := envelope.NewBatch("agent", "agent-01", "default")
	batch.Add(envelope.Item{Kind: "event", Type: "test", Key: "spooled", Payload: map[string]any{"ok": true}})
	encoded, err := json.Marshal(batch)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := q.Push(encoded); err != nil {
		t.Fatal(err)
	}

	sender := &fakeSender{}
	a := New(Config{
		AgentID:  "agent-01",
		TenantID: "default",
	}, sender, q, []Collector{})

	if err := a.FlushPending(context.Background(), 10); err != nil {
		t.Fatalf("FlushPending returned error: %v", err)
	}
	if len(sender.batches) != 1 {
		t.Fatalf("sent batches = %d", len(sender.batches))
	}
	records, err := q.Peek(10)
	if err != nil {
		t.Fatal(err)
	}
	if len(records) != 0 {
		t.Fatalf("spool was not acked: %#v", records)
	}
}

type countingCollector struct {
	name        string
	calls       int
	cycleStarts []time.Time
}

type failingCollector struct {
	name  string
	calls int
}

func (c *failingCollector) Name() string { return c.name }

func (c *failingCollector) Collect(context.Context) ([]envelope.Item, error) {
	c.calls++
	if c.calls == 1 {
		return nil, errors.New("temporary collector failure")
	}
	return []envelope.Item{{Kind: "event", Type: "recovered", Key: c.name}}, nil
}

func (c *countingCollector) Name() string { return c.name }

func (c *countingCollector) BeginCollectionCycle(startedAt time.Time) {
	c.cycleStarts = append(c.cycleStarts, startedAt)
}

func (c *countingCollector) Collect(context.Context) ([]envelope.Item, error) {
	c.calls++
	return []envelope.Item{{
		Kind:    "event",
		Type:    "counted",
		Key:     c.name,
		Payload: map[string]any{"call": c.calls},
	}}, nil
}

type scriptedSender struct {
	errors  []error
	batches []envelope.Batch
}

func (s *scriptedSender) Send(_ context.Context, batch envelope.Batch) error {
	index := len(s.batches)
	s.batches = append(s.batches, batch)
	if index < len(s.errors) {
		return s.errors[index]
	}
	return nil
}

type testDeliveryError struct {
	message   string
	permanent bool
}

func (e *testDeliveryError) Error() string   { return e.message }
func (e *testDeliveryError) Permanent() bool { return e.permanent }

func TestCollectOnceHonorsCollectorCadenceAndStillStartsEveryCycle(t *testing.T) {
	q, err := spool.Open(t.TempDir(), 1024*1024)
	if err != nil {
		t.Fatal(err)
	}
	collector := &countingCollector{name: "slow"}
	a := New(Config{
		AgentID:            "agent-01",
		CollectorIntervals: map[string]time.Duration{"slow": time.Hour},
	}, &fakeSender{}, q, []Collector{collector})

	if err := a.CollectOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	if err := a.CollectOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	if collector.calls != 1 {
		t.Fatalf("collector calls = %d, want 1 inside cadence window", collector.calls)
	}
	if len(collector.cycleStarts) != 2 || collector.cycleStarts[0].IsZero() || collector.cycleStarts[1].IsZero() {
		t.Fatalf("collection cycle notifications = %#v, want two non-zero timestamps", collector.cycleStarts)
	}

	a.lastCollected[collector.Name()] = time.Now().Add(-2 * time.Hour)
	if err := a.CollectOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	if collector.calls != 2 {
		t.Fatalf("collector calls = %d, want 2 after cadence elapsed", collector.calls)
	}

	records, err := q.Peek(10)
	if err != nil {
		t.Fatal(err)
	}
	if len(records) != 3 {
		t.Fatalf("queued batches = %d, want one durable batch per cycle", len(records))
	}
	var idle envelope.Batch
	if err := json.Unmarshal(records[1].Payload, &idle); err != nil {
		t.Fatal(err)
	}
	if len(idle.Items) != 1 || idle.Items[0].Type != "health" || idle.Items[0].Payload.(map[string]any)["scheduler_idle"] != true {
		t.Fatalf("unexpected idle-cycle batch: %#v", idle)
	}
}

func TestCollectOnceRetriesFailedCollectorBeforeNormalCadence(t *testing.T) {
	q, err := spool.Open(t.TempDir(), 1024*1024)
	if err != nil {
		t.Fatal(err)
	}
	collector := &failingCollector{name: "package"}
	a := New(Config{
		AgentID:            "agent-01",
		CollectorIntervals: map[string]time.Duration{"package": 12 * time.Hour},
	}, &fakeSender{}, q, []Collector{collector})

	if err := a.CollectOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	if !a.collectorFailed[collector.Name()] || collector.calls != 1 {
		t.Fatalf("failure state = %v calls = %d", a.collectorFailed[collector.Name()], collector.calls)
	}
	if err := a.CollectOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	if collector.calls != 1 {
		t.Fatalf("collector retried without backoff: calls=%d", collector.calls)
	}

	a.lastCollected[collector.Name()] = time.Now().Add(-collectorFailureRetryInterval - time.Second)
	if err := a.CollectOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	if collector.calls != 2 || a.collectorFailed[collector.Name()] {
		t.Fatalf("collector did not recover after short retry: calls=%d failed=%v", collector.calls, a.collectorFailed[collector.Name()])
	}
}

func TestRunOnceContinuesCollectingWhilePendingDeliveryFails(t *testing.T) {
	q, err := spool.Open(t.TempDir(), 1024*1024)
	if err != nil {
		t.Fatal(err)
	}
	collector := &countingCollector{name: "fast"}
	a := New(Config{AgentID: "agent-01"}, &fakeSender{err: errors.New("offline")}, q, []Collector{collector})

	for cycle := 1; cycle <= 2; cycle++ {
		if err := a.RunOnce(context.Background()); err == nil {
			t.Fatalf("cycle %d returned nil delivery error", cycle)
		}
	}
	if collector.calls != 2 {
		t.Fatalf("collector calls = %d, want 2 despite pending delivery failure", collector.calls)
	}
	stats, err := q.Stats()
	if err != nil {
		t.Fatal(err)
	}
	if stats.Records != 2 {
		t.Fatalf("spool records = %d, want 2 accumulated cycles", stats.Records)
	}
}

func TestChunkBatchPreservesStableBatchAndItemIdentity(t *testing.T) {
	batch := envelope.NewBatch("agent", "agent-01", "default")
	for i := 0; i < 5; i++ {
		batch.Add(envelope.Item{Kind: "event", Type: "test", Key: string(rune('a' + i)), Payload: map[string]any{"n": i}})
	}
	originalIDs := make([]string, len(batch.Items))
	for i := range batch.Items {
		originalIDs[i] = batch.Items[i].ItemID
	}

	chunks, err := chunkBatch(batch, 2, 1024*1024, 512*1024)
	if err != nil {
		t.Fatal(err)
	}
	if len(chunks) != 3 {
		t.Fatalf("chunks = %d, want 3", len(chunks))
	}
	itemIndex := 0
	for chunkIndex, chunk := range chunks {
		if chunk.BatchID != batch.BatchID || chunk.ChunkIndex != chunkIndex || chunk.ChunkCount != len(chunks) {
			t.Fatalf("unexpected chunk identity: %#v", chunk)
		}
		for _, item := range chunk.Items {
			if item.ItemID != originalIDs[itemIndex] || item.Sequence != itemIndex {
				t.Fatalf("item %d identity changed: %#v", itemIndex, item)
			}
			itemIndex++
		}
	}
	if itemIndex != len(batch.Items) {
		t.Fatalf("flattened item count = %d, want %d", itemIndex, len(batch.Items))
	}
}

func TestChunkBatchSplitsOnEncodedByteLimitAndRejectsOversizedItem(t *testing.T) {
	batch := envelope.NewBatch("agent", "agent-01", "default")
	for i := 0; i < 2; i++ {
		batch.Add(envelope.Item{Kind: "event", Type: "test", Key: "same", Payload: map[string]any{"data": strings.Repeat("x", 128)}})
	}
	oneItem := batch.WithItems(batch.Items[:1], 0, 2)
	oneItemEncoded, err := json.Marshal(oneItem)
	if err != nil {
		t.Fatal(err)
	}
	byteLimit := int64(len(oneItemEncoded) + 64)
	chunks, err := chunkBatch(batch, 10, byteLimit, 1024*1024)
	if err != nil {
		t.Fatal(err)
	}
	if len(chunks) != 2 || len(chunks[0].Items) != 1 || len(chunks[1].Items) != 1 {
		t.Fatalf("byte-limited chunks = %#v, want two single-item chunks", chunks)
	}
	for _, chunk := range chunks {
		encoded, err := json.Marshal(chunk)
		if err != nil {
			t.Fatal(err)
		}
		if int64(len(encoded)) > byteLimit {
			t.Fatalf("encoded chunk bytes = %d, limit = %d", len(encoded), byteLimit)
		}
	}

	itemEncoded, err := json.Marshal(batch.Items[0])
	if err != nil {
		t.Fatal(err)
	}
	if _, err := chunkBatch(batch, 10, 1024*1024, int64(len(itemEncoded)-1)); err == nil || !strings.Contains(err.Error(), "max_item_bytes") {
		t.Fatalf("oversized item error = %v", err)
	}
}

func TestFlushPendingDeadLettersPermanentFailureAndContinues(t *testing.T) {
	q, err := spool.Open(t.TempDir(), 1024*1024)
	if err != nil {
		t.Fatal(err)
	}
	_ = pushTestBatch(t, q, "first")
	_ = pushTestBatch(t, q, "second")
	sender := &scriptedSender{errors: []error{&testDeliveryError{message: "bad request", permanent: true}, nil}}
	a := New(Config{AgentID: "agent-01"}, sender, q, nil)

	if err := a.FlushPending(context.Background(), 10); err != nil {
		t.Fatalf("FlushPending returned error: %v", err)
	}
	stats, err := q.Stats()
	if err != nil {
		t.Fatal(err)
	}
	if stats.Records != 0 || stats.DeadLetterRecords != 1 {
		t.Fatalf("unexpected spool stats: %#v", stats)
	}
	if len(sender.batches) != 2 || sender.batches[0].BatchID == sender.batches[1].BatchID {
		t.Fatalf("delivery attempts = %#v", sender.batches)
	}
}

func TestFlushPendingRetainsTransientFailureAndStableIDAcrossRetry(t *testing.T) {
	q, err := spool.Open(t.TempDir(), 1024*1024)
	if err != nil {
		t.Fatal(err)
	}
	_ = pushTestBatch(t, q, "first")
	_ = pushTestBatch(t, q, "second")
	sender := &scriptedSender{errors: []error{errors.New("offline")}}
	a := New(Config{AgentID: "agent-01"}, sender, q, nil)

	if err := a.FlushPending(context.Background(), 10); err == nil {
		t.Fatal("FlushPending returned nil for transient error")
	}
	stats, err := q.Stats()
	if err != nil {
		t.Fatal(err)
	}
	if stats.Records != 2 || stats.DeadLetterRecords != 0 || len(sender.batches) != 1 {
		t.Fatalf("transient failure changed queue unexpectedly: stats=%#v attempts=%d", stats, len(sender.batches))
	}
	firstBatchID := sender.batches[0].BatchID

	if err := a.FlushPending(context.Background(), 10); err != nil {
		t.Fatalf("retry FlushPending returned error: %v", err)
	}
	if len(sender.batches) != 3 || sender.batches[1].BatchID != firstBatchID {
		t.Fatalf("batch identity was not stable across retry: %#v", sender.batches)
	}
	stats, err = q.Stats()
	if err != nil {
		t.Fatal(err)
	}
	if stats.Records != 0 || stats.DeadLetterRecords != 0 {
		t.Fatalf("retry did not drain queue: %#v", stats)
	}
}

func TestFlushPendingDeadLettersInvalidJSON(t *testing.T) {
	q := &rejectingMemoryQueue{records: []spool.Record{{ID: "invalid", Payload: []byte(`{"not":"closed"`)}}}
	a := New(Config{AgentID: "agent-01"}, &fakeSender{}, q, nil)
	if err := a.FlushPending(context.Background(), 10); err != nil {
		t.Fatal(err)
	}
	if len(q.records) != 0 || len(q.rejected) != 1 || !strings.Contains(q.rejected[0], "invalid batch json") {
		t.Fatalf("invalid JSON was not rejected: records=%#v rejected=%#v", q.records, q.rejected)
	}
}

type rejectingMemoryQueue struct {
	records  []spool.Record
	rejected []string
}

func (q *rejectingMemoryQueue) Push(payload []byte) (string, error) {
	q.records = append(q.records, spool.Record{ID: "pushed", Payload: append([]byte(nil), payload...)})
	return "pushed", nil
}

func (q *rejectingMemoryQueue) Peek(limit int) ([]spool.Record, error) {
	if limit > len(q.records) {
		limit = len(q.records)
	}
	return append([]spool.Record(nil), q.records[:limit]...), nil
}

func (q *rejectingMemoryQueue) Ack(ids []string) error {
	for _, id := range ids {
		q.remove(id)
	}
	return nil
}

func (q *rejectingMemoryQueue) Reject(id, reason string) error {
	q.rejected = append(q.rejected, reason)
	q.remove(id)
	return nil
}

func (q *rejectingMemoryQueue) remove(id string) {
	for index, record := range q.records {
		if record.ID == id {
			q.records = append(q.records[:index], q.records[index+1:]...)
			return
		}
	}
}

type persistentStateCollector struct {
	name    string
	items   []envelope.Item
	commits int
}

func (c *persistentStateCollector) Name() string { return c.name }

func (c *persistentStateCollector) Collect(context.Context) ([]envelope.Item, error) {
	return append([]envelope.Item(nil), c.items...), nil
}

func (c *persistentStateCollector) CommitPendingCursor() error {
	c.commits++
	return nil
}

type failNthPushQueue struct {
	failAt    int
	pushCalls int
	records   []spool.Record
	acked     []string
}

func (q *failNthPushQueue) Push(payload []byte) (string, error) {
	q.pushCalls++
	if q.pushCalls == q.failAt {
		return "", errors.New("injected queue push failure")
	}
	id := fmt.Sprintf("record-%d", q.pushCalls)
	q.records = append(q.records, spool.Record{ID: id, Payload: append([]byte(nil), payload...)})
	return id, nil
}

func (q *failNthPushQueue) Peek(limit int) ([]spool.Record, error) {
	if limit > len(q.records) {
		limit = len(q.records)
	}
	return append([]spool.Record(nil), q.records[:limit]...), nil
}

func (q *failNthPushQueue) Ack(ids []string) error {
	q.acked = append(q.acked, ids...)
	remove := make(map[string]struct{}, len(ids))
	for _, id := range ids {
		remove[id] = struct{}{}
	}
	kept := q.records[:0]
	for _, record := range q.records {
		if _, ok := remove[record.ID]; !ok {
			kept = append(kept, record)
		}
	}
	q.records = kept
	return nil
}

func TestCollectOnceRollsBackPartialMultiChunkEnqueueWithoutCommittingState(t *testing.T) {
	statePath := filepath.Join(t.TempDir(), "state", "inventory.json")
	collector := &persistentStateCollector{
		name: "inventory",
		items: []envelope.Item{
			{Kind: "state", Type: "process", Key: "1", Payload: map[string]any{"name": "one"}},
			{Kind: "state", Type: "process", Key: "2", Payload: map[string]any{"name": "two"}},
		},
	}
	queue := &failNthPushQueue{failAt: 2}
	a := New(Config{
		AgentID:                "agent-01",
		CollectorFullIntervals: map[string]time.Duration{"inventory": time.Hour},
		MaxBatchItems:          1,
		MaxBatchBytes:          1024 * 1024,
		MaxItemBytes:           512 * 1024,
		StatePath:              statePath,
	}, &fakeSender{}, queue, []Collector{collector})

	err := a.CollectOnce(context.Background())
	if err == nil || !strings.Contains(err.Error(), "injected queue push failure") {
		t.Fatalf("CollectOnce error = %v", err)
	}
	if queue.pushCalls != 2 || len(queue.acked) != 1 || queue.acked[0] != "record-1" || len(queue.records) != 0 {
		t.Fatalf("partial enqueue was not rolled back: pushes=%d acked=%#v records=%#v", queue.pushCalls, queue.acked, queue.records)
	}
	if collector.commits != 0 {
		t.Fatalf("cursor commits = %d, want 0", collector.commits)
	}
	if len(a.stateCache) != 0 || len(a.lastFull) != 0 || len(a.lastCollected) != 0 || a.stateDirty {
		t.Fatalf("state was committed after partial enqueue: cache=%#v lastFull=%#v lastCollected=%#v dirty=%v", a.stateCache, a.lastFull, a.lastCollected, a.stateDirty)
	}
	if _, err := os.Stat(statePath); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("state file exists after rollback: %v", err)
	}
}

func TestStatePathPersistsHashesAndFullTimestampAcrossRestart(t *testing.T) {
	statePath := filepath.Join(t.TempDir(), "state", "inventory.json")
	config := Config{
		AgentID:                "agent-01",
		CollectorFullIntervals: map[string]time.Duration{"inventory": 24 * time.Hour},
		StatePath:              statePath,
	}
	items := []envelope.Item{
		{Kind: "state", Type: "service", Key: "alpha", Payload: map[string]any{"status": "running"}},
		{Kind: "state", Type: "service", Key: "beta", Payload: map[string]any{"status": "stopped"}},
	}
	firstCollector := &persistentStateCollector{name: "inventory", items: items}
	firstQueue, err := spool.Open(t.TempDir(), 1024*1024)
	if err != nil {
		t.Fatal(err)
	}
	firstAgent := New(config, &fakeSender{}, firstQueue, []Collector{firstCollector})
	if err := firstAgent.CollectOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	firstRecords, err := firstQueue.Peek(10)
	if err != nil {
		t.Fatal(err)
	}
	if len(firstRecords) != 1 {
		t.Fatalf("initial snapshot records = %d, want 1", len(firstRecords))
	}
	var firstBatch envelope.Batch
	if err := json.Unmarshal(firstRecords[0].Payload, &firstBatch); err != nil {
		t.Fatal(err)
	}
	for _, item := range firstBatch.Items {
		payload, ok := item.Payload.(map[string]any)
		if !ok || payload["snapshot_full"] != true || payload["snapshot_item_count"] != float64(2) || !strings.HasSuffix(fmt.Sprint(payload["snapshot_id"]), ":inventory") {
			t.Fatalf("incomplete full-snapshot metadata: %#v", item.Payload)
		}
	}

	encodedState, err := os.ReadFile(statePath)
	if err != nil {
		t.Fatal(err)
	}
	var persisted persistedState
	if err := json.Unmarshal(encodedState, &persisted); err != nil {
		t.Fatalf("state file is not valid JSON: %v", err)
	}
	if persisted.Version != 1 || persisted.LastFull["inventory"].IsZero() || len(persisted.StateCache["inventory"]) != 2 {
		t.Fatalf("unexpected persisted state: %#v", persisted)
	}
	for key, state := range persisted.StateCache["inventory"] {
		if len(state.Hash) != 64 || state.Kind != "state" || state.Type != "service" || state.Key == "" {
			t.Fatalf("invalid persisted state %q: %#v", key, state)
		}
	}
	loadedCache, loadedFull, err := loadPersistentState(statePath)
	if err != nil {
		t.Fatal(err)
	}
	if len(loadedCache["inventory"]) != 2 || !loadedFull["inventory"].Equal(persisted.LastFull["inventory"]) {
		t.Fatalf("persisted state did not round trip: cache=%#v full=%#v", loadedCache, loadedFull)
	}

	secondCollector := &persistentStateCollector{name: "inventory", items: items}
	secondQueue, err := spool.Open(t.TempDir(), 1024*1024)
	if err != nil {
		t.Fatal(err)
	}
	secondAgent := New(config, &fakeSender{}, secondQueue, []Collector{secondCollector})
	if err := secondAgent.CollectOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	records, err := secondQueue.Peek(10)
	if err != nil {
		t.Fatal(err)
	}
	if len(records) != 1 {
		t.Fatalf("queued records after restart = %d, want 1", len(records))
	}
	var batch envelope.Batch
	if err := json.Unmarshal(records[0].Payload, &batch); err != nil {
		t.Fatal(err)
	}
	if len(batch.Items) != 1 || batch.Items[0].Type != "health" {
		t.Fatalf("unchanged state was not suppressed after restart: %#v", batch.Items)
	}
	payload := batch.Items[0].Payload.(map[string]any)
	if payload["scheduler_idle"] != true {
		t.Fatalf("restart batch is not an idle heartbeat: %#v", payload)
	}
}

func TestEmptySuccessfulInventoryEmitsTombstones(t *testing.T) {
	now := time.Now().UTC()
	a := New(Config{
		CollectorFullIntervals: map[string]time.Duration{"port": time.Hour},
	}, &fakeSender{}, &rejectingMemoryQueue{}, nil)
	a.stateCache["port"] = map[string]cachedState{
		"state\x00socket\x00tcp:443": {
			Kind: "state",
			Type: "socket",
			Key:  "tcp:443",
		},
	}
	a.lastFull["port"] = now.Add(-time.Minute)

	items, err := a.filterStateChanges("port", "batch-1", now, nil)
	if err != nil {
		t.Fatal(err)
	}
	if len(items) != 1 || items[0].Type != "socket" || items[0].Key != "tcp:443" {
		t.Fatalf("empty inventory did not emit socket tombstone: %#v", items)
	}
	payload := items[0].Payload.(map[string]any)
	if payload["deleted"] != true || payload["snapshot_item_count"] != 0 || payload["snapshot_full"] != false {
		t.Fatalf("invalid empty inventory tombstone metadata: %#v", payload)
	}
	if len(a.stateCache["port"]) != 0 {
		t.Fatalf("empty successful inventory was not committed: %#v", a.stateCache["port"])
	}
}

func TestEmptyFullInventoryEmitsCompleteSnapshotTombstone(t *testing.T) {
	now := time.Now().UTC()
	a := New(Config{
		CollectorFullIntervals: map[string]time.Duration{"port": time.Hour},
	}, &fakeSender{}, &rejectingMemoryQueue{}, nil)
	a.stateCache["port"] = map[string]cachedState{
		"state\x00socket\x00tcp:443": {Kind: "state", Type: "socket", Key: "tcp:443"},
	}
	a.lastFull["port"] = now.Add(-2 * time.Hour)

	items, err := a.filterStateChanges("port", "batch-2", now, nil)
	if err != nil {
		t.Fatal(err)
	}
	if len(items) != 1 {
		t.Fatalf("full empty inventory items = %d, want 1 tombstone", len(items))
	}
	payload := items[0].Payload.(map[string]any)
	if payload["deleted"] != true || payload["snapshot_item_count"] != 0 || payload["snapshot_full"] != true {
		t.Fatalf("full empty snapshot is not complete: %#v", payload)
	}
}

func TestCorruptStatePathIsReportedThenOverwrittenByValidCollection(t *testing.T) {
	statePath := filepath.Join(t.TempDir(), "state", "inventory.json")
	if err := os.MkdirAll(filepath.Dir(statePath), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(statePath, []byte(`{"version":`), 0o600); err != nil {
		t.Fatal(err)
	}
	collector := &persistentStateCollector{
		name: "inventory",
		items: []envelope.Item{{
			Kind: "state", Type: "package", Key: "agent", Payload: map[string]any{"version": "1.0"},
		}},
	}
	queue, err := spool.Open(t.TempDir(), 1024*1024)
	if err != nil {
		t.Fatal(err)
	}
	a := New(Config{
		AgentID:                "agent-01",
		CollectorFullIntervals: map[string]time.Duration{"inventory": time.Hour},
		StatePath:              statePath,
	}, &fakeSender{}, queue, []Collector{collector})

	before := a.HealthSnapshot()
	if before["state_cache_error"] == nil || !strings.Contains(fmt.Sprint(before["state_cache_error"]), "decode persistent state") {
		t.Fatalf("corrupt state error not surfaced: %#v", before)
	}
	if err := a.CollectOnce(context.Background()); err != nil {
		t.Fatalf("CollectOnce did not recover corrupt state: %v", err)
	}
	after := a.HealthSnapshot()
	if after["state_cache_error"] != nil {
		t.Fatalf("state error not cleared after recovery: %#v", after)
	}
	cache, lastFull, err := loadPersistentState(statePath)
	if err != nil {
		t.Fatalf("recovered state file is invalid: %v", err)
	}
	if len(cache["inventory"]) != 1 || lastFull["inventory"].IsZero() || collector.commits != 1 {
		t.Fatalf("recovered state was not committed: cache=%#v full=%#v commits=%d", cache, lastFull, collector.commits)
	}
}

func pushTestBatch(t *testing.T, q *spool.Queue, key string) string {
	t.Helper()
	batch := envelope.NewBatch("agent", "agent-01", "default")
	batch.Add(envelope.Item{Kind: "event", Type: "test", Key: key, Payload: map[string]any{"ok": true}})
	encoded, err := json.Marshal(batch)
	if err != nil {
		t.Fatal(err)
	}
	id, err := q.Push(encoded)
	if err != nil {
		t.Fatal(err)
	}
	return id
}
