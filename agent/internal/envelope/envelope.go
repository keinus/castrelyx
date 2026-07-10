package envelope

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"strings"
	"sync/atomic"
	"time"
)

const (
	SchemaVersion = "1.1"
	RedactedValue = "[REDACTED]"
)

type Batch struct {
	SchemaVersion string    `json:"schema_version"`
	BatchID       string    `json:"batch_id"`
	ChunkIndex    int       `json:"chunk_index"`
	ChunkCount    int       `json:"chunk_count"`
	Source        string    `json:"source"`
	SourceID      string    `json:"source_id"`
	TenantID      string    `json:"tenant_id"`
	ObservedAt    time.Time `json:"observed_at"`
	SentAt        time.Time `json:"sent_at"`
	Items         []Item    `json:"items"`
}

type Item struct {
	ItemID   string `json:"item_id"`
	Sequence int    `json:"sequence"`
	Kind     string `json:"kind"`
	Type     string `json:"type"`
	Key      string `json:"key"`
	Payload  any    `json:"payload"`
}

func NewBatch(source, sourceID, tenantID string) Batch {
	now := time.Now().UTC()
	return Batch{
		SchemaVersion: SchemaVersion,
		BatchID:       newBatchID(),
		ChunkIndex:    0,
		ChunkCount:    1,
		Source:        source,
		SourceID:      sourceID,
		TenantID:      tenantID,
		ObservedAt:    now,
		SentAt:        now,
		Items:         []Item{},
	}
}

func (b *Batch) Add(item Item) {
	item.Sequence = len(b.Items)
	if item.ItemID == "" {
		item.ItemID = fmt.Sprintf("%s:%d", b.BatchID, item.Sequence)
	}
	item.Payload = Redact(item.Payload)
	b.Items = append(b.Items, item)
}

func (b Batch) WithItems(items []Item, chunkIndex, chunkCount int) Batch {
	b.Items = append([]Item(nil), items...)
	b.ChunkIndex = chunkIndex
	b.ChunkCount = chunkCount
	return b
}

var fallbackBatchCounter atomic.Uint64

func newBatchID() string {
	random := make([]byte, 16)
	if _, err := rand.Read(random); err == nil {
		return hex.EncodeToString(random)
	}
	return fmt.Sprintf("%d-%d", time.Now().UTC().UnixNano(), fallbackBatchCounter.Add(1))
}

func Redact(value any) any {
	switch v := value.(type) {
	case map[string]any:
		out := make(map[string]any, len(v))
		for key, child := range v {
			if isSensitiveKey(key) {
				out[key] = RedactedValue
				continue
			}
			out[key] = Redact(child)
		}
		return out
	case []any:
		out := make([]any, len(v))
		for i := range v {
			out[i] = Redact(v[i])
		}
		return out
	default:
		return value
	}
}

func isSensitiveKey(key string) bool {
	normalized := strings.ToLower(strings.ReplaceAll(key, "-", "_"))
	if normalized == "key" || normalized == "api_key" || normalized == "apikey" {
		return true
	}
	sensitive := []string{"token", "password", "secret", "authorization", "credential"}
	for _, needle := range sensitive {
		if strings.Contains(normalized, needle) {
			return true
		}
	}
	return false
}
