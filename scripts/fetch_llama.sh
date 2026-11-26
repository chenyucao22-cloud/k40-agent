#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
destination="$root/vendor/llama.cpp-b5124"
if [[ -f "$destination/.agent-verified" ]]; then exit 0; fi
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
curl -fsSL --retry 2 https://codeload.github.com/ggml-org/llama.cpp/tar.gz/refs/tags/b5124 -o "$tmp/llama.tgz"
echo "5d7ac94bef0aff2533d205398d54954d0d6ea79077de7898b9210ea344c63ecf  $tmp/llama.tgz" | sha256sum -c -
tar -xzf "$tmp/llama.tgz" -C "$tmp"
mkdir -p "$root/vendor"
mv "$tmp/llama.cpp-b5124" "$destination"
touch "$destination/.agent-verified"
