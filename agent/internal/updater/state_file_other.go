//go:build !windows

package updater

import (
	"os"
	"path/filepath"
)

func replaceDurableFile(temporaryPath, targetPath string) error {
	if err := os.Rename(temporaryPath, targetPath); err != nil {
		return err
	}
	return syncParentDir(targetPath)
}

func syncParentDir(path string) error {
	directory, err := os.Open(filepath.Dir(path))
	if err != nil {
		return err
	}
	defer directory.Close()
	return directory.Sync()
}
