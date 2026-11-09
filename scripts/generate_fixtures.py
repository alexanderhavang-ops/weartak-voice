#!/usr/bin/env python3
"""Generate disposable, credential-free interoperability fixtures from the real TAKS writers."""
from pathlib import Path
import json
import sys
import zipfile
from unittest.mock import patch

import argparse
import subprocess
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--taks-source", type=Path, required=True)
args = parser.parse_args()
root = args.taks_source.resolve()
revision = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
sys.path[:0] = [str(root / "takctl"), str(root / "martine")]
from takctl.onboarding.vx import write_vx_mission_zip
from martine.tools.voice_onboarding_package import _render_voice_package

out = Path(__file__).resolve().parents[1] / "core/src/test/resources/fixtures"
out.mkdir(parents=True, exist_ok=True)
write_vx_mission_zip(out / "takctl.zip", package_name="WATCH_test", mission_name="Samband-test",
    host="voice.example.org", channels=[
        {"name": "GruppL-Ö", "server_channel_id": 17},
        {"name": "Command", "server_channel_id": 93},
    ])
with patch("martine.tools.voice_onboarding_package._read_mumble_channel_ids", return_value={"GruppL-Ö": 17, "Command": 93}):
    result = _render_voice_package(target_callsign="WATCH", target_uid="WATCH-TEST",
        sender_callsign="Martine", sender_uid="MARTINE-TEST", node_name="test",
        mission_label="Samband-test", fqdn="voice.example.org", voice_port=64738,
        channels=["GruppL-Ö", "Command"], server_password="", state_dir=out / ".generation")
(out / "martine.zip").write_bytes(Path(result["package_path"]).read_bytes())
for kind in ("takctl", "martine"):
    with zipfile.ZipFile(out / f"{kind}.zip") as source:
        names = source.namelist()
        json_name = next(n for n in names if n != "MANIFEST/manifest.xml" and not n.endswith("_proto"))
        proto_name = next(n for n in names if n.endswith("_proto"))
        (out / f"{kind}.json").write_bytes(source.read(json_name))
        (out / f"{kind}.proto").write_bytes(source.read(proto_name))
        # Test binary-only packages with the normal manifest and only the binary content reference.
        import xml.etree.ElementTree as ET
        manifest = ET.fromstring(source.read("MANIFEST/manifest.xml"))
        contents = manifest.find("Contents")
        for entry in list(contents):
            if entry.get("zipEntry") != proto_name:
                contents.remove(entry)
        with zipfile.ZipFile(out / f"{kind}-binary.zip", "w") as target:
            target.writestr("MANIFEST/manifest.xml", ET.tostring(manifest))
            target.writestr(proto_name, source.read(proto_name))
import shutil
shutil.rmtree(out / ".generation")
(out / "provenance.json").write_text(json.dumps({"source_repository": "alexanderhavang-ops/taks", "source_commit": revision, "writers": ["takctl/takctl/onboarding/vx.py", "martine/martine/tools/voice_onboarding_package.py"], "synthetic": True, "credentials": False}, indent=2) + "\n")
print(f"Generated fixtures in {out}")
