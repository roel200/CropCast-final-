"""Send simulated CropCast sensor readings to Firebase Realtime Database.

Examples:
    python scripts/simulate_firebase_sensor.py --crop Alugbati --interval 5
    python scripts/simulate_firebase_sensor.py --crop Alugbati \
        --backfill-month 2026-08 --count 8 --interval 1
    python scripts/simulate_firebase_sensor.py --crop Potato --dry-run

The simulator updates only one device's current reading, monthly history, and
status. It never performs a root-level import or deletes existing data.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen

SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parent
sys.path.insert(0, str(SCRIPT_DIR))

from seed_crop_test_data import (  # noqa: E402
    SENSOR_TO_PROFILE_RANGE,
    distinctive_values,
    make_reading,
)


DEFAULT_DEVICE_ID = "esp32-field-01"
DEFAULT_FIREBASE_URL = "https://cropcast-84fb5-default-rtdb.asia-southeast1.firebasedatabase.app"
AUTH_URL = "https://identitytoolkit.googleapis.com/v1/accounts"


def load_firebase_config() -> tuple[str, str]:
    config_path = PROJECT_ROOT / "app" / "google-services.json"
    if not config_path.exists():
        return DEFAULT_FIREBASE_URL, ""
    config = json.loads(config_path.read_text(encoding="utf-8"))
    project = config.get("project_info", {})
    api_keys = config.get("client", [{}])[0].get("api_key", [{}])
    api_key = api_keys[0].get("current_key", "") if api_keys else ""
    return project.get("firebase_url", DEFAULT_FIREBASE_URL), api_key


def http_json(url: str, payload: dict) -> dict:
    body = json.dumps(payload).encode("utf-8")
    request = Request(
        url,
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urlopen(request, timeout=20) as response:
        return json.loads(response.read().decode("utf-8"))


def authenticate(args: argparse.Namespace, api_key: str) -> str:
    if args.no_auth:
        return ""
    if args.id_token:
        return args.id_token
    if not api_key:
        raise RuntimeError(
            "No Firebase API key found. Pass --id-token or --no-auth explicitly."
        )

    if bool(args.email) != bool(args.password):
        raise RuntimeError("Pass both --email and --password, or neither.")

    if args.email:
        endpoint = f"{AUTH_URL}:signInWithPassword?key={quote(api_key, safe='')}"
        payload = {"email": args.email, "password": args.password, "returnSecureToken": True}
        auth_type = "email/password"
    else:
        endpoint = f"{AUTH_URL}:signUp?key={quote(api_key, safe='')}"
        payload = {"returnSecureToken": True}
        auth_type = "anonymous"

    try:
        result = http_json(endpoint, payload)
    except HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"Firebase {auth_type} authentication failed: {detail}") from error
    except URLError as error:
        raise RuntimeError(f"Could not reach Firebase authentication: {error.reason}") from error

    token = result.get("idToken")
    if not token:
        raise RuntimeError(f"Firebase {auth_type} authentication returned no ID token.")
    return token


def firebase_url(base_url: str, path: list[str], token: str) -> str:
    encoded_path = "/".join(quote(part, safe="") for part in path)
    query = f"?{urlencode({'auth': token})}" if token else ""
    return f"{base_url.rstrip('/')}/{encoded_path}.json{query}"


def put_json(base_url: str, path: list[str], payload: dict, token: str) -> None:
    body = json.dumps(payload).encode("utf-8")
    request = Request(
        firebase_url(base_url, path, token),
        data=body,
        headers={"Content-Type": "application/json"},
        method="PUT",
    )
    try:
        with urlopen(request, timeout=20) as response:
            response.read()
    except HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"Firebase write failed for /{'/'.join(path)}: {detail}") from error
    except URLError as error:
        raise RuntimeError(f"Could not reach Firebase: {error.reason}") from error


def parse_backfill_month(value: str) -> datetime:
    try:
        return datetime.strptime(value, "%Y-%m").replace(
            day=1, hour=9, minute=0, second=0, microsecond=0, tzinfo=timezone.utc
        )
    except ValueError as error:
        raise argparse.ArgumentTypeError("Use YYYY-MM for --backfill-month") from error


def format_reading(reading: dict) -> str:
    return (
        f"temp={reading['temperature']:.1f}°C, humidity={reading['humidity']:.1f}%, "
        f"moisture={reading['soilMoisture']:.1f}%, pH={reading['soilPh']:.2f}, "
        f"NPK={reading['nitrogen']:.1f}/{reading['phosphorus']:.1f}/{reading['potassium']:.1f}"
    )


def build_parser(crop_names: list[str], default_url: str, default_api_key: str) -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Continuously send simulated sensor readings to CropCast Firebase."
    )
    parser.add_argument("--crop", choices=crop_names, default="Alugbati")
    parser.add_argument("--device-id", default=DEFAULT_DEVICE_ID)
    parser.add_argument("--interval", type=float, default=5.0, help="Seconds between writes (default: 5)")
    parser.add_argument(
        "--count",
        type=int,
        default=0,
        help="Number of readings; 0 runs until Ctrl+C (default: 0)",
    )
    parser.add_argument(
        "--backfill-month",
        type=parse_backfill_month,
        help="Write monthly samples into a closed month such as 2026-08",
    )
    parser.add_argument("--firebase-url", default=default_url)
    parser.add_argument("--id-token", help="Existing Firebase ID token")
    parser.add_argument("--email", help="Firebase email/password account")
    parser.add_argument("--password", help="Firebase email/password account password")
    parser.add_argument(
        "--no-auth",
        action="store_true",
        help="Do not authenticate; only works when Firebase rules allow public writes",
    )
    parser.add_argument("--dry-run", action="store_true", help="Print one reading without writing to Firebase")
    parser.set_defaults(api_key=default_api_key)
    return parser


def main() -> int:
    firebase_url_default, api_key = load_firebase_config()
    profiles_path = PROJECT_ROOT / "data" / "processed" / "crop_requirements_selected.json"
    profiles = json.loads(profiles_path.read_text(encoding="utf-8"))["crops"]
    profiles_by_name = {profile["name"]: profile for profile in profiles}
    parser = build_parser(list(profiles_by_name), firebase_url_default, api_key)
    args = parser.parse_args()

    if args.count < 0:
        parser.error("--count cannot be negative")
    if args.interval < 0.2:
        parser.error("--interval must be at least 0.2 seconds")
    if not args.firebase_url.startswith("https://"):
        parser.error("--firebase-url must use HTTPS")

    target = profiles_by_name[args.crop]
    base_values = distinctive_values(target, profiles)
    token = ""

    try:
        if not args.dry_run:
            token = authenticate(args, args.api_key)
            auth_mode = "no-auth" if args.no_auth else ("ID token" if args.id_token else "anonymous/email")
            print(f"Connected to {args.firebase_url} using {auth_mode}.")
        else:
            print("Dry run: Firebase will not be changed.")

        if args.backfill_month:
            history_time = args.backfill_month
            print(f"Monthly history target: {history_time.strftime('%Y-%m')}.")
        else:
            history_time = None

        sequence = 0
        while args.count == 0 or sequence < args.count:
            now = datetime.now(timezone.utc).replace(microsecond=0)
            recorded_at = history_time + timedelta(hours=sequence) if history_time else now
            history_reading = make_reading(base_values, target, recorded_at, sequence % 8)
            current_reading = dict(history_reading)
            current_reading["timestamp"] = int(now.timestamp() * 1000)

            if args.dry_run:
                print(f"[{now.isoformat()}] {args.crop}: {format_reading(current_reading)}")
                return 0

            month_key = recorded_at.strftime("%Y-%m")
            reading_key = recorded_at.strftime("%Y-%m-%d_%H-%M-%S")
            root = ["devices", args.device_id]
            put_json(args.firebase_url, root + ["readings", "current"], current_reading, token)
            put_json(
                args.firebase_url,
                root + ["readings", "monthly", month_key, reading_key],
                history_reading,
                token,
            )
            put_json(
                args.firebase_url,
                root + ["status"],
                {
                    "online": True,
                    "lastSeen": int(now.timestamp() * 1000),
                    "firmware": "firebase-simulator",
                    "sensors": ["ESP32", "DHT11", "NPK", "pH", "MOISTURE", "LUX"],
                },
                token,
            )
            print(
                f"[{now.strftime('%H:%M:%S')}] sent {args.crop} -> "
                f"current + {month_key}/{reading_key} ({format_reading(current_reading)})"
            )
            sequence += 1
            if args.count == 0 or sequence < args.count:
                time.sleep(args.interval)
    except KeyboardInterrupt:
        print("\nStopped. Marking the simulated device offline.")
        if token and not args.dry_run:
            try:
                put_json(
                    args.firebase_url,
                    ["devices", args.device_id, "status"],
                    {
                        "online": False,
                        "lastSeen": int(datetime.now(timezone.utc).timestamp() * 1000),
                        "firmware": "firebase-simulator",
                        "sensors": ["ESP32", "DHT11", "NPK", "pH", "MOISTURE", "LUX"],
                    },
                    token,
                )
            except RuntimeError as error:
                print(f"Warning: could not mark the device offline: {error}")
        return 0
    except (OSError, RuntimeError, ValueError, json.JSONDecodeError) as error:
        print(f"Error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
