package spool

import (
  "bufio"
  "crypto/rand"
  "encoding/hex"
  "encoding/json"
  "errors"
  "fmt"
  "os"
  "path/filepath"
  "time"
)

const queueFileName = "queue.ndjson"

type Queue struct {
  dir            string
  path           string
  maxRecordBytes int64
}

type Record struct {
  ID        string
  Payload   []byte
  CreatedAt time.Time
}

type diskRecord struct {
  ID        string          `json:"id"`
  Payload   json.RawMessage `json:"payload"`
  CreatedAt time.Time       `json:"created_at"`
}

func Open(dir string, maxRecordBytes int64) (*Queue, error) {
  if maxRecordBytes <= 0 {
    return nil, errors.New("maxRecordBytes must be positive")
  }
  if err := os.MkdirAll(dir, 0o700); err != nil {
    return nil, err
  }
  return &Queue{
    dir:            dir,
    path:           filepath.Join(dir, queueFileName),
    maxRecordBytes: maxRecordBytes,
  }, nil
}

func (q *Queue) Push(payload []byte) (string, error) {
  if len(payload) == 0 {
    return "", errors.New("payload is empty")
  }
  if int64(len(payload)) > q.maxRecordBytes {
    return "", fmt.Errorf("payload size %d exceeds max record size %d", len(payload), q.maxRecordBytes)
  }

  id, err := newID()
  if err != nil {
    return "", err
  }
  rec := diskRecord{
    ID:        id,
    Payload:   append(json.RawMessage(nil), payload...),
    CreatedAt: time.Now().UTC(),
  }
  encoded, err := json.Marshal(rec)
  if err != nil {
    return "", err
  }

  f, err := os.OpenFile(q.path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600)
  if err != nil {
    return "", err
  }
  defer f.Close()
  if _, err := f.Write(append(encoded, '\n')); err != nil {
    return "", err
  }
  return id, nil
}

func (q *Queue) Peek(limit int) ([]Record, error) {
  if limit <= 0 {
    return nil, nil
  }
  f, err := os.Open(q.path)
  if errors.Is(err, os.ErrNotExist) {
    return nil, nil
  }
  if err != nil {
    return nil, err
  }
  defer f.Close()

  records := make([]Record, 0, limit)
  scanner := bufio.NewScanner(f)
  scanner.Buffer(make([]byte, 0, 64*1024), int(q.maxRecordBytes)+1024)
  for scanner.Scan() {
    var rec diskRecord
    if err := json.Unmarshal(scanner.Bytes(), &rec); err != nil {
      continue
    }
    records = append(records, Record{
      ID:        rec.ID,
      Payload:   append([]byte(nil), rec.Payload...),
      CreatedAt: rec.CreatedAt,
    })
    if len(records) >= limit {
      break
    }
  }
  if err := scanner.Err(); err != nil {
    return nil, err
  }
  return records, nil
}

func (q *Queue) Ack(ids []string) error {
  if len(ids) == 0 {
    return nil
  }
  remove := make(map[string]struct{}, len(ids))
  for _, id := range ids {
    remove[id] = struct{}{}
  }

  in, err := os.Open(q.path)
  if errors.Is(err, os.ErrNotExist) {
    return nil
  }
  if err != nil {
    return err
  }
  defer in.Close()

  tmpPath := filepath.Join(q.dir, "queue.tmp")
  out, err := os.OpenFile(tmpPath, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o600)
  if err != nil {
    return err
  }

  scanner := bufio.NewScanner(in)
  scanner.Buffer(make([]byte, 0, 64*1024), int(q.maxRecordBytes)+1024)
  for scanner.Scan() {
    line := append([]byte(nil), scanner.Bytes()...)
    var rec diskRecord
    if err := json.Unmarshal(line, &rec); err != nil {
      continue
    }
    if _, ok := remove[rec.ID]; ok {
      continue
    }
    if _, err := out.Write(append(line, '\n')); err != nil {
      out.Close()
      return err
    }
  }
  if err := scanner.Err(); err != nil {
    out.Close()
    return err
  }
  if err := out.Close(); err != nil {
    return err
  }
  if err := os.Remove(q.path); err != nil && !errors.Is(err, os.ErrNotExist) {
    return err
  }
  return os.Rename(tmpPath, q.path)
}

func newID() (string, error) {
  b := make([]byte, 16)
  if _, err := rand.Read(b); err != nil {
    return "", err
  }
  return fmt.Sprintf("%d-%s", time.Now().UTC().UnixNano(), hex.EncodeToString(b)), nil
}
