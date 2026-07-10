package agent

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"time"

	"castrelyx/agent/internal/envelope"
	"castrelyx/agent/internal/spool"
)

const collectorFailureRetryInterval = time.Minute

// ErrLocalPersistence marks queue/cache/cursor durability failures. They are
// environmental backpressure or storage faults, not evidence that a freshly
// updated binary is unhealthy and should be rolled back.
var ErrLocalPersistence = errors.New("local telemetry persistence failure")

type Config struct {
	AgentID                string
	TenantID               string
	CollectorIntervals     map[string]time.Duration
	CollectorFullIntervals map[string]time.Duration
	MaxBatchItems          int
	MaxBatchBytes          int64
	MaxItemBytes           int64
	StatePath              string
}

type Collector interface {
	Name() string
	Collect(context.Context) ([]envelope.Item, error)
}

type CollectionCycleAware interface {
	BeginCollectionCycle(time.Time)
}

type DurableEnqueueCommitter interface {
	CommitPendingCursor() error
}

type Sender interface {
	Send(context.Context, envelope.Batch) error
}

type Queue interface {
	Push([]byte) (string, error)
	Peek(int) ([]spool.Record, error)
	Ack([]string) error
}

type rejectQueue interface {
	Reject(string, string) error
}

type statsQueue interface {
	Stats() (spool.Stats, error)
}

type permanentError interface {
	Permanent() bool
}

type DeliveryError struct {
	Err error
}

func (e *DeliveryError) Error() string { return "deliver telemetry: " + e.Err.Error() }
func (e *DeliveryError) Unwrap() error { return e.Err }

type CollectorStatus struct {
	LastAttemptAt time.Time     `json:"last_attempt_at"`
	LastSuccessAt time.Time     `json:"last_success_at,omitempty"`
	Duration      time.Duration `json:"duration"`
	Items         int           `json:"items"`
	LastError     string        `json:"last_error,omitempty"`
}

type Agent struct {
	config          Config
	sender          Sender
	queue           Queue
	collectors      []Collector
	lastCollected   map[string]time.Time
	collectorFailed map[string]bool
	lastFull        map[string]time.Time
	stateCache      map[string]map[string]cachedState
	stateDirty      bool
	stateError      string

	mu                  sync.RWMutex
	collectorStatus     map[string]CollectorStatus
	lastDeliveryAt      time.Time
	lastDeliverySuccess time.Time
	lastDeliveryError   string
}

type cachedState struct {
	Kind string
	Type string
	Key  string
	Hash [sha256.Size]byte
}

type persistedState struct {
	Version    int                                        `json:"version"`
	LastFull   map[string]time.Time                       `json:"last_full"`
	StateCache map[string]map[string]persistedCachedState `json:"state_cache"`
}

type persistedCachedState struct {
	Kind string `json:"kind"`
	Type string `json:"type"`
	Key  string `json:"key"`
	Hash string `json:"hash"`
}

func New(config Config, sender Sender, queue Queue, collectors []Collector) *Agent {
	if config.TenantID == "" {
		config.TenantID = "default"
	}
	if config.MaxBatchItems <= 0 {
		config.MaxBatchItems = 1_000
	}
	if config.MaxBatchBytes <= 0 {
		config.MaxBatchBytes = 4 * 1024 * 1024
	}
	if config.MaxItemBytes <= 0 {
		config.MaxItemBytes = 512 * 1024
	}
	agent := &Agent{
		config:          config,
		sender:          sender,
		queue:           queue,
		collectors:      collectors,
		lastCollected:   map[string]time.Time{},
		collectorFailed: map[string]bool{},
		lastFull:        map[string]time.Time{},
		stateCache:      map[string]map[string]cachedState{},
		collectorStatus: map[string]CollectorStatus{},
	}
	if config.StatePath != "" {
		if stateCache, lastFull, err := loadPersistentState(config.StatePath); err == nil {
			agent.stateCache = stateCache
			agent.lastFull = lastFull
		} else if !errors.Is(err, os.ErrNotExist) {
			agent.stateError = err.Error()
		}
	}
	return agent
}

// RunOnce is retained for one-shot operation and tests. Long-running agents use
// CollectOnce and FlushPending from independent loops so delivery stalls never
// stop host observation.
func (a *Agent) RunOnce(ctx context.Context) error {
	if err := a.CollectOnce(ctx); err != nil {
		return err
	}
	if err := a.FlushPending(ctx, 25); err != nil {
		return &DeliveryError{Err: err}
	}
	return nil
}

