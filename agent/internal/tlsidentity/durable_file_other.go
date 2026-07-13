//go:build !windows

package tlsidentity

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

func removeDurableFile(path string) error {
	err := os.Remove(path)
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		return err
	}
	return syncParentDir(path)
}

func cleanupDurableDelete(string) error {
	return nil
}
