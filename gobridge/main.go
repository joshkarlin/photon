package main

import (
	"context"
	"database/sql"
	"flag"
	"fmt"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"database/sql/driver"

	sqliteDriver "modernc.org/sqlite"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/store/sqlstore"
	waLog "go.mau.fi/whatsmeow/util/log"
)

// fkDriver wraps the modernc sqlite driver and enables foreign keys, WAL
// mode, and a busy timeout on every connection. Both the whatsmeow session
// store and the message DB open through it so the pragmas live in one place
// and apply to every pooled connection (a one-shot Exec only configures
// whichever connection happens to serve it).
type fkDriver struct {
	inner driver.Driver
}

func (d *fkDriver) Open(dsn string) (driver.Conn, error) {
	conn, err := d.inner.Open(dsn)
	if err != nil {
		return nil, err
	}
	// Enable foreign keys, WAL mode, and busy timeout
	for _, pragma := range []string{
		"PRAGMA foreign_keys=ON",
		"PRAGMA journal_mode=WAL",
		"PRAGMA busy_timeout=5000",
	} {
		if stmt, err := conn.Prepare(pragma); err == nil {
			stmt.Exec(nil)
			stmt.Close()
		}
	}
	return conn, nil
}

var fkDriverRegistered bool

func registerFKDriver() {
	if fkDriverRegistered {
		return
	}
	fkDriverRegistered = true
	sql.Register("sqlite_fk", &fkDriver{inner: &sqliteDriver.Driver{}})
}

// messageDBDSN is messages.db (a plain path, not a file: URI) with write
// transactions that take the write lock as they begin (BEGIN IMMEDIATE). A
// deferred transaction that reads first and writes after another connection
// has committed fails with SQLITE_BUSY_SNAPSHOT, which no busy timeout
// retries; revokes and LID merges both read first.
func messageDBDSN(dataDir string) string {
	return filepath.Join(dataDir, "messages.db") + "?_txlock=immediate"
}

// bridgeConfig is the bridge's command line.
type bridgeConfig struct {
	dataDir   string
	logLevel  string
	retention RetentionConfig
}

// parseConfig reads the command line. Retention flags carry the user's
// settings across bridge restarts; zero or absent keeps the defaults (50
// messages per chat, 7 days, 5-minute media), and Kotlin still sends
// set_retention for live changes.
func parseConfig(args []string) (bridgeConfig, error) {
	var cfg bridgeConfig
	fs := flag.NewFlagSet("gobridge", flag.ContinueOnError)
	fs.StringVar(&cfg.dataDir, "data-dir", ".", "Path to data directory")
	fs.StringVar(&cfg.logLevel, "log-level", "DEBUG", "Log level (DEBUG, INFO, WARN, ERROR)")
	fs.IntVar(&cfg.retention.MaxMessages, "max-messages", 0, "Messages kept per chat (0 = default)")
	fs.IntVar(&cfg.retention.MaxDays, "max-days", 0, "Days of history kept (0 = default)")
	fs.IntVar(&cfg.retention.MediaTTLMins, "media-ttl-mins", 0, "Minutes downloaded media is kept (0 = default)")
	err := fs.Parse(args)
	return cfg, err
}