func (a *Agent) CollectOnce(ctx context.Context) error {
	cacheBefore := cloneStateCache(a.stateCache)
	fullBefore := cloneTimes(a.lastFull)
	collectedBefore := cloneTimes(a.lastCollected)
	failedBefore := cloneBools(a.collectorFailed)
	dirtyBefore := a.stateDirty
	stateCommitted := false
	defer func() {
		if !stateCommitted {
			a.stateCache = cacheBefore
			a.lastFull = fullBefore
			a.lastCollected = collectedBefore
			a.collectorFailed = failedBefore
			a.stateDirty = dirtyBefore
		}
	}()
	batch := envelope.NewBatch("agent", a.config.AgentID, a.config.TenantID)
	now := time.Now().UTC()
	for _, collector := range a.collectors {
		if aware, ok := collector.(CollectionCycleAware); ok {
			aware.BeginCollectionCycle(now)
		}
	}

	for _, collector := range a.collectors {
		if !a.collectorDue(collector.Name(), now) {
			continue
		}
		startedAt := time.Now()
		items, err := collector.Collect(ctx)
		duration := time.Since(startedAt)
		a.recordCollector(collector.Name(), now, duration, len(items), err)
		a.lastCollected[collector.Name()] = now
		if err != nil {
			a.collectorFailed[collector.Name()] = true
			batch.Add(envelope.Item{
				Kind: "event",
				Type: "collector_error",
				Key:  collector.Name(),
				Payload: map[string]any{
					"collector":   collector.Name(),
					"error":       err.Error(),
					"duration_ms": duration.Milliseconds(),
				},
			})
			continue
		}
		delete(a.collectorFailed, collector.Name())
		items, err = a.filterStateChanges(collector.Name(), batch.BatchID, now, items)
		if err != nil {
			return fmt.Errorf("filter %s state changes: %w", collector.Name(), err)
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
			Payload: map[string]any{"status": "ok", "scheduler_idle": true},
		})
	}

	chunks, err := chunkBatch(batch, a.config.MaxBatchItems, a.config.MaxBatchBytes, a.config.MaxItemBytes)
	if err != nil {
		return err
	}
	pushedIDs := make([]string, 0, len(chunks))
	rollbackPushed := func(cause error) error {
		if len(pushedIDs) == 0 {
			return cause
		}
		if err := a.queue.Ack(pushedIDs); err != nil {
			return errors.Join(cause, ErrLocalPersistence, fmt.Errorf("rollback partially enqueued batch: %w", err))
		}
		return cause
	}
	for _, chunk := range chunks {
		encoded, err := json.Marshal(chunk)
		if err != nil {
			return rollbackPushed(fmt.Errorf("marshal batch: %w", err))
		}
		id, err := a.queue.Push(encoded)
		if err != nil {
			return rollbackPushed(errors.Join(
				ErrLocalPersistence,
				fmt.Errorf("enqueue batch %s/%d: %w", chunk.BatchID, chunk.ChunkIndex, err),
			))
		}
		pushedIDs = append(pushedIDs, id)
	}
	if a.stateDirty && a.config.StatePath != "" {
		if err := savePersistentState(a.config.StatePath, a.stateCache, a.lastFull); err != nil {
			a.setStateError(err)
			return rollbackPushed(errors.Join(
				ErrLocalPersistence,
				fmt.Errorf("persist state cache after durable enqueue: %w", err),
			))
		}
		a.stateDirty = false
		a.setStateError(nil)
	}
	// Inventory state is committed once the complete batch and its persistent
	// cache are durable. A later log-cursor failure must not roll it back and
	// cause unrelated inventory duplicates.
	stateCommitted = true
	for _, collector := range a.collectors {
		if committer, ok := collector.(DurableEnqueueCommitter); ok {
			if err := committer.CommitPendingCursor(); err != nil {
				return errors.Join(
					ErrLocalPersistence,
					fmt.Errorf("commit %s cursor after durable enqueue: %w", collector.Name(), err),
				)
			}
		}
	}
	return nil
}

