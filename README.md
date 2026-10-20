# JENI

### Autonomous On-Device Schedule Assistant & Engine

JENI (Schedule Assistant) is a modern, privacy-first Android application and offline scheduling engine. It analyzes incoming WhatsApp messages completely on-device without cloud dependencies, extracts dates, times, and schedule proposals, and automatically arms exact system alarms and reminders.

---

## 🌟 Key Features

- **100% Offline & Private**: Zero cloud dependency. Ingestion, keyword filtering, natural language date/time parsing, and sync event projection run entirely on-device.
- **WhatsApp Notification Integration**: Seamless background detection via Android Notification Listener Service.
- **Hybrid Logical Clock (HLC)**: Deterministic CRDT-style event-sourced state projection ensuring conflict-free offline scheduling and cross-device synchronization.
- **AlarmManager & System Clock**: High-precision alarms (`AlarmManager.setAlarmClock`) with custom ringing UI, lock-screen overlay, and battery optimization survival.
- **Sleek, Modern Android UI**: Built with Jetpack Compose featuring dark mode aesthetics, fluid micro-animations, and clean typography.
- **Developer Console**: Hidden 5-tap developer mode providing real-time hardware telemetry, SQLite audit logs, synthetic message injector, and diagnostics export.

---

## 🏗️ Architecture

```
ai_agent/
├── android/            # Native Android App (Kotlin, Jetpack Compose, Room, WorkManager)
├── core/               # Shared Schedule Parser & Date-Time Matching Engine (TypeScript)
├── spec/               # Machine-readable schemas, dayparts, and phrase dictionaries
├── laptop/             # Desktop/CLI evaluation harness & soak test runner
└── relay/              # Peer-to-peer sync relay protocol
```

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Iguana+ (or latest)
- JDK 17
- Android SDK (API 34 / targetSdk 34, minSdk 26)
- Node.js 18+ (for core spec & engine tests)

### Build Android APK
```bash
cd android
./gradlew assembleDebug
```
The output APK will be generated at `android/app/build/outputs/apk/debug/app-debug.apk`.

### Run Test Suites
```bash
# Android Unit Tests (67 tests)
cd android && ./gradlew testDebugUnitTest

# Monorepo Core Engine Tests (69 tests)
npm test
```

---

## 📜 License & Credits

Developed by **Qwrtystds.**
