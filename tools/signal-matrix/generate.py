# SPDX-License-Identifier: AGPL-3.0-only
"""Generate the Fleet Telemetry vs BLE signal matrix.

Cloud data comes from Tesla's Fleet Telemetry protos (teslamotors/fleet-telemetry,
vendored and pinned under tools/signal-matrix/upstream/fleet-telemetry/). BLE data
comes from the protos pinned in core/src/main/proto (teslamotors/vehicle-command).

The curated cross-map lives in mapping.json. This script validates it against both
proto catalogs and emits docs/reference/fleet-telemetry-vs-ble.md.

Usage:
  python tools/signal-matrix/generate.py            # regenerate the doc (offline)
  python tools/signal-matrix/generate.py --check    # fail if the doc is stale
  python tools/signal-matrix/generate.py --refresh  # update vendored fleet protos from upstream
  python tools/signal-matrix/generate.py --status   # print pinned vs upstream SHAs
  python tools/signal-matrix/generate.py --dump     # list parsed message fields
"""

import argparse
import difflib
import json
import os
import re
import sys
import urllib.request
from pathlib import Path

TOOL_DIR = Path(__file__).resolve().parent
REPO = TOOL_DIR.parents[1]
DOC = REPO / "docs" / "reference" / "fleet-telemetry-vs-ble.md"
MAPPING = TOOL_DIR / "mapping.json"
FT_DIR = TOOL_DIR / "upstream" / "fleet-telemetry"
FT_PIN = FT_DIR / "FLEET_TELEMETRY_COMMIT"
TESLA_COMMIT = REPO / "core" / "src" / "main" / "proto" / "TESLA_COMMIT"

FT_API = "https://api.github.com/repos/teslamotors/fleet-telemetry"
VC_API = "https://api.github.com/repos/teslamotors/vehicle-command"
VC_PROTO_PATH = "pkg/protocol/protobuf"

# ---------------------------------------------------------------------------
# Proto parsing
# ---------------------------------------------------------------------------

TOKEN = re.compile(
    r"(message|enum|oneof)\s+(\w+)\s*\{|\{|\}|([\w.]+)\s+(\w+)\s*=\s*(\d+)\s*;"
)


def strip_comment(line, in_block):
    """Remove // comments, honouring /* ... */ blocks (rare here)."""
    out = []
    i = 0
    while i < len(line):
        if in_block:
            end = line.find("*/", i)
            if end == -1:
                return "".join(out), True
            i = end + 2
            in_block = False
        else:
            start = line.find("/*", i)
            slash = line.find("//", i)
            if slash != -1 and (start == -1 or slash < start):
                out.append(line[i:slash])
                return "".join(out), False
            if start != -1:
                out.append(line[i:start])
                i = start + 2
                in_block = True
            else:
                out.append(line[i:])
                break
    return "".join(out), in_block


def parse_messages(paths):
    """Return {full_name: {"file": name, "fields": [{name,type,repeated}]}}.

    full_name is dotted for nested messages; cataloguing uses top-level names.
    """
    messages = {}
    for path in paths:
        path = Path(path)
        text = path.read_text(encoding="utf-8")
        code_lines = []
        in_block = False
        for line in text.splitlines():
            stripped, in_block = strip_comment(line, in_block)
            code_lines.append(stripped)
        code = "\n".join(code_lines)

        stack = []  # (kind, name)
        for m in TOKEN.finditer(code):
            if m.group(1):
                kind, name = m.group(1), m.group(2)
                stack.append((kind, name))
                if kind == "message":
                    full = ".".join(n for k, n in stack if k == "message")
                    messages.setdefault(full, {"file": path.name, "fields": []})
            elif m.group(0) == "{":
                stack.append(("block", None))
            elif m.group(0) == "}":
                if stack:
                    stack.pop()
            else:
                ftype, fname = m.group(3), m.group(4)
                if ftype in ("option", "reserved", "import", "syntax", "package"):
                    continue
                for kind, name in reversed(stack):
                    if kind == "message":
                        full = ".".join(n for k, n in stack if k == "message")
                        messages.setdefault(full, {"file": path.name, "fields": []})
                        messages[full]["fields"].append(
                            {"name": fname, "type": ftype, "repeated": ftype == "repeated"}
                        )
                        break
    return messages