func (a *Agent) filterStateChanges(collectorName, snapshotID string, now time.Time, items []envelope.Item) ([]envelope.Item, error) {
	fullInterval := a.config.CollectorFullIntervals[collectorName]
	if fullInterval <= 0 {
		return items, nil
	}
	previous := a.stateCache[collectorName]
	current := make(map[string]cachedState)
	stateItems := make(map[string]envelope.Item)
	passthrough := make([]envelope.Item, 0, len(items))
	for _, item := range items {
		if item.Kind != "state" && item.Kind != "asset" {
			passthrough = append(passthrough, item)
			continue
		}
		key := item.Kind + "\x00" + item.Type + "\x00" + item.Key
		encoded, err := json.Marshal(item.Payload)
		if err != nil {
			return nil, err
		}
		current[key] = cachedState{Kind: item.Kind, Type: item.Type, Key: item.Key, Hash: sha256.Sum256(encoded)}
		stateItems[key] = item
	}
	full := len(previous) == 0 || a.lastFull[collectorName].IsZero() || now.Sub(a.lastFull[collectorName]) >= fullInterval
	collectorSnapshotID := snapshotID + ":" + collectorName
	snapshotItemCount := len(current)
	filtered := append([]envelope.Item(nil), passthrough...)
	for key, state := range current {
		old, existed := previous[key]
		if !full && existed && old.Hash == state.Hash {
			continue
		}
		item := stateItems[key]
		item.Payload = withSnapshotMetadata(item.Payload, collectorSnapshotID, snapshotItemCount, full, false)
		filtered = append(filtered, item)
	}
	for key, old := range previous {
		if _, exists := current[key]; exists {
			continue
		}
		filtered = append(filtered, envelope.Item{
			Kind: old.Kind,
			Type: old.Type,
			Key:  old.Key,
			Payload: map[string]any{
				"deleted":             true,
				"snapshot_id":         collectorSnapshotID,
				"snapshot_item_count": snapshotItemCount,
				"snapshot_full":       full,
				"observed_at":         now.Format(time.RFC3339Nano),
			},
		})
	}
	if full || !cachedStateMapsEqual(previous, current) {
		a.stateDirty = true
	}
	a.stateCache[collectorName] = current
	if full {
		a.lastFull[collectorName] = now
	}
	return filtered, nil
}

func withSnapshotMetadata(payload any, snapshotID string, itemCount int, full, deleted bool) map[string]any {
	result := map[string]any{}
	if values, ok := payload.(map[string]any); ok {
		for key, value := range values {
			result[key] = value
		}
	} else if payload != nil {
		result["value"] = payload
	}
	result["snapshot_id"] = snapshotID
	result["snapshot_item_count"] = itemCount
	result["snapshot_full"] = full
	if deleted {
		result["deleted"] = true
	}
	return result
}

func cloneStateCache(source map[string]map[string]cachedState) map[string]map[string]cachedState {
	clone := make(map[string]map[string]cachedState, len(source))
	for collector, states := range source {
		entries := make(map[string]cachedState, len(states))
		for key, state := range states {
			entries[key] = state
		}
		clone[collector] = entries
	}
	return clone
}

func cloneTimes(source map[string]time.Time) map[string]time.Time {
	clone := make(map[string]time.Time, len(source))
	for key, value := range source {
		clone[key] = value
	}
	return clone
}

func cloneBools(source map[string]bool) map[string]bool {
	clone := make(map[string]bool, len(source))
	for key, value := range source {
		clone[key] = value
	}
	return clone
}

func (a *Agent) FlushPending(ctx context.Context, limit int) error {
	records, err := a.queue.Peek(limit)
	if err != nil {
		return err
	}
	for _, record := range records {
		var batch envelope.Batch
		if err := json.Unmarshal(record.Payload, &batch); err != nil {
			if rejector, ok := a.queue.(rejectQueue); ok {
				if rejectErr := rejector.Reject(record.ID, "invalid batch json: "+err.Error()); rejectErr != nil {
					return rejectErr
				}
				continue
			}
			return fmt.Errorf("invalid queued batch %s: %w", record.ID, err)
		}
		a.recordDeliveryAttempt()
		if err := a.sender.Send(ctx, batch); err != nil {
			a.recordDeliveryError(err)
			var permanent permanentError
			if errors.As(err, &permanent) && permanent.Permanent() {
				if rejector, ok := a.queue.(rejectQueue); ok {
					if rejectErr := rejector.Reject(record.ID, err.Error()); rejectErr != nil {
						return rejectErr
					}
					continue
				}
			}
			return err
		}
		if err := a.queue.Ack([]string{record.ID}); err != nil {
			return err
		}
		a.recordDeliverySuccess()
	}
	return nil
}

