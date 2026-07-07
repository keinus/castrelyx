package remotetasks

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"os/exec"
	"os/user"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"time"
)

const castrelyxKeyMarker = "castrelyx-session="

type sshTaskPayload struct {
	Username       string `json:"username"`
	PublicKey      string `json:"publicKey"`
	PublicKeyAlt   string `json:"public_key"`
	SessionID      string `json:"sessionId"`
	SessionIDAlt   string `json:"session_id"`
	ExpiresAt      string `json:"expiresAt"`
	ExpiresAtAlt   string `json:"expires_at"`
	AllowedFrom    string `json:"allowedFrom"`
	AllowedFromAlt string `json:"allowed_from"`
}

func Run(ctx context.Context, taskType string, payloadJSON []byte) (map[string]any, error) {
	switch taskType {
	case "ssh_preflight":
		payload, _ := parseSSHPayload(payloadJSON)
		return sshPreflight(ctx, payload), nil
	case "ssh_authorize_key":
		payload, err := parseSSHPayload(payloadJSON)
		if err != nil {
			return nil, err
		}
		return sshAuthorizeKey(payload)
	case "ssh_revoke_key":
		payload, err := parseSSHPayload(payloadJSON)
		if err != nil {
			return nil, err
		}
		return sshRevokeKey(payload)
	default:
		return nil, fmt.Errorf("unsupported remote task type %q", taskType)
	}
}

func parseSSHPayload(data []byte) (sshTaskPayload, error) {
	var payload sshTaskPayload
	if len(bytes.TrimSpace(data)) == 0 {
		return payload, nil
	}
	if err := json.Unmarshal(data, &payload); err != nil {
		return payload, err
	}
	return payload, nil
}

func sshPreflight(ctx context.Context, payload sshTaskPayload) map[string]any {
	username := firstNonBlank(payload.Username, currentUsername())
	var usr *user.User
	var lookupErr error
	if runtime.GOOS == "windows" {
		usr, lookupErr = lookupWindowsUser(username)
	} else {
		usr, lookupErr = user.Lookup(username)
	}
	ports := listeningSSHPorts(ctx)
	activeService := firstActiveService(ctx, "sshd", "ssh")
	installedPath := sshdPath()
	supported := runtime.GOOS == "linux" || runtime.GOOS == "windows"
	result := map[string]any{
		"os":                    runtime.GOOS,
		"supported":             supported,
		"sshdInstalled":         installedPath != "",
		"sshdPath":              installedPath,
		"serviceActive":         activeService != "",
		"activeServiceName":     activeService,
		"listeningPorts":        ports,
		"publicKeyAuthExpected": true,
		"configChanged":         false,
	}
	if lookupErr == nil {
		result["username"] = usr.Username
		result["homeDir"] = usr.HomeDir
		result["authorizedKeysPath"] = authorizedKeysPathForUser(usr)
		result["userExists"] = true
	} else {
		result["username"] = username
		result["userExists"] = false
		result["userError"] = lookupErr.Error()
	}
	if !supported {
		result["message"] = "remote SSH key management is supported for Linux and Windows OpenSSH targets in this agent build"
	} else if installedPath != "" || activeService != "" {
		result["message"] = "existing OpenSSH installation was detected; Castrelyx will only manage temporary authorized_keys entries"
	}
	return result
}

func sshAuthorizeKey(payload sshTaskPayload) (map[string]any, error) {
	switch runtime.GOOS {
	case "linux":
		return sshAuthorizeKeyLinux(payload)
	case "windows":
		return sshAuthorizeKeyWindows(payload)
	default:
		return nil, errors.New("ssh_authorize_key supports Linux and Windows OpenSSH targets")
	}
}