def parse_field_enum(path):
    """Return [{name, number, comment}] for `enum Field` in vehicle_data.proto."""
    lines = Path(path).read_text(encoding="utf-8").splitlines()
    fields, in_enum = [], False
    entry = re.compile(r"^\s*(\w+)\s*=\s*(\d+)\s*;\s*(?://\s*(.*))?$")
    for line in lines:
        if re.match(r"^enum Field\s*\{", line):
            in_enum = True
            continue
        if in_enum and line.strip().startswith("}"):
            break
        if not in_enum:
            continue
        m = entry.match(line)
        if m and m.group(1) != "Unknown":
            fields.append(
                {"name": m.group(1), "number": int(m.group(2)), "comment": (m.group(3) or "").strip()}
            )
    return fields


# ---------------------------------------------------------------------------
# Upstream refresh / status
# ---------------------------------------------------------------------------


def gh_json(url):
    req = urllib.request.Request(
        url, headers={"User-Agent": "TeslaBatteryBLE-signal-matrix", "Accept": "application/vnd.github+json"}
    )
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=60) as response:
        return json.load(response)


def latest_path_commit(api, path):
    data = gh_json(f"{api}/commits?path={path}&per_page=1")
    return data[0]["sha"] if data else None


def download(url, dest):
    req = urllib.request.Request(url, headers={"User-Agent": "TeslaBatteryBLE-signal-matrix"})
    with urllib.request.urlopen(req, timeout=120) as response:
        dest.write_bytes(response.read())


def refresh_fleet():
    sha = latest_path_commit(FT_API, "protos")
    listing = gh_json(f"{FT_API}/contents/protos?ref={sha}")
    names = sorted(item["name"] for item in listing if item["name"].endswith(".proto"))
    FT_DIR.mkdir(parents=True, exist_ok=True)
    for name in names:
        download(
            f"https://raw.githubusercontent.com/teslamotors/fleet-telemetry/{sha}/protos/{name}",
            FT_DIR / name,
        )
    FT_PIN.write_text(sha, encoding="utf-8")
    return sha, names


def status():
    ft_pinned = FT_PIN.read_text(encoding="utf-8").strip()
    ft_latest = latest_path_commit(FT_API, "protos")
    vc_pinned = TESLA_COMMIT.read_text(encoding="utf-8").strip()
    vc_latest = latest_path_commit(VC_API, VC_PROTO_PATH)
    print(f"Fleet Telemetry: pinned `{ft_pinned}` · upstream latest `{ft_latest}`"
          f" ({'up to date' if ft_pinned == ft_latest else 'DRIFT'})")
    print(f"vehicle-command: pinned `{vc_pinned}` · upstream latest touching {VC_PROTO_PATH} "
          f"`{vc_latest}` ({'up to date' if vc_pinned == vc_latest else 'DRIFT - bump core protos deliberately'})")
    return 0 if ft_pinned == ft_latest else 1


# ---------------------------------------------------------------------------
# Document generation
# ---------------------------------------------------------------------------


def field_ref(message, field):
    return f"{message}.{field}"


