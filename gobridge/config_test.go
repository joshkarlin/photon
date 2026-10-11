package main

import (
	"testing"
	"time"

	waLog "go.mau.fi/whatsmeow/util/log"
)

func TestParseConfig_retention(t *testing.T) {
	cases := []struct {
		name string
		args []string
		want RetentionConfig
	}{
		{"defaults", []string{"--data-dir", "/d"}, RetentionConfig{MaxMessages: 50, MaxDays: 7, MediaTTLMins: 5}},
		{"user settings", []string{"--max-messages", "200", "--max-days", "30", "--media-ttl-mins", "10"},
			RetentionConfig{MaxMessages: 200, MaxDays: 30, MediaTTLMins: 10}},
		{"zero keeps the default", []string{"--max-messages", "0", "--max-days", "1"},
			RetentionConfig{MaxMessages: 50, MaxDays: 1, MediaTTLMins: 5}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			cfg, err := parseConfig(tc.args)
			if err != nil {
				t.Fatalf("parseConfig: %v", err)
			}
			b := NewBridge(nil, nil, cfg.dataDir, waLog.Noop)
			b.SetRetention(cfg.retention.MaxMessages, cfg.retention.MaxDays, cfg.retention.MediaTTLMins)
			if b.retention != tc.want {
				t.Errorf("retention = %+v, want %+v", b.retention, tc.want)
			}
		})
	}
	if _, err := parseConfig([]string{"--max-days", "lots"}); err == nil {
		t.Error("a malformed flag should be an error")
	}
}

func TestRunWithin(t *testing.T) {
	if !runWithin(time.Second, func() {}) {
		t.Error("a quick fn reported as still running")
	}
	block := make(chan struct{})
	defer close(block)
	start := time.Now()
	if runWithin(50*time.Millisecond, func() { <-block }) {
		t.Error("a blocked fn reported as done")
	}
	if waited := time.Since(start); waited > time.Second {
		t.Errorf("waited %s for a blocked fn, want about 50ms", waited)
	}
}
