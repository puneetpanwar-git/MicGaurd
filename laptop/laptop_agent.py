"""
Mic Guard - laptop agent (Windows 10/11)

Two-way link with the phone over Bluetooth LE:
  laptop -> phone : GREEN = in a call, mic LIVE   RED = in a call, mic MUTED   BLACK = no call
  phone  -> laptop: tapping the phone screen toggles the mic mute

State byte: 0 = no call, 1 = live, 2 = muted.   Command byte written by the phone: 1 = toggle mute.

Install:   pip install winsdk websockets pycaw comtypes
Run:       python laptop_agent.py

How it works
 1. New Teams "Third-party app API" (ws://127.0.0.1:8124) - reads Teams' own mute state and
    toggles Teams mute. Enable it in Teams: Settings > Privacy > Third-party app API.
    The first run shows an approval prompt inside Teams; the token is saved to teams_token.txt.
 2. Fallback for Zoom / Meet / anything else: the Windows default-microphone mute flag plus
    "is any app currently capturing audio". Tapping the phone toggles that Windows mute.
"""
import asyncio
import json
import os
import time
import uuid

SERVICE_UUID = uuid.UUID("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
CHAR_UUID = uuid.UUID("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
TOKEN_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "teams_token.txt")

state = {"teams": None, "value": 0, "ws": None}  # teams: None | 0/1/2 ; value: last broadcast
changed = asyncio.Event()


# ---------- Teams API ----------
async def teams_listener():
    try:
        import websockets
    except ImportError:
        print("websockets not installed; Teams API disabled")
        return
    while True:
        token = open(TOKEN_FILE).read().strip() if os.path.exists(TOKEN_FILE) else ""
        url = ("ws://127.0.0.1:8124?token=%s&protocol-version=2.0.0"
               "&manufacturer=MicGuard&device=Laptop&app=MicGuard&app-version=1.0" % token)
        try:
            async with websockets.connect(url) as ws:
                print("Connected to Teams API")
                state["ws"] = ws
                await ws.send(json.dumps({
                    "action": "query-state", "service": "query-state",
                    "manufacturer": "MicGuard", "device": "Laptop",
                    "timestamp": int(time.time()), "requestId": 1}))
                async for raw in ws:
                    msg = json.loads(raw)
                    if msg.get("tokenRefresh"):
                        open(TOKEN_FILE, "w").write(msg["tokenRefresh"])
                    ms = (msg.get("meetingUpdate") or {}).get("meetingState")
                    if ms is not None:
                        if not ms.get("isInMeeting"):
                            state["teams"] = 0
                        else:
                            state["teams"] = 2 if ms.get("isMuted") else 1
                        changed.set()
        except Exception:
            pass
        state["ws"] = None
        state["teams"] = None
        await asyncio.sleep(5)


async def teams_toggle_mute():
    ws = state["ws"]
    if ws is None:
        return False
    await ws.send(json.dumps({
        "action": "toggle-mute", "service": "toggle-mute",
        "manufacturer": "MicGuard", "device": "Laptop",
        "timestamp": int(time.time()), "requestId": 2}))
    return True


# ---------- Windows fallback ----------
def _mic_endpoint():
    from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume
    from comtypes import CLSCTX_ALL
    import comtypes
    comtypes.CoInitialize()
    mic = AudioUtilities.GetMicrophone()
    vol = mic.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None)
    return mic, vol.QueryInterface(IAudioEndpointVolume)


def windows_mic_state():
    """0 no capture / 1 live / 2 muted, using the default microphone."""
    try:
        from pycaw.pycaw import IAudioSessionManager2, IAudioSessionControl2
        from comtypes import CLSCTX_ALL
        mic, vol = _mic_endpoint()
        muted = bool(vol.GetMute())
        active = False
        mgr = mic.Activate(IAudioSessionManager2._iid_, CLSCTX_ALL, None)
        mgr = mgr.QueryInterface(IAudioSessionManager2)
        enum = mgr.GetSessionEnumerator()
        for i in range(enum.GetCount()):
            ctl = enum.GetSession(i).QueryInterface(IAudioSessionControl2)
            if ctl.GetState() == 1:  # AudioSessionStateActive
                active = True
                break
        if not active:
            return 0
        return 2 if muted else 1
    except Exception:
        return 0