func sshAuthorizeKeyLinux(payload sshTaskPayload) (map[string]any, error) {
	username := firstNonBlank(payload.Username, currentUsername())
	sessionID := firstNonBlank(payload.SessionID, payload.SessionIDAlt)
	publicKey := firstNonBlank(payload.PublicKey, payload.PublicKeyAlt)
	allowedFrom := firstNonBlank(payload.AllowedFrom, payload.AllowedFromAlt)
	expiresAtText := firstNonBlank(payload.ExpiresAt, payload.ExpiresAtAlt)
	if sessionID == "" {
		return nil, errors.New("session id is required")
	}
	if err := validatePublicKey(publicKey); err != nil {
		return nil, err
	}
	expiresAt := time.Now().UTC().Add(10 * time.Minute)
	if expiresAtText != "" {
		parsed, err := time.Parse(time.RFC3339, expiresAtText)
		if err != nil {
			return nil, fmt.Errorf("invalid expires_at: %w", err)
		}
		expiresAt = parsed.UTC()
	}
	if time.Now().After(expiresAt) {
		return nil, errors.New("ssh key expiration is already in the past")
	}

	authorizedKeysPath, uid, gid, err := authorizedKeysTarget(username)
	if err != nil {
		return nil, err
	}
	if err := ensureAuthorizedKeysFile(authorizedKeysPath, uid, gid); err != nil {
		return nil, err
	}
	removedExpired, err := cleanupExpiredKeys(authorizedKeysPath)
	if err != nil {
		return nil, err
	}
	line := authorizedKeyLine(publicKey, sessionID, expiresAt, allowedFrom)
	added, err := upsertSessionKey(authorizedKeysPath, sessionID, line)
	if err != nil {
		return nil, err
	}
	if err := setOwnerAndMode(authorizedKeysPath, uid, gid, 0o600); err != nil {
		return nil, err
	}
	return map[string]any{
		"username":           username,
		"authorizedKeysPath": authorizedKeysPath,
		"sessionId":          sessionID,
		"expiresAt":          expiresAt.Format(time.RFC3339),
		"added":              added,
		"removedExpired":     removedExpired,
		"configChanged":      false,
	}, nil
}

func sshRevokeKey(payload sshTaskPayload) (map[string]any, error) {
	switch runtime.GOOS {
	case "linux":
		return sshRevokeKeyLinux(payload)
	case "windows":
		return sshRevokeKeyWindows(payload)
	default:
		return nil, errors.New("ssh_revoke_key supports Linux and Windows OpenSSH targets")
	}
}

func sshRevokeKeyLinux(payload sshTaskPayload) (map[string]any, error) {
	username := firstNonBlank(payload.Username, currentUsername())
	sessionID := firstNonBlank(payload.SessionID, payload.SessionIDAlt)
	if sessionID == "" {
		return nil, errors.New("session id is required")
	}
	authorizedKeysPath, uid, gid, err := authorizedKeysTarget(username)
	if err != nil {
		return nil, err
	}
	removed, err := removeSessionKey(authorizedKeysPath, sessionID)
	if err != nil {
		return nil, err
	}
	if err := setOwnerAndMode(authorizedKeysPath, uid, gid, 0o600); err != nil {
		return nil, err
	}
	return map[string]any{
		"username":           username,
		"authorizedKeysPath": authorizedKeysPath,
		"sessionId":          sessionID,
		"removed":            removed,
	}, nil
}

func authorizedKeysTarget(username string) (string, int, int, error) {
	usr, err := user.Lookup(username)
	if err != nil {
		return "", 0, 0, err
	}
	uid, err := strconv.Atoi(usr.Uid)
	if err != nil {
		return "", 0, 0, fmt.Errorf("parse uid: %w", err)
	}
	gid, err := strconv.Atoi(usr.Gid)
	if err != nil {
		return "", 0, 0, fmt.Errorf("parse gid: %w", err)
	}
	if usr.HomeDir == "" {
		return "", 0, 0, errors.New("target user has no home directory")
	}
	return filepath.Join(usr.HomeDir, ".ssh", "authorized_keys"), uid, gid, nil
}

func authorizedKeysPathForUser(usr *user.User) string {
	if usr == nil {
		return ""
	}
	homeDir := usr.HomeDir
	if runtime.GOOS == "windows" && homeDir == "" {
		homeDir = windowsHomeFallback(usr.Username)
	}
	if homeDir == "" {
		return ""
	}
	return filepath.Join(homeDir, ".ssh", "authorized_keys")
}

type windowsAuthorizedKeysTarget struct {
	username string
	sid      string
	homeDir  string
	path     string
}

