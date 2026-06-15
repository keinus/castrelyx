//go:build !windows

package main

import "context"

func runServiceOrConsole(configPath string, once bool) error {
  return runAgent(context.Background(), configPath, once)
}