def render(mapping, cloud_fields, ble_messages):
    ft_pin = FT_PIN.read_text(encoding="utf-8").strip()
    vc_pin = TESLA_COMMIT.read_text(encoding="utf-8").strip()
    cloud_names = {f["name"] for f in cloud_fields}

    errors = []
    reviewed_cloud = set()
    ble_refs = set()
    for group in mapping["cross_map"]:
        for row in group["rows"]:
            cloud = row["cloud"].split(".")[-1]
            if cloud not in cloud_names:
                errors.append(f"mapping: cloud signal `{cloud}` not in Fleet Telemetry Field enum")
            reviewed_cloud.add(cloud)
            if row.get("ble"):
                ref = row["ble"]
                message, _, field = ref.rpartition(".")
                if message not in ble_messages or field not in {f["name"] for f in ble_messages[message]["fields"]}:
                    errors.append(f"mapping: BLE ref `{ref}` not found in pinned protos")
                ble_refs.add(ref)
    if errors:
        for error in errors:
            print(f"ERROR {error}", file=sys.stderr)
        raise SystemExit(2)

    mapped_count = sum(1 for g in mapping["cross_map"] for r in g["rows"] if r.get("ble"))
    cloud_only_count = sum(1 for g in mapping["cross_map"] for r in g["rows"] if not r.get("ble"))

    ignore_prefixes = tuple(mapping.get("cloud_ignore_prefixes", []))
    ignore_comments = tuple(mapping.get("cloud_ignore_comment_contains", []))
    ignored = {
        f["name"]
        for f in cloud_fields
        if f["name"].startswith(ignore_prefixes)
        or any(c in f["comment"] for c in ignore_comments)
    }
    unmapped = [
        f for f in cloud_fields if f["name"] not in reviewed_cloud and f["name"] not in ignored
    ]

    catalog_messages = mapping["ble_catalog_messages"]
    ble_field_total = sum(
        len(ble_messages[m]["fields"]) for m in catalog_messages if m in ble_messages
    )
    ble_only = {}
    for message in catalog_messages:
        fields = ble_messages.get(message, {}).get("fields", [])
        rest = [f["name"] for f in fields if field_ref(message, f["name"]) not in ble_refs]
        if rest:
            ble_only[message] = rest

    out = []
    w = out.append
    w("# Fleet Telemetry vs BLE — signal matrix")
    w("")
    w("> Generated by `tools/signal-matrix/generate.py` from pinned protos. "
      "Do not edit by hand — update `tools/signal-matrix/mapping.json` and regenerate.")
    w(f"> Pins: Fleet Telemetry [`{ft_pin[:12]}`](https://github.com/teslamotors/fleet-telemetry/tree/{ft_pin}) · "
      f"vehicle-command BLE protos [`{vc_pin[:12]}`](https://github.com/teslamotors/vehicle-command/tree/{vc_pin})")
    w("")
    w("**Scope.** Cloud side: signals defined in Tesla's Fleet Telemetry protos "
      "(`teslamotors/fleet-telemetry/protos`, the data streamed to a telemetry server and the "
      "same vehicle data the Fleet API exposes as JSON). BLE side: messages in Tesla's "
      "vehicle-command protobufs as pinned in `core/src/main/proto`, which is what this app can "
      "read locally without any cloud connection.")
    w("")
    for note in mapping.get("summary_notes", []):
        w(note)
        w("")
    w("## Summary")
    w("")
    w("| Metric | Count |")
    w("|---|---|")
    w(f"| Fleet Telemetry signals (excluding deprecated/experimental) | "
      f"{len(cloud_fields) - len(ignored)} |")
    w(f"| Mapped to a BLE equivalent | {mapped_count} |")
    w(f"| Cloud-only (no BLE equivalent, reviewed) | {cloud_only_count} |")
    w(f"| Unreviewed upstream signals (drift watch) | {len(unmapped)} |")
    w(f"| BLE fields catalogued | {ble_field_total} |")
    w("")

    for group in mapping["cross_map"]:
        w(f"## {group['title']}")
        w("")
        if group.get("note"):
            w(group["note"])
            w("")
        w("| Cloud signal | BLE equivalent | Notes |")
        w("|---|---|---|")
        for row in group["rows"]:
            ble = f"`{row['ble']}`" if row.get("ble") else "—"
            w(f"| `{row['cloud']}` | {ble} | {row.get('note', '')} |")
        w("")

    w("## Unreviewed upstream signals (drift watch)")
    w("")
    if unmapped:
        w("These Fleet Telemetry signals are neither mapped nor explicitly ignored in "
          "`mapping.json`. If Tesla adds a battery-relevant signal it lands here first — "
          "review it and either map it, add it to a cloud-only group, or add it to the "
          "ignore list.")
        w("")
        w("| Signal | # | Upstream comment |")
        w("|---|---|---|")
        for f in sorted(unmapped, key=lambda x: x["number"]):
            w(f"| `{f['name']}` | {f['number']} | {f['comment']} |")
    else:
        w("None — every signal is either mapped or explicitly ignored.")
    w("")

    w("## BLE fields with no cloud mapping")
    w("")
    w(f"<details><summary>{sum(len(v) for v in ble_only.values())} BLE fields not referenced "
      "by the cross-map (usually internal, identity, or semi-specific fields)</summary>")
    w("")
    for message, fields in ble_only.items():
        w(f"**{message}** ({len(fields)}): " + ", ".join(f"`{f}`" for f in fields))
        w("")
    w("</details>")
    w("")

    w("## Commands")
    w("")
    w("| Action | Fleet API (signed command / HTTPS) | BLE | Notes |")
    w("|---|---|---|---|")
    for row in mapping["commands"]:
        w(f"| {row['action']} | {row['fleet']} | {row['ble']} | {row.get('note', '')} |")
    w("")

    w("## Full cloud catalog (Fleet Telemetry `Field` enum)")
    w("")
    w(f"<details><summary>{len(cloud_fields)} signals incl. deprecated/experimental</summary>")
    w("")
    w("| Signal | # | Upstream comment |")
    w("|---|---|---|")
    for f in sorted(cloud_fields, key=lambda x: x["number"]):
        w(f"| `{f['name']}` | {f['number']} | {f['comment']} |")
    w("</details>")
    w("")

    w("## Full BLE catalog (pinned state messages)")
    w("")
    for message in catalog_messages:
        if message not in ble_messages:
            continue
        fields = ble_messages[message]["fields"]
        w(f"<details><summary>{message} — {len(fields)} fields</summary>")
        w("")
        w("| Field | Type |")
        w("|---|---|")
        for f in fields:
            w(f"| `{f['name']}` | {f['type']} |")
        w("</details>")
        w("")

    w("## How this stays current")
    w("")
    w("- A scheduled workflow (`.github/workflows/signal-matrix.yml`) refreshes the vendored "
      "Fleet Telemetry protos weekly, regenerates this document, and opens a PR when anything "
      "changes.")
    w("- New upstream signals appear under *Unreviewed upstream signals* until mapping.json is "
      "updated — that is the review queue.")
    w("- BLE protos are **not** auto-bumped: `core/src/main/proto` is pinned to `TESLA_COMMIT` "
      "and upgrades are deliberate, tested changes. The workflow reports when upstream moves "
      "ahead of the pin.")
    w("- Regenerate locally: `python tools/signal-matrix/generate.py`; "
      "verify in CI: `--check`.")
    w("")
    return "\n".join(out) + "\n"


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="fail if the committed doc is stale")
    parser.add_argument("--refresh", action="store_true", help="update vendored fleet protos from upstream")
    parser.add_argument("--status", action="store_true", help="print pinned vs upstream SHAs")
    parser.add_argument("--dump", action="store_true", help="dump parsed message fields")
    args = parser.parse_args()

    if args.refresh:
        sha, names = refresh_fleet()
        print(f"refreshed fleet-telemetry protos to {sha} ({len(names)} files)")
    if args.status:
        return status()

    mapping = json.loads(MAPPING.read_text(encoding="utf-8"))
    cloud_fields = parse_field_enum(FT_DIR / "vehicle_data.proto")
    ble_files = [REPO / p for p in mapping["ble_files"]]
    ble_messages = parse_messages(ble_files)

    if args.dump:
        for message in mapping["ble_catalog_messages"]:
            fields = ble_messages.get(message, {}).get("fields", [])
            print(f"== {message} ({len(fields)})")
            for f in fields:
                print(f"  {f['name']} : {f['type']}")
        return 0

    doc = render(mapping, cloud_fields, ble_messages)
    if args.check:
        current = DOC.read_text(encoding="utf-8") if DOC.exists() else ""
        if current == doc:
            print("signal matrix is up to date")
            return 0
        diff = list(difflib.unified_diff(current.splitlines(), doc.splitlines(), lineterm=""))
        print("\n".join(diff[:120]), file=sys.stderr)
        print(f"signal matrix is stale ({sum(1 for _ in diff)} diff lines); run generate.py", file=sys.stderr)
        return 1

    DOC.parent.mkdir(parents=True, exist_ok=True)
    DOC.write_text(doc, encoding="utf-8", newline="\n")
    print(f"wrote {DOC.relative_to(REPO)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
