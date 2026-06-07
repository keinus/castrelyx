package spool

import (
  "testing"
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

func TestQueueRejectsRecordsPastLimit(t *testing.T) {
  queue, err := Open(t.TempDir(), 4)
  if err != nil {
    t.Fatal(err)
  }

  if _, err := queue.Push([]byte(`{"too":"large"}`)); err == nil {
    t.Fatal("Push accepted a record larger than the spool limit")
  }
}