func (a *Agent) HealthSnapshot() map[string]any {
	a.mu.RLock()
	collectors := make(map[string]CollectorStatus, len(a.collectorStatus))
	for name, status := range a.collectorStatus {
		collectors[name] = status
	}
	snapshot := map[string]any{
		"collectors":            collectors,
		"last_delivery_at":      nullableTime(a.lastDeliveryAt),
		"last_delivery_success": nullableTime(a.lastDeliverySuccess),
		"last_delivery_error":   nullableString(a.lastDeliveryError),
		"state_cache_error":     nullableString(a.stateError),
	}
	a.mu.RUnlock()
	if queue, ok := a.queue.(statsQueue); ok {
		if stats, err := queue.Stats(); err == nil {
			snapshot["spool_records"] = stats.Records
			snapshot["spool_bytes"] = stats.Bytes
			snapshot["spool_dead_letters"] = stats.DeadLetterRecords
			snapshot["spool_dead_letter_bytes"] = stats.DeadLetterBytes
			snapshot["spool_total_records"] = stats.TotalRecords
			snapshot["spool_total_bytes"] = stats.TotalBytes
			if !stats.OldestAt.IsZero() {
				snapshot["spool_oldest_age_seconds"] = time.Since(stats.OldestAt).Seconds()
			} else {
				snapshot["spool_oldest_age_seconds"] = 0
			}
		}
	}
	return snapshot
}

// StartupCollectionHealthy requires every enabled foundational collector to
// have completed its latest attempt successfully. Collector errors are valid
// telemetry, but an error-only cycle must not approve a freshly updated binary.
func (a *Agent) StartupCollectionHealthy() bool {
	a.mu.RLock()
	defer a.mu.RUnlock()

	criticalPresent := false
	for _, collector := range a.collectors {
		name := collector.Name()
		if name != "identity" && name != "metric" {
			continue
		}
		criticalPresent = true
		status, ok := a.collectorStatus[name]
		if !ok || status.LastSuccessAt.IsZero() || status.LastError != "" {
			return false
		}
	}
	if criticalPresent {
		return true
	}
	for _, status := range a.collectorStatus {
		if !status.LastSuccessAt.IsZero() && status.LastError == "" {
			return true
		}
	}
	return false
}

func cachedStateMapsEqual(left, right map[string]cachedState) bool {
	if len(left) != len(right) {
		return false
	}
	for key, leftState := range left {
		if rightState, ok := right[key]; !ok || leftState != rightState {
			return false
		}
	}
	return true
}

func loadPersistentState(path string) (map[string]map[string]cachedState, map[string]time.Time, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, nil, err
	}
	defer file.Close()
	decoder := json.NewDecoder(&io.LimitedReader{R: file, N: 64*1024*1024 + 1})
	var state persistedState
	if err := decoder.Decode(&state); err != nil {
		return nil, nil, fmt.Errorf("decode persistent state: %w", err)
	}
	if state.Version != 1 {
		return nil, nil, fmt.Errorf("unsupported persistent state version %d", state.Version)
	}
	cache := make(map[string]map[string]cachedState, len(state.StateCache))
	for collector, entries := range state.StateCache {
		converted := make(map[string]cachedState, len(entries))
		for key, entry := range entries {
			hash, err := hex.DecodeString(entry.Hash)
			if err != nil || len(hash) != sha256.Size {
				return nil, nil, fmt.Errorf("invalid state hash for %s/%s", collector, key)
			}
			var hashArray [sha256.Size]byte
			copy(hashArray[:], hash)
			converted[key] = cachedState{Kind: entry.Kind, Type: entry.Type, Key: entry.Key, Hash: hashArray}
		}
		cache[collector] = converted
	}
	if state.LastFull == nil {
		state.LastFull = map[string]time.Time{}
	}
	return cache, state.LastFull, nil
}

func savePersistentState(path string, cache map[string]map[string]cachedState, lastFull map[string]time.Time) error {
	persistedCache := make(map[string]map[string]persistedCachedState, len(cache))
	for collector, entries := range cache {
		converted := make(map[string]persistedCachedState, len(entries))
		for key, entry := range entries {
			converted[key] = persistedCachedState{
				Kind: entry.Kind,
				Type: entry.Type,
				Key:  entry.Key,
				Hash: hex.EncodeToString(entry.Hash[:]),
			}
		}
		persistedCache[collector] = converted
	}
	payload, err := json.Marshal(persistedState{Version: 1, LastFull: lastFull, StateCache: persistedCache})
	if err != nil {
		return err
	}
	if len(payload) > 64*1024*1024 {
		return errors.New("persistent state exceeds 64 MiB")
	}
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	temp, err := os.CreateTemp(dir, ".state-cache-*.tmp")
	if err != nil {
		return err
	}
	tempPath := temp.Name()
	cleanup := func() { _ = os.Remove(tempPath) }
	if err := temp.Chmod(0o600); err != nil {
		_ = temp.Close()
		cleanup()
		return err
	}
	if _, err := temp.Write(payload); err != nil {
		_ = temp.Close()
		cleanup()
		return err
	}
	if err := temp.Sync(); err != nil {
		_ = temp.Close()
		cleanup()
		return err
	}
	if err := temp.Close(); err != nil {
		cleanup()
		return err
	}
	if err := os.Rename(tempPath, path); err != nil {
		cleanup()
		return err
	}
	if runtime.GOOS != "windows" {
		directory, err := os.Open(dir)
		if err != nil {
			return err
		}
		err = directory.Sync()
		_ = directory.Close()
		if err != nil {
			return err
		}
	}
	return nil
}

