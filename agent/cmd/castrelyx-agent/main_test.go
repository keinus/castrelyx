package main

import (
	"context"
	"errors"
	"strings"
	"sync"
	"testing"
	"time"

	"castrelyx/agent/internal/agent"
	"castrelyx/agent/internal/updater"
)

type fakeUpdateLoopClient struct {
	mu          sync.Mutex
	pending     bool
	markOnce    sync.Once
	markCalled  chan struct{}
	checkCalled chan struct{}
	markErr     error
}

func (f *fakeUpdateLoopClient) CheckAndApply(context.Context) error {
	f.checkCalled <- struct{}{}
	return nil
}

func (f *fakeUpdateLoopClient) MarkApplied(context.Context) error {
	f.mu.Lock()
	f.pending = false
	f.mu.Unlock()
	f.markOnce.Do(func() { close(f.markCalled) })
	return f.markErr
}

func TestRunUpdaterLoopPropagatesRestartRequiredFromMarkApplied(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	client := &fakeUpdateLoopClient{
		pending:     true,
		markCalled:  make(chan struct{}),
		checkCalled: make(chan struct{}, 1),
		markErr:     updater.ErrRestartRequired,
	}
	collectionSucceeded := make(chan struct{}, 1)
	restartRequired := make(chan string, 1)
	done := make(chan struct{})
	go func() {
		runUpdaterLoop(ctx, client, time.Hour, collectionSucceeded, restartRequired, true)
		close(done)
	}()

	collectionSucceeded <- struct{}{}
	select {
	case reason := <-restartRequired:
		if !strings.Contains(reason, "replacement resumed") {
			t.Fatalf("restart reason = %q", reason)
		}
	case <-time.After(time.Second):
		t.Fatal("restart-required MarkApplied result was not propagated")
	}
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("updater loop did not stop after restart request")
	}
}

func (f *fakeUpdateLoopClient) HasPendingApply() bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.pending
}

func TestRunUpdaterLoopDefersChecksUntilStartupApplyIsMarked(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	client := &fakeUpdateLoopClient{
		pending:     true,
		markCalled:  make(chan struct{}),
		checkCalled: make(chan struct{}, 1),
	}
	collectionSucceeded := make(chan struct{}, 1)
	restartRequired := make(chan string, 1)
	done := make(chan struct{})
	go func() {
		runUpdaterLoop(ctx, client, time.Hour, collectionSucceeded, restartRequired, true)
		close(done)
	}()

	select {
	case <-client.checkCalled:
		t.Fatal("update check ran while startup apply was still pending")
	case <-time.After(150 * time.Millisecond):
	}
	collectionSucceeded <- struct{}{}
	select {
	case <-client.markCalled:
	case <-time.After(time.Second):
		t.Fatal("startup apply was not marked after successful collection")
	}
	select {
	case <-client.checkCalled:
	case <-time.After(2 * time.Second):
		t.Fatal("update checks did not resume after startup apply was marked")
	}

	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("updater loop did not stop")
	}
}

func TestPostUpdateRollbackIgnoresLocalPersistenceBackpressure(t *testing.T) {
	localFailure := errors.Join(agent.ErrLocalPersistence, errors.New("spool full"))
	if shouldRollbackPostUpdateCollection(localFailure) {
		t.Fatal("local spool/cache/cursor failure triggered update rollback")
	}
	if !shouldRollbackPostUpdateCollection(errors.New("batch serialization invariant failed")) {
		t.Fatal("non-persistence health failure did not trigger update rollback")
	}
}
