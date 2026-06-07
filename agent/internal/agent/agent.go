package agent

import (
  "context"
  "encoding/json"
  "fmt"

  "castrelyx/agent/internal/envelope"
  "castrelyx/agent/internal/spool"
)

type Config struct {
  AgentID  string
  TenantID string
}

type Collector interface {
  Name() string
  Collect(context.Context) ([]envelope.Item, error)
}

type Sender interface {
  Send(context.Context, envelope.Batch) error
}

type Queue interface {
  Push([]byte) (string, error)
  Peek(int) ([]spool.Record, error)
  Ack([]string) error
}

type Agent struct {
  config     Config
  sender     Sender
  queue      Queue
  collectors []Collector
}

func New(config Config, sender Sender, queue Queue, collectors []Collector) *Agent {
  if config.TenantID == "" {
    config.TenantID = "default"
  }
  return &Agent{
    config:     config,
    sender:     sender,
    queue:      queue,
    collectors: collectors,
  }
}

func (a *Agent) RunOnce(ctx context.Context) error {
  if err := a.FlushPending(ctx, 25); err != nil {
    return err
  }

  batch := envelope.NewBatch("agent", a.config.AgentID, a.config.TenantID)

  for _, c := range a.collectors {
    items, err := c.Collect(ctx)
    if err != nil {
      batch.Add(envelope.Item{
        Kind: "event",
        Type: "collector_error",
        Key:  c.Name(),
        Payload: map[string]any{
          "collector": c.Name(),
          "error":     err.Error(),
        },
      })
      continue
    }
    for _, item := range items {
      batch.Add(item)
    }
  }

  if len(batch.Items) == 0 {
    batch.Add(envelope.Item{
      Kind:    "event",
      Type:    "health",
      Key:     "heartbeat",
      Payload: map[string]any{"status": "ok"},
    })
  }

  if err := a.sender.Send(ctx, batch); err != nil {
    encoded, marshalErr := json.Marshal(batch)
    if marshalErr != nil {
      return fmt.Errorf("send failed: %w; marshal failed: %v", err, marshalErr)
    }
    if _, spoolErr := a.queue.Push(encoded); spoolErr != nil {
      return fmt.Errorf("send failed: %w; spool failed: %v", err, spoolErr)
    }
    return err
  }

  return nil
}

func (a *Agent) FlushPending(ctx context.Context, limit int) error {
  records, err := a.queue.Peek(limit)
  if err != nil {
    return err
  }
  if len(records) == 0 {
    return nil
  }

  acked := make([]string, 0, len(records))
  for _, record := range records {
    var batch envelope.Batch
    if err := json.Unmarshal(record.Payload, &batch); err != nil {
      acked = append(acked, record.ID)
      continue
    }
    if err := a.sender.Send(ctx, batch); err != nil {
      if len(acked) > 0 {
        _ = a.queue.Ack(acked)
      }
      return err
    }
    acked = append(acked, record.ID)
  }
  return a.queue.Ack(acked)
}
