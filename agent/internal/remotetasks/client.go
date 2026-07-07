package remotetasks

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

type Client struct {
	baseURL    string
	agentID    string
	httpClient *http.Client
}

type Config struct {
	BaseURL   string
	AgentID   string
	TLSConfig *tls.Config
}

type Task struct {
	TaskID      string `json:"taskId"`
	AgentID     string `json:"agentId"`
	TaskType    string `json:"taskType"`
	PayloadJSON string `json:"payloadJson"`
	Status      string `json:"status"`
	ExpiresAt   string `json:"expiresAt"`
}

type pollRequest struct {
	MaxTasks int `json:"maxTasks"`
}

type resultRequest struct {
	Status       string `json:"status"`
	ResultJSON   string `json:"resultJson"`
	ErrorMessage string `json:"errorMessage,omitempty"`
}

func New(config Config) (*Client, error) {
	if config.BaseURL == "" {
		return nil, errors.New("base url is required")
	}
	if config.AgentID == "" {
		return nil, errors.New("agent id is required")
	}
	if config.TLSConfig == nil {
		return nil, errors.New("tls config is required")
	}
	parsed, err := url.Parse(config.BaseURL)
	if err != nil {
		return nil, err
	}
	if parsed.Scheme != "https" {
		return nil, errors.New("remote task endpoint must use https")
	}
	return &Client{
		baseURL: strings.TrimRight(config.BaseURL, "/"),
		agentID: config.AgentID,
		httpClient: &http.Client{
			Timeout: 30 * time.Second,
			Transport: &http.Transport{
				TLSClientConfig: config.TLSConfig,
			},
		},
	}, nil
}

func (c *Client) PollAndRun(ctx context.Context) error {
	tasks, err := c.poll(ctx)
	if err != nil {
		return err
	}
	for _, task := range tasks {
		result, runErr := Run(ctx, task.TaskType, []byte(task.PayloadJSON))
		status := "COMPLETED"
		message := ""
		if runErr != nil {
			status = "FAILED"
			message = runErr.Error()
		}
		if err := c.report(ctx, task.TaskID, status, result, message); err != nil {
			return err
		}
	}
	return nil
}

func (c *Client) poll(ctx context.Context) ([]Task, error) {
	body, err := json.Marshal(pollRequest{MaxTasks: 5})
	if err != nil {
		return nil, err
	}
	var tasks []Task
	if err := c.doJSON(ctx, http.MethodPost, "/api/agent/tasks/poll", body, &tasks); err != nil {
		return nil, err
	}
	return tasks, nil
}

func (c *Client) report(ctx context.Context, taskID, status string, result map[string]any, message string) error {
	if taskID == "" {
		return errors.New("task id is required")
	}
	encoded, err := json.Marshal(result)
	if err != nil {
		return err
	}
	body, err := json.Marshal(resultRequest{
		Status:       status,
		ResultJSON:   string(encoded),
		ErrorMessage: message,
	})
	if err != nil {
		return err
	}
	var ignored Task
	return c.doJSON(ctx, http.MethodPost, "/api/agent/tasks/"+url.PathEscape(taskID)+"/result", body, &ignored)
}

func (c *Client) doJSON(ctx context.Context, method, path string, body []byte, out any) error {
	req, err := http.NewRequestWithContext(ctx, method, c.baseURL+path, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	data, readErr := io.ReadAll(resp.Body)
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return fmt.Errorf("remote task endpoint returned status %d: %s", resp.StatusCode, strings.TrimSpace(string(data)))
	}
	if readErr != nil {
		return readErr
	}
	if out != nil && len(data) > 0 {
		if err := json.Unmarshal(data, out); err != nil {
			return err
		}
	}
	return nil
}