def windows_toggle_mute():
    try:
        _, vol = _mic_endpoint()
        vol.SetMute(0 if vol.GetMute() else 1, None)
        return True
    except Exception as e:
        print("Windows mute toggle failed:", e)
        return False


async def fallback_poller():
    while True:
        changed.set()
        await asyncio.sleep(1.0)


def current_value():
    if state["teams"] is not None and state["teams"] != 0:
        return state["teams"]
    return windows_mic_state()


async def handle_toggle(loop):
    """Phone tapped: toggle mute only while a call is active."""
    v = state["value"]
    if v == 0:
        print("Tap ignored: no active call")
        return
    done = False
    if state["teams"] in (1, 2):
        done = await teams_toggle_mute()
    if not done:
        done = await loop.run_in_executor(None, windows_toggle_mute)
    print("Mute toggled from phone" if done else "Could not toggle mute")
    changed.set()


# ---------- BLE GATT server ----------
async def main():
    from winsdk.windows.devices.bluetooth.genericattributeprofile import (
        GattServiceProvider, GattLocalCharacteristicParameters,
        GattCharacteristicProperties, GattServiceProviderAdvertisingParameters,
        GattWriteOption)
    from winsdk.windows.storage.streams import DataWriter, DataReader

    def buf(v):
        w = DataWriter()
        w.write_bytes(bytes([v]))
        return w.detach_buffer()

    res = await GattServiceProvider.create_async(SERVICE_UUID)
    if res.error != 0:
        raise SystemExit("Bluetooth LE peripheral mode not available: %s" % res.error)
    provider = res.service_provider

    params = GattLocalCharacteristicParameters()
    params.characteristic_properties = (
        GattCharacteristicProperties.READ
        | GattCharacteristicProperties.NOTIFY
        | GattCharacteristicProperties.WRITE
        | GattCharacteristicProperties.WRITE_WITHOUT_RESPONSE)
    cres = await provider.service.create_characteristic_async(CHAR_UUID, params)
    char = cres.characteristic
    loop = asyncio.get_running_loop()

    async def on_read(sender, args):
        d = args.get_deferral()
        req = await args.get_request_async()
        req.respond_with_value(buf(state["value"]))
        d.complete()

    async def on_write(sender, args):
        d = args.get_deferral()
        req = await args.get_request_async()
        try:
            data = bytes(DataReader.from_buffer(req.value).read_byte() for _ in range(req.value.length))
            if req.option == GattWriteOption.WRITE_WITH_RESPONSE:
                req.respond()
            if data and data[0] == 1:
                await handle_toggle(loop)
        finally:
            d.complete()

    char.add_read_requested(lambda s, a: asyncio.run_coroutine_threadsafe(on_read(s, a), loop))
    char.add_write_requested(lambda s, a: asyncio.run_coroutine_threadsafe(on_write(s, a), loop))

    adv = GattServiceProviderAdvertisingParameters()
    adv.is_connectable = True
    adv.is_discoverable = True
    provider.start_advertising(adv)
    print("Advertising over Bluetooth LE. Open Mic Guard on your phone.")

    asyncio.create_task(teams_listener())
    asyncio.create_task(fallback_poller())

    while True:
        await changed.wait()
        changed.clear()
        v = await loop.run_in_executor(None, current_value)
        if v != state["value"]:
            state["value"] = v
            print({0: "no call", 1: "LIVE (green)", 2: "MUTED (red)"}[v])
            try:
                await char.notify_value_async(buf(v))
            except Exception as e:
                print("notify failed:", e)


if __name__ == "__main__":
    asyncio.run(main())
