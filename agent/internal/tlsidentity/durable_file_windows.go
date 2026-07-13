//go:build windows

package tlsidentity

import (
	"errors"
	"os"

	"golang.org/x/sys/windows"
)

func replaceDurableFile(temporaryPath, targetPath string) error {
	temporary, err := windows.UTF16PtrFromString(temporaryPath)
	if err != nil {
		return err
	}
	target, err := windows.UTF16PtrFromString(targetPath)
	if err != nil {
		return err
	}
	return windows.MoveFileEx(
		temporary,
		target,
		windows.MOVEFILE_REPLACE_EXISTING|windows.MOVEFILE_WRITE_THROUGH,
	)
}

func syncParentDir(string) error {
	return nil
}

func removeDurableFile(path string) error {
	if _, err := os.Stat(path); errors.Is(err, os.ErrNotExist) {
		return nil
	} else if err != nil {
		return err
	}
	source, err := windows.UTF16PtrFromString(path)
	if err != nil {
		return err
	}
	tombstone, err := windows.UTF16PtrFromString(durableDeleteTombstone(path))
	if err != nil {
		return err
	}
	if err := windows.MoveFileEx(source, tombstone, windows.MOVEFILE_REPLACE_EXISTING|windows.MOVEFILE_WRITE_THROUGH); err != nil {
		if errors.Is(err, windows.ERROR_FILE_NOT_FOUND) || errors.Is(err, windows.ERROR_PATH_NOT_FOUND) {
			return nil
		}
		return err
	}
	return nil
}

func cleanupDurableDelete(path string) error {
	err := os.Remove(durableDeleteTombstone(path))
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	return err
}
