package agent

import (
  "context"
  "encoding/json"
  "errors"
  "testing"

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