func sshAuthorizeKeyWindows(payload sshTaskPayload) (map[string]any, error) {
	username := firstNonBlank(payload.Username, currentUsername())
	sessionID := firstNonBlank(payload.SessionID, payload.SessionIDAlt)
	publicKey := firstNonBlank(payload.PublicKey, payload.PublicKeyAlt)
	allowedFrom := firstNonBlank(payload.AllowedFrom, payload.AllowedFromAlt)
	expiresAtText := firstNonBlank(payload.ExpiresAt, payload.ExpiresAtAlt)
	if sessionID == "" {
		return nil, errors.New("session id is required")
	}
	if err := validatePublicKey(publicKey); err != nil {
		return nil, err
	}
	expiresAt := time.Now().UTC().Add(10 * time.Minute)
	if expiresAtText != "" {
		parsed, err := time.Parse(time.RFC3339, expiresAtText)
		if err != nil {
			return nil, fmt.Errorf("invalid expires_at: %w", err)
		}
		expiresAt = parsed.UTC()
	}
	if time.Now().After(expiresAt) {
		return nil, errors.New("ssh key expiration is already in the past")
	}

	target, err := windowsAuthorizedKeysTargetForUser(username)
	if err != nil {
		return nil, err
	}
	if err := ensureWindowsAuthorizedKeysFile(target.path); err != nil {
		return nil, err
	}
	removedExpired, err := cleanupExpiredKeys(target.path)
	if err != nil {
		return nil, err
	}
	line := authorizedKeyLine(publicKey, sessionID, expiresAt, allowedFrom)
	added, err := upsertSessionKey(target.path, sessionID, line)
	if err != nil {
		return nil, err
	}
	ownerWarning, err := setWindowsOpenSSHACLs(target)
	if err != nil {
		return nil, err
	}
	result := map[string]any{
		"username":           target.username,
		"authorizedKeysPath": target.path,
		"sessionId":          sessionID,
		"expiresAt":          expiresAt.Format(time.RFC3339),
		"added":              added,
		"removedExpired":     removedExpired,
		"configChanged":      false,
		"aclAdjusted":        true,
	}
	if ownerWarning != "" {
		result["ownerWarning"] = ownerWarning
	}
	return result, nil
}

func sshRevokeKeyWindows(payload sshTaskPayload) (map[string]any, error) {
	username := firstNonBlank(payload.Username, currentUsername())
	sessionID := firstNonBlank(payload.SessionID, payload.SessionIDAlt)
	if sessionID == "" {
		return nil, errors.New("session id is required")
	}
	target, err := windowsAuthorizedKeysTargetForUser(username)
	if err != nil {
		return nil, err
	}
	removed, err := removeSessionKey(target.path, sessionID)
	if err != nil {
		return nil, err
	}
	ownerWarning, aclErr := setWindowsOpenSSHACLs(target)
	if aclErr != nil && removed {
		return nil, aclErr
	}
	result := map[string]any{
		"username":           target.username,
		"authorizedKeysPath": target.path,
		"sessionId":          sessionID,
		"removed":            removed,
	}
	if ownerWarning != "" {
		result["ownerWarning"] = ownerWarning
	}
	return result, nil
}

func windowsAuthorizedKeysTargetForUser(username string) (windowsAuthorizedKeysTarget, error) {
	usr, err := lookupWindowsUser(username)
	if err != nil {
		return windowsAuthorizedKeysTarget{}, err
	}
	homeDir := usr.HomeDir
	if homeDir == "" {
		homeDir = windowsHomeFallback(usr.Username)
	}
	if homeDir == "" {
		return windowsAuthorizedKeysTarget{}, fmt.Errorf("could not determine home directory for Windows user %q", usr.Username)
	}
	return windowsAuthorizedKeysTarget{
		username: usr.Username,
		sid:      usr.Uid,
		homeDir:  homeDir,
		path:     filepath.Join(homeDir, ".ssh", "authorized_keys"),
	}, nil
}

func lookupWindowsUser(username string) (*user.User, error) {
	trimmed := strings.TrimSpace(username)
	if trimmed == "" {
		trimmed = currentUsername()
	}
	usr, err := user.Lookup(trimmed)
	if err == nil {
		return usr, nil
	}
	shortName := shortWindowsUsername(trimmed)
	if shortName != "" && shortName != trimmed {
		if usr, shortErr := user.Lookup(shortName); shortErr == nil {
			return usr, nil
		}
	}
	return nil, err
}