func main() {
	cfg, err := parseConfig(os.Args[1:])
	if err != nil {
		os.Exit(2)
	}
	if abs, err := filepath.Abs(cfg.dataDir); err == nil {
		cfg.dataDir = abs
	}

	// stdout is the line protocol to Kotlin (transport.go). whatsmeow's
	// loggers print to os.Stdout, so point that — and any stray print — at
	// stderr, which Kotlin forwards to logcat.
	protocolOut := os.Stdout
	os.Stdout = os.Stderr

	logger := waLog.Stdout("Bridge", cfg.logLevel, false)
	logger.Infof("Starting Photon bridge, data-dir=%s", cfg.dataDir)

	// Android doesn't have /etc/resolv.conf, so Go's pure-Go DNS resolver
	// can't find DNS servers. Override the default resolver to use Google DNS.
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
			d := net.Dialer{}
			return d.DialContext(ctx, "udp", "8.8.8.8:53")
		},
	}

	// Ensure directories exist
	os.MkdirAll(filepath.Join(cfg.dataDir, "media"), 0755)
	os.MkdirAll(filepath.Join(cfg.dataDir, "thumbs"), 0755)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// Initialize whatsmeow session store (pure Go SQLite)
	// whatsmeow requires foreign keys enabled. Register a custom driver
	// that runs PRAGMA foreign_keys=ON on each connection.
	sessionPath := filepath.Join(cfg.dataDir, "whatsmeow.db")
	registerFKDriver()
	container, err := sqlstore.New(ctx, "sqlite_fk", sessionPath, waLog.Stdout("Session", cfg.logLevel, false))
	if err != nil {
		logger.Errorf("Failed to init session store: %v", err)
		os.Exit(1)
	}

	// The sqlite_fk driver applies the WAL/foreign keys/busy timeout pragmas.
	msgDB, err := sql.Open("sqlite_fk", messageDBDSN(cfg.dataDir))
	if err != nil {
		logger.Errorf("Failed to open message DB: %v", err)
		os.Exit(1)
	}

	// Get or create device
	device, err := container.GetFirstDevice(ctx)
	if err != nil {
		logger.Errorf("Failed to get device: %v", err)
		os.Exit(1)
	}

	// Set device name shown in WhatsApp linked devices
	photonName := "Photon"
	store.DeviceProps.Os = &photonName

	// Create whatsmeow client
	client := whatsmeow.NewClient(device, waLog.Stdout("Client", cfg.logLevel, false))
	// A stored session can need a few attempts after a long offline period or
	// a transient primary-device/network outage. Let whatsmeow retry an initial
	// connection in the background instead of returning a terminal failure.
	client.InitialAutoReconnect = true

	// Create bridge
	bridge := NewBridge(client, msgDB, cfg.dataDir, logger)
	bridge.out = &lineWriter{w: protocolOut}
	bridge.SetRetention(cfg.retention.MaxMessages, cfg.retention.MaxDays, cfg.retention.MediaTTLMins)
	logger.Infof("Retention: %+v", bridge.retention)

	// Initialize message DB schema
	if err := bridge.InitMessageDB(ctx); err != nil {
		logger.Errorf("Failed to init message DB: %v", err)
		os.Exit(1)
	}

	// Register event handler
	client.AddEventHandler(bridge.HandleEvent)

	// Start periodic pruning of old messages and temp media
	bridge.StartPruning(ctx)
	bridge.StartConnectionWatchdog(ctx)

	// Answer Kotlin's requests until it closes our stdin.
	served := make(chan error, 1)
	go func() { served <- bridge.Serve(ctx, os.Stdin) }()

	// Connect to WhatsApp if already paired
	if client.Store.ID != nil {
		logger.Infof("Existing session found, connecting...")
		bridge.setConnectionState("connecting", "starting WhatsApp connection")
		if err := client.Connect(); err != nil {
			logger.Errorf("Failed to connect: %v", err)
			bridge.setConnectionState("connecting", fmt.Sprintf("initial connection failed; retrying: %v", err))
		}
	} else {
		logger.Infof("No session found, waiting for pairing request...")
		bridge.setConnectionState("logged_out", "no paired WhatsApp session")
	}

	// Run until the app closes our stdin (it exited, or is restarting us) or
	// we're signalled.
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	select {
	case err := <-served:
		logger.Infof("Request stream closed (%v), shutting down", err)
	case sig := <-sigCh:
		logger.Infof("Received %s, shutting down", sig)
	}
	cancel()
	// Exit promptly whatever whatsmeow's Disconnect is doing — it can block
	// for most of a minute. The app may be starting this bridge's replacement
	// (a reset, say), and two bridges must never share the session. Committed
	// writes are safe in the WAL either way.
	if !runWithin(shutdownGrace, func() {
		client.Disconnect()
		msgDB.Close()
	}) {
		logger.Warnf("Still disconnecting after %s; exiting anyway", shutdownGrace)
	}
}

// shutdownGrace is how long the bridge waits for a clean disconnect once
// told to stop. Kotlin kills it if it's still running a little later.
const shutdownGrace = time.Second

// runWithin runs fn and waits at most limit for it to return; false if it
// was still running.
func runWithin(limit time.Duration, fn func()) bool {
	done := make(chan struct{})
	go func() {
		fn()
		close(done)
	}()
	select {
	case <-done:
		return true
	case <-time.After(limit):
		return false
	}
}
