#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p vendor app/src/main/assets/speech
download() {
    local url="$1" path="$2" sha="$3"
    if test -f "$path" && echo "$sha  $path" | sha256sum -c --status; then return; fi
    curl -fsSL --retry 2 "$url" -o "$path.part"
    echo "$sha  $path.part" | sha256sum -c -
    mv "$path.part" "$path"
}
download https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.3/sherpa-onnx-2.0.3.aar vendor/sherpa-onnx.aar 4c766e2e74c9d33d595338c21fe3b305c73ba45d47e96a235675c33142a2ec50
base=https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23/resolve/main
download "$base/encoder-epoch-99-avg-1.int8.onnx" app/src/main/assets/speech/encoder.onnx 1c556ea57cec304e55ec4b72e52c1cc098bb01476ed7d90f3de939fe126487b1
download "$base/decoder-epoch-99-avg-1.int8.onnx" app/src/main/assets/speech/decoder.onnx 22f123bb8cba9b38974b3df18a3f45e7081f4985ebb2e075d9f21f618c468bbf
download "$base/joiner-epoch-99-avg-1.int8.onnx" app/src/main/assets/speech/joiner.onnx a7cf9d82757bdcf786059454495a9ca95e4bd7347f72473fc08d794475c36169
download "$base/tokens.txt" app/src/main/assets/speech/tokens.txt 8b294db9045d6e5f94647f4c1eec1af4da143a75053c399611444b378ff966ac