func (a *Agent) collectorDue(name string, now time.Time) bool {
	last := a.lastCollected[name]
	if last.IsZero() {
		return true
	}
	interval := a.config.CollectorIntervals[name]
	if a.collectorFailed[name] && (interval <= 0 || interval > collectorFailureRetryInterval) {
		interval = collectorFailureRetryInterval
	}
	return interval <= 0 || now.Sub(last) >= interval
}

func (a *Agent) recordCollector(name string, attemptedAt time.Time, duration time.Duration, items int, err error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	status := CollectorStatus{LastAttemptAt: attemptedAt, Duration: duration, Items: items}
	if err == nil {
		status.LastSuccessAt = attemptedAt
	} else {
		status.LastError = err.Error()
		status.LastSuccessAt = a.collectorStatus[name].LastSuccessAt
	}
	a.collectorStatus[name] = status
}

func (a *Agent) recordDeliveryAttempt() {
	a.mu.Lock()
	a.lastDeliveryAt = time.Now().UTC()
	a.mu.Unlock()
}

func (a *Agent) recordDeliveryError(err error) {
	a.mu.Lock()
	a.lastDeliveryError = err.Error()
	a.mu.Unlock()
}

func (a *Agent) recordDeliverySuccess() {
	a.mu.Lock()
	a.lastDeliverySuccess = time.Now().UTC()
	a.lastDeliveryError = ""
	a.mu.Unlock()
}

func (a *Agent) setStateError(err error) {
	a.mu.Lock()
	if err == nil {
		a.stateError = ""
	} else {
		a.stateError = err.Error()
	}
	a.mu.Unlock()
}

func chunkBatch(batch envelope.Batch, maxItems int, maxBytes, maxItemBytes int64) ([]envelope.Batch, error) {
	// Account with the widest possible chunk metadata so the final chunk index/count
	// can never make an otherwise accepted batch cross the byte limit.
	worstChunkCount := max(1, len(batch.Items))
	empty := batch.WithItems(nil, worstChunkCount-1, worstChunkCount)
	emptyEncoded, err := json.Marshal(empty)
	if err != nil {
		return nil, err
	}
	// The encoded empty slice contributes two bytes ("[]"). Replace those bytes
	// with the encoded item list as items are accumulated below.
	baseBytes := int64(len(emptyEncoded) - len("[]"))
	if baseBytes > maxBytes {
		return nil, errors.New("batch envelope exceeds max_batch_bytes")
	}

	chunks := make([][]envelope.Item, 0, 1)
	current := make([]envelope.Item, 0, min(maxItems, len(batch.Items)))
	currentBytes := baseBytes
	for _, item := range batch.Items {
		encoded, err := json.Marshal(item)
		if err != nil {
			return nil, fmt.Errorf("marshal item %s: %w", item.ItemID, err)
		}
		itemBytes := int64(len(encoded))
		if itemBytes > maxItemBytes {
			return nil, fmt.Errorf("item %s size %d exceeds max_item_bytes %d", item.ItemID, itemBytes, maxItemBytes)
		}
		extra := itemBytes
		if len(current) > 0 {
			extra++
		}
		if len(current) >= maxItems || currentBytes+extra > maxBytes {
			if len(current) == 0 {
				return nil, fmt.Errorf("item %s cannot fit in an empty batch", item.ItemID)
			}
			chunks = append(chunks, current)
			current = make([]envelope.Item, 0, min(maxItems, len(batch.Items)))
			currentBytes = baseBytes
			extra = itemBytes
		}
		current = append(current, item)
		currentBytes += extra
	}
	if len(current) > 0 {
		chunks = append(chunks, current)
	}

	result := make([]envelope.Batch, 0, len(chunks))
	for index, items := range chunks {
		chunk := batch.WithItems(items, index, len(chunks))
		encoded, err := json.Marshal(chunk)
		if err != nil {
			return nil, err
		}
		if int64(len(encoded)) > maxBytes {
			return nil, fmt.Errorf("chunk %d exceeds max_batch_bytes after encoding", index)
		}
		result = append(result, chunk)
	}
	return result, nil
}

func nullableTime(value time.Time) any {
	if value.IsZero() {
		return nil
	}
	return value
}

func nullableString(value string) any {
	if value == "" {
		return nil
	}
	return value
}
