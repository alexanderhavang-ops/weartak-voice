#!/usr/bin/env python3
"""Run once on the maintainer's Mac with GitHub CLI and a JDK installed."""
import base64
import getpass
import json
import os
from pathlib import Path
import subprocess
import sys

REPO = "alexanderhavang-ops/weartak-voice"
ENV = "voice-release"
API = f"repos/{REPO}/environments/{ENV}"
SECRET = "VOICE_RELEASE_SIGNING"


def gh(*args, data=None):
    return subprocess.run(["gh", *args], input=data, text=True, check=True,
                          stdout=subprocess.PIPE).stdout


def setup():
    gh("auth", "status", "--hostname", "github.com")
    envs = json.loads(gh("api", "--paginate", "--slurp", f"repos/{REPO}/environments?per_page=100"))
    existing = next((e for page in envs for e in page["environments"] if e["name"] == ENV), None)
    if existing is None:
        gh("api", "--method", "PUT", API, "--input", "-", data=json.dumps({
            "deployment_branch_policy": {"protected_branches": False, "custom_branch_policies": True}}))
    policy = json.loads(gh("api", API))["deployment_branch_policy"] or {}
    if not policy.get("custom_branch_policies"):
        sys.exit("In GitHub Settings > Environments > voice-release, restrict deployment to the main branch.")
    pages = json.loads(gh("api", "--paginate", "--slurp", API + "/deployment-branch-policies?per_page=100"))
    rules = [(r["name"], r["type"]) for page in pages for r in page["branch_policies"]]
    if not rules:
        gh("api", "-X", "POST", API + "/deployment-branch-policies", "-f", "name=main", "-f", "type=branch")
    elif set(rules) != {("main", "branch")}:
        sys.exit("Restrict voice-release to the main branch before provisioning its signing key.")
    names = gh("secret", "list", "-R", REPO, "--env", ENV, "--json", "name")
    if any(s["name"] == SECRET for s in json.loads(names)):
        sys.exit("Voice release signing is already configured. The existing key was preserved.")

    os.umask(0o077)
    directory = Path.home() / ".weartak-signing"
    directory.mkdir(mode=0o700, exist_ok=True)
    store = directory / "voice-release.p12"
    password = getpass.getpass("Voice keystore password (save in LastPass): ")
    if not password or len(password) < 12 or any(c in password for c in "\r\n"):
        sys.exit("Use a password of at least 12 characters, on one line.")
    env = dict(os.environ, VOICE_STORE_PASS=password)
    if not store.exists():
        if password != getpass.getpass("Confirm keystore password: "):
            sys.exit("Passwords differ.")
        subprocess.run(["keytool", "-genkeypair", "-alias", "weartak-voice", "-keyalg", "RSA",
                        "-keysize", "3072", "-validity", "10000", "-dname", "CN=WearTAK Voice",
                        "-storetype", "PKCS12", "-keystore", str(store), "-storepass:env", "VOICE_STORE_PASS"],
                       env=env, check=True)
    subprocess.run(["keytool", "-list", "-alias", "weartak-voice", "-keystore", str(store),
                    "-storepass:env", "VOICE_STORE_PASS"], env=env, check=True)
    gh("secret", "set", SECRET, "-R", REPO, "--env", ENV,
       data=json.dumps({"password": password, "p12": base64.b64encode(store.read_bytes()).decode()}))
    print(f"Configured {REPO} / {ENV}. Back up {store} and its password in LastPass.")
    print("After the workflow reaches main, select Actions > Sign WearTAK Voice plugin > Run workflow.")


if __name__ == "__main__":
    setup()
