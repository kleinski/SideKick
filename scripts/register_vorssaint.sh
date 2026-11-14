#!/usr/bin/env python3 
"""
Registers SideKick quick actions (Takeover, Start/Stop, Settings) into
Vorssaint's Command Bar Links (defaults domain `com.vorssaint.utils`, key `commandBarLinks`).
"""
import json
import subprocess
import uuid

DOMAIN = "com.vorssaint.utils"
KEY = "commandBarLinks"

SIDEKICK_LINKS = [
    {
        "id": "51DE41C4-0001-4000-8000-000000000001",
        "name": "SideKick: Connect Tablet Here (Takeover)",
        "kind": "link",
        "destination": "sidekick://takeover",
        "runsDirectly": True,
        "runsWithoutArgument": True,
    },
    {
        "id": "51DE41C4-0002-4000-8000-000000000002",
        "name": "SideKick: Toggle Streaming (Start / Stop)",
        "kind": "link",
        "destination": "sidekick://toggle",
        "runsDirectly": True,
        "runsWithoutArgument": True,
    },
    {
        "id": "51DE41C4-0003-4000-8000-000000000003",
        "name": "SideKick: Open Settings",
        "kind": "link",
        "destination": "sidekick://settings",
        "runsDirectly": True,
        "runsWithoutArgument": True,
    },
]

def read_existing_links():
    try:
        out = subprocess.check_output(
            ["defaults", "export", DOMAIN, "-"], stderr=subprocess.DEVNULL
        )
        import plistlib
        plist = plistlib.loads(out)
        raw = plist.get(KEY)
        if isinstance(raw, bytes):
            return json.loads(raw.decode("utf-8"))
        elif isinstance(raw, str):
            return json.loads(raw)
    except Exception:
        pass
    return []

def write_links(links):
    payload_bytes = json.dumps(links, ensure_ascii=False).encode("utf-8")
    hex_str = payload_bytes.hex()
    subprocess.check_call(["defaults", "write", DOMAIN, KEY, "-data", hex_str])

def is_vorssaint_running():
    res = subprocess.run(["pgrep", "-x", "Vorssaint"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return res.returncode == 0

def main():
    was_running = is_vorssaint_running()
    if was_running:
        print("⏸️  Stopping Vorssaint briefly so it doesn't overwrite UserDefaults...")
        subprocess.run(["killall", "Vorssaint"], stderr=subprocess.DEVNULL)
        import time
        time.sleep(0.8)

    existing = read_existing_links()
    sidekick_dests = {item["destination"] for item in SIDEKICK_LINKS}
    filtered = [
        item for item in existing
        if isinstance(item, dict)
        and item.get("destination") not in sidekick_dests
        and not str(item.get("destination", "")).startswith("sidescreen://")
    ]
    merged = SIDEKICK_LINKS + filtered
    write_links(merged)
    subprocess.run(["killall", "cfprefsd"], stderr=subprocess.DEVNULL)
    print(f"✅ Registered {len(SIDEKICK_LINKS)} SideKick actions in Vorssaint Command Bar ({DOMAIN} -> {KEY}).")
    for item in SIDEKICK_LINKS:
        print(f"   • {item['name']}  →  {item['destination']}")

    if was_running:
        print("▶️  Restarting Vorssaint...")
        subprocess.run(["open", "-a", "Vorssaint"], stderr=subprocess.DEVNULL)

if __name__ == "__main__":
    main()