func windowsHomeFallback(username string) string {
	if envHome := os.Getenv("USERPROFILE"); envHome != "" {
		current := shortWindowsUsername(currentUsername())
		if current != "" && strings.EqualFold(current, shortWindowsUsername(username)) {
			return envHome
		}
	}
	shortName := shortWindowsUsername(username)
	if shortName == "" {
		return ""
	}
	root := os.Getenv("SystemDrive")
	if root == "" {
		root = `C:`
	}
	return filepath.Join(root+`\`, "Users", shortName)
}

func shortWindowsUsername(username string) string {
	value := strings.TrimSpace(username)
	if idx := strings.LastIndexAny(value, `\`); idx >= 0 {
		value = value[idx+1:]
	}
	if idx := strings.LastIndex(value, "@"); idx > 0 {
		value = value[:idx]
	}
	return value
}

func ensureWindowsAuthorizedKeysFile(path string) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	file, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return err
	}
	return file.Close()
}

func setWindowsOpenSSHACLs(target windowsAuthorizedKeysTarget) (string, error) {
	principal := windowsACLPrincipal(target)
	dir := filepath.Dir(target.path)
	if err := runICACLS(dir, "/inheritance:r"); err != nil {
		return "", err
	}
	if err := runICACLS(dir, "/grant:r",
		principal+":(OI)(CI)F",
		"*S-1-5-18:(OI)(CI)F",
		"*S-1-5-32-544:(OI)(CI)F"); err != nil {
		return "", err
	}
	if err := runICACLS(dir, "/remove:g", "*S-1-1-0", "*S-1-5-11", "*S-1-5-32-545"); err != nil {
		return "", err
	}
	if err := runICACLS(target.path, "/inheritance:r"); err != nil {
		return "", err
	}
	if err := runICACLS(target.path, "/grant:r",
		principal+":F",
		"*S-1-5-18:F",
		"*S-1-5-32-544:F"); err != nil {
		return "", err
	}
	if err := runICACLS(target.path, "/remove:g", "*S-1-1-0", "*S-1-5-11", "*S-1-5-32-545"); err != nil {
		return "", err
	}
	if err := runICACLS(target.path, "/setowner", principal); err != nil {
		return err.Error(), nil
	}
	return "", nil
}

func windowsACLPrincipal(target windowsAuthorizedKeysTarget) string {
	if strings.HasPrefix(strings.ToUpper(target.sid), "S-") {
		return "*" + target.sid
	}
	return target.username
}

func runICACLS(path string, args ...string) error {
	commandArgs := append([]string{path}, args...)
	output, err := exec.Command("icacls.exe", commandArgs...).CombinedOutput()
	if err != nil {
		return fmt.Errorf("icacls %s %s failed: %w: %s", path, strings.Join(args, " "), err, strings.TrimSpace(string(output)))
	}
	return nil
}

func ensureAuthorizedKeysFile(path string, uid, gid int) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	if err := setOwnerAndMode(dir, uid, gid, 0o700); err != nil {
		return err
	}
	file, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return err
	}
	return file.Close()
}

func upsertSessionKey(path, sessionID, line string) (bool, error) {
	lines, err := readLines(path)
	if err != nil {
		return false, err
	}
	marker := castrelyxKeyMarker + sessionID
	next := make([]string, 0, len(lines)+1)
	added := true
	for _, existing := range lines {
		if strings.Contains(existing, marker) {
			continue
		}
		if strings.TrimSpace(existing) == strings.TrimSpace(line) {
			added = false
		}
		next = append(next, existing)
	}
	next = append(next, line)
	return added, writeLines(path, next)
}

func removeSessionKey(path, sessionID string) (bool, error) {
	lines, err := readLines(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return false, nil
		}
		return false, err
	}
	marker := castrelyxKeyMarker + sessionID
	next := make([]string, 0, len(lines))
	removed := false
	for _, line := range lines {
		if strings.Contains(line, marker) {
			removed = true
			continue
		}
		next = append(next, line)
	}
	return removed, writeLines(path, next)
}

func cleanupExpiredKeys(path string) (int, error) {
	lines, err := readLines(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return 0, nil
		}
		return 0, err
	}
	now := time.Now().UTC()
	next := make([]string, 0, len(lines))
	removed := 0
	for _, line := range lines {
		expiresAt, ok := castrelyxExpiry(line)
		if ok && now.After(expiresAt) {
			removed++
			continue
		}
		next = append(next, line)
	}
	if removed == 0 {
		return 0, nil
	}
	return removed, writeLines(path, next)
}

func readLines(path string) ([]string, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	lines := []string{}
	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		lines = append(lines, scanner.Text())
	}
	return lines, scanner.Err()
}

func writeLines(path string, lines []string) error {
	data := strings.Join(lines, "\n")
	if data != "" {
		data += "\n"
	}
	return os.WriteFile(path, []byte(data), 0o600)
}

func authorizedKeyLine(publicKey, sessionID string, expiresAt time.Time, allowedFrom string) string {
	options := []string{"no-agent-forwarding", "no-X11-forwarding", "no-port-forwarding"}
	if allowedFrom != "" {
		options = append([]string{`from="` + strings.ReplaceAll(allowedFrom, `"`, "") + `"`}, options...)
	}
	comment := fmt.Sprintf("castrelyx %s%s expires=%s", castrelyxKeyMarker, sessionID, expiresAt.UTC().Format(time.RFC3339))
	return strings.Join(options, ",") + " " + strings.TrimSpace(publicKey) + " " + comment
}

