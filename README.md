# Wi-Fi Phone (Secure Offline Wi-Fi Calling App)

A lightweight, military-grade encrypted P2P Wi-Fi calling tool designed to run across legacy Android (Android 5.0 Lollipop - Vivo Y51L) up to modern flagship Android (Android 14+ - Samsung Galaxy S24 Ultra).

---

## 📁 File Structure & System Guide (Har File Ka Kaam)

```
WiFiCallingApp/
│
├── app/
│   ├── build.gradle                              <- Android Build parameters (minSdk 21, targetSdk 34)
│   └── src/main/
│       ├── AndroidManifest.xml                   <- Permissions, Service declaration, FullScreen intent
│       │
│       ├── java/com/secure/wificalling/
│       │   ├── security/
│       │   │   └── CryptoUtil.java               <- AES-256-GCM encryption & HMAC integrity verification
│       │   │
│       │   ├── network/
│       │   │   └── CallSignaling.java            <- Offline P2P UDP discovery & encrypted TCP call signaling
│       │   │
│       │   ├── audio/
│       │   │   └── VoiceEngine.java              <- 16kHz HD Audio capture, Echo Cancellation & AES-256 stream
│       │   │
│       │   ├── service/
│       │   │   └── CallBackgroundService.java    <- 24/7 Persistent Service (WifiLock + WakeLock)
│       │   │
│       │   └── ui/
│       │       ├── MainActivity.java             <- Google Phone style dialer card screen
│       │       ├── IncomingCallActivity.java     <- Full-screen incoming call wake screen (Answer / Reject)
│       │       └── InCallActivity.java           <- Live call screen (Timer, Mute, Speaker, Hangup)
│       │
│       └── res/
│           ├── layout/
│           │   ├── activity_main.xml             <- Google Dialer style UI with device cards
│           │   ├── activity_incoming_call.xml    <- Full-screen incoming call layout with large avatar
│           │   └── activity_in_call.xml          <- Active voice call screen layout
│           └── values/
│               ├── colors.xml                    <- Dark Material palette (Green/Red call buttons)
│               └── styles.xml                    <- Full-screen dark theme
│
├── build.gradle                                  <- Project-level Gradle configuration
└── settings.gradle                               <- Module repository links
```

---

## 🔒 Security & Privacy Architecture (Hacker-Proof Design)

1. **Zero Cloud Servers:** 
   * Yeh app kisi bhi third-party cloud ya internet server par audio ya packets nahi bhejti.
   * Saara communication sirf aapke ghar ke local Wi-Fi router ke andar P2P (Device-to-Device) hota hai.
2. **AES-256-GCM End-to-End Encryption (`CryptoUtil.java`):**
   * Har call command (INVITE, RING, ACCEPT, HANGUP) aur audio packet 256-bit AES-GCM se encrypted hota hai.
   * Agar koi hacker Wi-Fi par Wireshark ya packet sniffer laga kar baitha ho, tab bhi use sirf garbage encrypted bytes milenge — audio sunna ya fake packet bhejna mathematically impossible hai.
3. **Zero Data Storage:**
   * App phone ke kisi photo, video, contact ya storage ko touch nahi karti. Iska APK size sirf ~5-8 MB rehta hai.

---

## 📱 Lock Screen & Vivo Battery Killer Bypass (Ringtone Solution)

1. **Vivo Y51L (Android 5.0.2):**
   * `WifiLock (WIFI_MODE_FULL_HIGH_PERF)` aur `WakeLock (PARTIAL_WAKE_LOCK)` ensure karte hain ki Funtouch OS Wi-Fi chip ko deep-sleep me na dale.
   * Status bar me persistent silent foreground notification rehta hai jisse i Manager process ko terminate nahi karta.
2. **Samsung S24 Ultra & Modern Android:**
   * `setShowWhenLocked(true)` aur `setTurnScreenOn(true)` use hota hai taaki phone locked hone par bhi direct Google Phone jaisi incoming screen pop-up ho.

---

## 🛠️ How to Compile APK in Android Studio

1. **Android Studio** open karein.
2. **Open Existing Project** par click karke folder select karein:  
   `C:\Users\SK\.gemini\antigravity\scratch\WiFiCallingApp`
3. Gradle sync complete hone dein.
4. Top menu se: **Build -> Build Bundle(s) / APK(s) -> Build APK(s)**.
5. Generated `app-debug.apk` ko:
   * Samsung Galaxy S24 Ultra me install karein.
   * Vivo Y51L me install karein.
   * OnePlus Nord CE 2 Lite me install karein.
6. Sabhi phones par app open karke Microphone permission Allow karein — Bas! Ek phone se "CALL" dabate hi dusra phone locked hone par bhi foran bajega!
