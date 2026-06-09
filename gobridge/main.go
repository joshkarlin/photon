package main

import (
	"context"
	"database/sql"
	"flag"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"

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

func main() {
	dataDir := flag.String("data-dir", ".", "Path to data directory")
	wsPort := flag.Int("ws-port", 8765, "WebSocket server port")
	logLevel := flag.String("log-level", "DEBUG", "Log level (DEBUG, INFO, WARN, ERROR)")
	flag.Parse()

	logger := waLog.Stdout("Bridge", *logLevel, true)
	logger.Infof("Starting Photon bridge, data-dir=%s, ws-port=%d", *dataDir, *wsPort)

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
	os.MkdirAll(filepath.Join(*dataDir, "media"), 0755)
	os.MkdirAll(filepath.Join(*dataDir, "thumbs"), 0755)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// Initialize whatsmeow session store (pure Go SQLite)
	// whatsmeow requires foreign keys enabled. Register a custom driver
	// that runs PRAGMA foreign_keys=ON on each connection.
	sessionPath := filepath.Join(*dataDir, "whatsmeow.db")
	registerFKDriver()
	container, err := sqlstore.New(ctx, "sqlite_fk", sessionPath, waLog.Stdout("Session", *logLevel, true))
	if err != nil {
		logger.Errorf("Failed to init session store: %v", err)
		os.Exit(1)
	}

	// Initialize message database (use plain path, not file: URI). The
	// sqlite_fk driver applies the WAL/foreign keys/busy timeout pragmas.
	msgDSN := filepath.Join(*dataDir, "messages.db")
	msgDB, err := sql.Open("sqlite_fk", msgDSN)
	if err != nil {
		logger.Errorf("Failed to open message DB: %v", err)
		os.Exit(1)
	}
	defer msgDB.Close()

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
	client := whatsmeow.NewClient(device, waLog.Stdout("Client", *logLevel, true))

	// Create bridge
	bridge := NewBridge(client, msgDB, *dataDir, logger)

	// Initialize message DB schema
	if err := bridge.InitMessageDB(ctx); err != nil {
		logger.Errorf("Failed to init message DB: %v", err)
		os.Exit(1)
	}

	// Register event handler
	client.AddEventHandler(bridge.HandleEvent)

	// Start periodic pruning of old messages and temp media
	bridge.StartPruning(ctx)

	// Start WebSocket server
	mux := http.NewServeMux()
	bridge.RegisterWSHandler(mux)

	addr := fmt.Sprintf("127.0.0.1:%d", *wsPort)
	server := &http.Server{Addr: addr, Handler: mux}

	go func() {
		logger.Infof("WebSocket server listening on %s", addr)
		if err := server.ListenAndServe(); err != http.ErrServerClosed {
			logger.Errorf("WS server error: %v", err)
		}
	}()

	// Connect to WhatsApp if already paired
	if client.Store.ID != nil {
		logger.Infof("Existing session found, connecting...")
		if err := client.Connect(); err != nil {
			logger.Errorf("Failed to connect: %v", err)
		}
	} else {
		logger.Infof("No session found, waiting for pairing request...")
		bridge.BroadcastEvent("connection_state", map[string]interface{}{"state": "logged_out"})
	}

	// Wait for shutdown signal
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	<-sigCh

	logger.Infof("Shutting down...")
	client.Disconnect()
	server.Shutdown(ctx)
}
