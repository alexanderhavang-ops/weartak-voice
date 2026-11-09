#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
sdk_commit=86bdc8b04797c2cf6caa30e78e796abac1c2a330
if [[ ! -d .deps/sdk/.git ]]; then
    mkdir -p .deps
    git clone https://github.com/aegorsuch/weartak-plugin-sdk.git .deps/sdk
fi
if [[ -n $(git -C .deps/sdk status --porcelain) ]]; then
    echo "SDK checkout has local changes; refusing to replace it" >&2
    exit 1
fi
git -C .deps/sdk checkout --detach "$sdk_commit"
test "$(git -C .deps/sdk rev-parse HEAD)" = "$sdk_commit"
