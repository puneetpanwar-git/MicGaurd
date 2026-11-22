# Mic Guard

Phone screen turns **green** when you are in a call with the mic live, **red** when muted,
**black** when there is no call. **Tap the phone screen to mute / unmute the laptop mic.**

```
laptop/laptop_agent.py   Windows agent (Bluetooth LE peripheral)
app/                     Native Android app (Kotlin)  ->  builds to the APK
web/index.html           Optional browser version (Chrome on Android, needs HTTPS)
.github/workflows/       Builds the APK in the cloud
```

## 1. Get the APK (pick one)

**A. No installs - build in the cloud (about 5 minutes)**
1. Create a free GitHub repository and upload everything in this folder (keep the `.github` folder).
2. Open the repository's **Actions** tab -> **Build APK** -> wait for the green tick.
3. Open the finished run, download the **MicGuard-apk** artifact, unzip it to get `app-debug.apk`.

**B. Android Studio**
Open this folder in Android Studio, let it sync, then **Build -> Build APK(s)**.
The APK is in `app/build/outputs/apk/debug/app-debug.apk`.

Copy the APK to your phone and install it (allow "install unknown apps" when asked).

## 2. Run the laptop agent (Windows 10/11)

```
pip install winsdk websockets pycaw comtypes
python laptop/laptop_agent.py
```
Leave it running. Pair/turn on Bluetooth on both devices (no manual pairing is needed
for Bluetooth LE, the app finds the laptop by itself).

**Teams:** turn on *Settings -> Privacy -> Third-party app API* in new Teams and approve
the pairing prompt the first time. Without it, the agent falls back to the Windows
microphone mute switch, which cannot see Teams' own mute button.

## 3. Use it

Open **Mic Guard** on the phone and allow the Bluetooth permission. It connects automatically.
- Tap during a call -> toggles mute (screen flips green/red).
- Tap while not connected -> retries the connection.
- Long-press -> demo mode to preview the colours (tap cycles, long-press again exits).
