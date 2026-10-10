# 🛡️ SafeShield: Zero-Cost Parental Control System

A high-security, **$0 Infrastructure / Serverless** parental control system for Android powered by **Rust Core (Ed25519 Cryptography + Offline SQLite Engine)**, **Telegram Long Polling Transport**, and **Android Native Enforcement (Device Admin / Device Owner / Accessibility / System Overlay)**.

---

## 🌟 Key Features

1. **$0 Server/VPS Infrastructure**:
   - Uses Telegram Bot API `getUpdates` (Long Polling) as a zero-cost asynchronous transport queue.
   - Works behind Carrier-Grade NAT (4G/5G) and Wi-Fi routers without public IPs or port forwarding.

2. **Zero-Trust Asymmetric Cryptography**:
   - Every parent command is digitally signed using **Ed25519**.
   - Optional End-to-End Encryption with **X25519 Diffie-Hellman + ChaCha20-Poly1305** ensures Telegram servers cannot read app limits or child usage stats.
   - **Anti-Replay Protection**: Nonce verification + 60-second time drift window prevents intercepted messages from being re-sent.

3. **100% Offline Policy Resilience**:
   - Bedtime schedules, study hours, and app quotas are stored and evaluated inside the child phone's local SQLite database.
   - Continues enforcing rules even if the internet is turned off or Telegram is blocked.

4. **Maximum Hardening & Anti-Tamper**:
   - Supports **Device Owner Mode** via ADB (prevents uninstall, force-stop, or data clearing in Settings).
   - Accessibility Service detects and closes restricted apps or settings.
   - Hardware-level fullscreen lock overlay with Emergency 911/SOS dialer bypass.

---

## 📁 Project Structure

```
.
├── Cargo.toml                     # Rust workspace configuration
├── build_android_libs.sh          # Android NDK compilation helper
├── core/                          # Rust Core Library (UniFFI / JNI)
│   ├── Cargo.toml
│   ├── src/
│   │   ├── lib.rs                 # Crate exports & UniFFI scaffolding
│   │   ├── error.rs               # Unified CoreError definitions
│   │   ├── crypto/                # Ed25519, X25519, ChaCha20, Nonce utilities
│   │   ├── protocol/              # Commands, ACKs, QR Pairing, Telegram Wire format
│   │   ├── policy/                # SQLite Storage & Policy Evaluator
│   │   ├── engine/                # ChildEngine & ParentEngine implementations
│   │   └── ffi.rs                 # UniFFI Kotlin/Swift exported bridge
│   └── tests/
│       └── integration_tests.rs   # Comprehensive end-to-end integration test suite
│
└── android/                       # Android Project (Kotlin + Jetpack Compose)
    ├── build.gradle.kts
    ├── settings.gradle.kts
    ├── core-bridge/               # Shared Kotlin bindings for Rust Core
    ├── app-child/                 # Child Device App
    │   └── src/main/java/com/parentalcontrol/child/
    │       ├── receiver/          # AdminReceiver & BootReceiver
    │       ├── service/           # TelegramPollingService, Accessibility, LockOverlay
    │       └── ui/                # MainActivity (QR Generator, Permissions Setup)
    └── app-parent/                # Parent Controller App
        └── src/main/java/com/parentalcontrol/parent/
            ├── engine/            # ParentEngineHolder & Child Store
            ├── telegram/          # TelegramDispatcher HTTP Client
            └── ui/                # ParentMainActivity (Dashboard, Locks, Schedules, Limits)
```

---

## 🚀 Running Rust Integration Tests

The Rust Core engine contains full test coverage for cryptographic verification, replay rejection, QR pairing, schedule evaluation, and Telegram parsing:

```bash
cargo test --manifest-path core/Cargo.toml
```

---

## 📱 Android Device Owner Setup (Optional / Recommended)

To grant the Child App absolute anti-uninstall protection:

```bash
# Ensure no other Google accounts exist on the device before running
adb shell dpm set-device-owner com.parentalcontrol.child/.receiver.AdminReceiver
```

---

## 🔑 Pairing Protocol Workflow

1. Open **Child SafeShield** on child's phone -> Tap **"Show Pairing QR Code"**.
2. Open **Parent SafeShield** on parent's phone -> Tap **"Pair Child"** and scan the QR.
3. The parent app automatically generates a cryptographic `PairConfirm` message, signs it with the parent's Ed25519 private key, and dispatches it over Telegram.
4. The child app verifies the setup token and registers the parent public key into local SQLite. Both devices are now securely linked.