func castrelyxExpiry(line string) (time.Time, bool) {
	if !strings.Contains(line, castrelyxKeyMarker) {
		return time.Time{}, false
	}
	for _, field := range strings.Fields(line) {
		if strings.HasPrefix(field, "expires=") {
			value := strings.TrimPrefix(field, "expires=")
			parsed, err := time.Parse(time.RFC3339, value)
			return parsed, err == nil
		}
	}
	return time.Time{}, false
}

func validatePublicKey(publicKey string) error {
	if publicKey == "" {
		return errors.New("public key is required")
	}
	if strings.ContainsAny(publicKey, "\r\n") {
		return errors.New("public key must be a single line")
	}
	fields := strings.Fields(publicKey)
	if len(fields) < 2 {
		return errors.New("public key must use OpenSSH authorized_keys format")
	}
	switch fields[0] {
	case "ssh-rsa", "ssh-ed25519", "ecdsa-sha2-nistp256":
		return nil
	default:
		return fmt.Errorf("unsupported public key algorithm %q", fields[0])
	}
}

func setOwnerAndMode(path string, uid, gid int, mode os.FileMode) error {
	if err := os.Chmod(path, mode); err != nil {
		return err
	}
	if os.Geteuid() == 0 {
		if err := os.Chown(path, uid, gid); err != nil {
			return err
		}
	}
	return nil
}

func sshdPath() string {
	if runtime.GOOS == "windows" {
		systemRoot := os.Getenv("SystemRoot")
		if systemRoot == "" {
			systemRoot = `C:\Windows`
		}
		candidates := []string{
			"sshd.exe",
			filepath.Join(systemRoot, "System32", "OpenSSH", "sshd.exe"),
			filepath.Join(os.Getenv("ProgramFiles"), "OpenSSH", "sshd.exe"),
		}
		for _, candidate := range candidates {
			if strings.TrimSpace(candidate) == "" {
				continue
			}
			if path, err := exec.LookPath(candidate); err == nil {
				return path
			}
			if filepath.IsAbs(candidate) {
				if _, err := os.Stat(candidate); err == nil {
					return candidate
				}
			}
		}
		return ""
	}
	candidates := []string{"sshd", "/usr/sbin/sshd", "/usr/local/sbin/sshd", "/sbin/sshd"}
	for _, candidate := range candidates {
		if path, err := exec.LookPath(candidate); err == nil {
			return path
		}
		if strings.HasPrefix(candidate, "/") {
			if _, err := os.Stat(candidate); err == nil {
				return candidate
			}
		}
	}
	return ""
}

func firstActiveService(ctx context.Context, names ...string) string {
	if runtime.GOOS == "windows" {
		for _, name := range names {
			output, err := exec.CommandContext(ctx, "sc.exe", "query", name).CombinedOutput()
			if err == nil && strings.Contains(strings.ToUpper(string(output)), "RUNNING") {
				return name
			}
		}
		return ""
	}
	if _, err := exec.LookPath("systemctl"); err != nil {
		return ""
	}
	for _, name := range names {
		cmd := exec.CommandContext(ctx, "systemctl", "is-active", "--quiet", name)
		if err := cmd.Run(); err == nil {
			return name
		}
	}
	return ""
}

func listeningSSHPorts(ctx context.Context) []int {
	output, err := exec.CommandContext(ctx, "ss", "-ltn").Output()
	if err != nil {
		output, err = exec.CommandContext(ctx, "netstat", "-ltn").Output()
		if err != nil {
			return nil
		}
	}
	ports := map[int]bool{}
	scanner := bufio.NewScanner(bytes.NewReader(output))
	for scanner.Scan() {
		for _, field := range strings.Fields(scanner.Text()) {
			host, port, err := net.SplitHostPort(field)
			if err != nil || host == "" {
				continue
			}
			n, err := strconv.Atoi(port)
			if err == nil && n > 0 {
				ports[n] = true
			}
		}
	}
	result := make([]int, 0, len(ports))
	for port := range ports {
		if port == 22 {
			result = append([]int{port}, result...)
		} else {
			result = append(result, port)
		}
	}
	return result
}

func currentUsername() string {
	if current, err := user.Current(); err == nil && current.Username != "" {
		if idx := strings.LastIndexAny(current.Username, `\`); idx >= 0 {
			return current.Username[idx+1:]
		}
		return current.Username
	}
	if value := os.Getenv("SUDO_USER"); value != "" {
		return value
	}
	if value := os.Getenv("USER"); value != "" {
		return value
	}
	return "root"
}

func firstNonBlank(values ...string) string {
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			return strings.TrimSpace(value)
		}
	}
	return ""
}
