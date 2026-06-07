package collectors

import "testing"

func TestBuildReturnsCollectorsInConfigOrder(t *testing.T) {
  got, err := Build([]string{"identity", "metric", "network"})
  if err != nil {
    t.Fatalf("Build returned error: %v", err)
  }
  names := []string{got[0].Name(), got[1].Name(), got[2].Name()}
  want := []string{"identity", "metric", "network"}
  for i := range want {
    if names[i] != want[i] {
      t.Fatalf("collector %d = %q, want %q", i, names[i], want[i])
    }
  }
}

func TestBuildRejectsUnknownCollector(t *testing.T) {
  if _, err := Build([]string{"identity", "unknown"}); err == nil {
    t.Fatal("Build returned nil error for unknown collector")
  }
}
