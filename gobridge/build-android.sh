#!/bin/bash
set -e
cd "$(dirname "$0")"

# Build as a .so in jniLibs so Android treats it as an executable native library.
# Files in assets/ or filesDir are on noexec mounts on modern Android.
OUTPUT_DIR="../app/src/main/jniLibs/arm64-v8a"
mkdir -p "$OUTPUT_DIR"

GOOS=android GOARCH=arm64 CGO_ENABLED=0 \
  go build -buildvcs=false -trimpath -ldflags="-s -w" \
  -o "$OUTPUT_DIR/libgobridge.so" \
  .
echo "Built libgobridge.so for android/arm64 ($(du -h "$OUTPUT_DIR/libgobridge.so" | cut -f1))"
