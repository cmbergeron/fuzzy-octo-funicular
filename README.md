# Bulb Control

An Android app (Kotlin + Jetpack Compose) that controls a Bluetooth LE light bulb:
power on/off, color, saturation, brightness, and preset colors.

It supports the two most common families of cheap Bluetooth bulbs and works out
which one your bulb uses after it connects:

| Protocol | Usually sold with these apps / names | Service / characteristic |
|---|---|---|
| Triones / Magic Blue | Happy Lighting, Magic Blue, LEDBLE, Triones, iLight | `FFD5/FFD9` or `FFE5/FFE9` |
| ELK-BLEDOM | Lotus Lamp, duoCo Strip, ELK-BLEDOM | `FFF0/FFF3` |
| Standard Bluetooth Mesh | Sylvania / LEDVANCE and other SIG Mesh bulbs | `1827` (new) / `1828` (set up) |

**Bluetooth Mesh bulbs (e.g. Sylvania):** the first time you connect, the app adds the
bulb to its own encrypted mesh network (keys are stored on the phone). After that the
bulb belongs to this app, and the manufacturer's app can't control it until you
factory-reset the bulb. A bulb already set up in another app must be factory-reset
before this app can use it. The app's **Details** card shows each setup step.

If your bulb isn't one of these, see [Adding your bulb](#adding-your-bulb).

There's also a **Demo mode** (switch in the top bar) with a pretend bulb, so you
can try the whole app on the Android Studio emulator.

---

## 1. Open the project in Android Studio

1. Install [Android Studio](https://developer.android.com/studio) (Ladybug 2024.2 or newer).
2. **File → Open…** and pick this folder (the one with `settings.gradle.kts`).
3. Wait for **Gradle sync** to finish (bottom status bar). The first sync downloads
   the Android SDK pieces and libraries and can take a few minutes.
   - If it asks to install a missing SDK platform (API 35), click the link to install it.
   - If it complains about the JDK: **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK** → pick the bundled "jbr-17" (or newer).

## 2. Run the unit tests (no phone needed)

These check the exact bytes sent to the bulb and the color math.

- In the **Project** panel open `app/src/test/java/com/example/bulbcontrol/`.
- Right-click the `bulbcontrol` folder → **Run 'Tests in bulbcontrol'**.
- Or from the terminal at the bottom of Android Studio: `./gradlew test`
  (on Windows: `gradlew test`).

You should see 10 green tests.

## 3. Try the app on the emulator (Demo mode)

The emulator can't connect to a real Bluetooth bulb, so use Demo mode there.

1. **Tools → Device Manager → Create virtual device** (➕). Choose e.g. *Pixel 8*, a
   system image with API 34 or 35, then **Finish**.
2. Pick that device in the toolbar dropdown and click **Run ▶** (Shift+F10).
3. In the app, turn on the **Demo** switch (top right). It turns on by itself if the
   emulator has no Bluetooth.
4. Tap **Scan for bulbs** → tap **Demo Bulb (Triones)** → wait for "Connected".
5. Move the sliders and tap presets. The circle shows the color, and
   **Last command** at the bottom shows the exact bytes a real bulb would receive.

### Automated UI test

`app/src/androidTest/.../DemoModeUiTest.kt` taps through the same steps by itself
(scan → connect → Red) and checks the bytes. With the emulator running:

- Right-click `DemoModeUiTest` → **Run 'DemoModeUiTest'**, or
- terminal: `./gradlew connectedAndroidTest`

## 4. Test with your real bulb on a real phone

1. On the phone: **Settings → About phone** → tap **Build number** 7 times to
   unlock Developer options. Then **Settings → System → Developer options** → turn on
   **USB debugging** (or **Wireless debugging**).
2. Plug the phone into the computer and accept the "Allow USB debugging?" prompt.
3. Choose the phone in Android Studio's device dropdown and click **Run ▶**.
4. Make sure:
   - the bulb is powered on,
   - **the bulb's own app is closed** (most bulbs accept only one connection at a time),
   - Bluetooth is on (the app will offer to turn it on),
   - on Android 11 or lower, Location is also turned on (Android needs it for Bluetooth scanning there).
5. Make sure **Demo** is off, tap **Scan for bulbs**, allow the *Nearby devices*
   permission, and tap your bulb in the list.

### If something goes wrong

- **Watch the logs:** open **Logcat** (bottom panel) and type `BleBulbLink` in the
  filter box to see connection and write messages.
- **Bulb not in the list:** move closer, close the bulb's own app (or force-stop it),
  and turn the bulb off and on again at the wall switch.
- **"This bulb's protocol isn't supported yet"**: the app connected but didn't
  recognise the bulb. The message lists the services it found. See below.
- **Connects, but nothing changes:** look at **Last command** to see what was sent,
  and compare it with your bulb's protocol.

## Adding your bulb

1. Install **nRF Connect** (by Nordic Semiconductor) on your phone, scan, and connect
   to your bulb.
2. Find the service with a characteristic marked **WRITE** or **WRITE NO RESPONSE**,
   and note both UUIDs.
3. Look up the byte commands for the bulb (search its app name + "BLE protocol").
4. Add a new `object` that implements `BulbProtocol` in
   `app/src/main/java/com/example/bulbcontrol/ble/BulbProtocol.kt`, and add it to
   `BulbProtocols.all`. Add a unit test for it in `BulbProtocolTest.kt`.

## Project layout

```
app/src/main/java/com/example/bulbcontrol/
├── MainActivity.kt        permissions, turning Bluetooth on, theme
├── BulbViewModel.kt       light state, picks real or demo link
├── LightState.kt          color model + HSV→RGB
├── ble/
│   ├── BulbProtocol.kt    byte commands for each bulb family
│   ├── BulbLink.kt        common interface + connection state
│   ├── BleBulbLink.kt     real Bluetooth LE: scan, connect, queued writes
│   └── DemoBulbLink.kt    pretend bulb for the emulator
└── ui/BulbScreen.kt       Compose UI
```

Requirements: Android 8.0 (API 26) or newer, with Bluetooth LE.
