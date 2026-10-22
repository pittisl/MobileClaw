#!/bin/sh
# Fetch the MobileCLIP-S0 ONNX weights (fp32 vision, int8 text) and tokenizer
# into app/src/main/assets/clip/, which is gitignored. The optional weights total
# about 88 MB and are packaged into an APK only when present during its build.
# Files already present with the expected size are left alone.
# Source: https://huggingface.co/Xenova/mobileclip_s0 (Apple's MobileCLIP;
# Apple license in LICENSE alongside; ONNX export by Xenova).
set -e
DEST="$(dirname "$0")/../app/src/main/assets/clip"
BASE="https://huggingface.co/Xenova/mobileclip_s0/resolve/main"
mkdir -p "$DEST"
fetch() { # remote-path local-name expected-bytes
  if [ -f "$DEST/$2" ] && [ "$(wc -c < "$DEST/$2")" -eq "$3" ]; then return; fi
  echo "fetching $2"
  curl -fL --retry 3 -o "$DEST/$2.part" "$BASE/$1" && mv "$DEST/$2.part" "$DEST/$2"
  [ "$(wc -c < "$DEST/$2")" -eq "$3" ] || { echo "$2: unexpected size" >&2; exit 1; }
}
fetch onnx/vision_model.onnx      vision.onnx 45543630
fetch onnx/text_model_int8.onnx   text.onnx   42799230
fetch tokenizer.json              tokenizer.json 2224081
fetch LICENSE                     LICENSE     2671
