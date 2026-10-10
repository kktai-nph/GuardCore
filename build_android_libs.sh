#!/usr/bin/env bash
set -euo pipefail

# Build script for compiling Rust Core for Android target architectures
TARGET_DIR="android/core-bridge/src/main/jniLibs"
mkdir -p "$TARGET_DIR/arm64-v8a" "$TARGET_DIR/armeabi-v7a" "$TARGET_DIR/x86_64" "$TARGET_DIR/x86"

echo "=== Building Rust Core Crate ==="
cargo build --manifest-path core/Cargo.toml --release

echo "=== To compile for Android devices via cargo-ndk ==="
echo "Install cargo-ndk: cargo install cargo-ndk"
echo "Example command: cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o android/core-bridge/src/main/jniLibs build --release"

echo "Done!"
