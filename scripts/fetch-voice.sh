#!/usr/bin/env bash
# Fetches the sherpa-onnx Android library and the offline voice models (wake word, speech
# recognition, speech synthesis) used by VoiceService. They are too large for git, so they
# live in git-ignored paths: app/libs/ and app/src/main/assets/voice/.
# Every download is checked against a pinned SHA-256.
#
# Usage: scripts/fetch-voice.sh   (from the repo root; re-running skips what is already there)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE="${SHERPA_BASE_URL:-https://github.com/k2-fsa/sherpa-onnx/releases/download}"
LIBS="$ROOT/app/libs"
ASSETS="$ROOT/app/src/main/assets/voice"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fetch() { # <release path> <sha256> <output file>
  local url="$BASE/$1" out="$3"
  echo "downloading $1"
  curl -fL --retry 3 --max-time 600 -o "$out" "$url"
  echo "$2  $out" | shasum -a 256 -c - >/dev/null || { echo "SHA-256 mismatch: $1" >&2; exit 1; }
}

mkdir -p "$LIBS"
AAR="$LIBS/sherpa-onnx-1.13.8.aar"
if [ ! -f "$AAR" ]; then
  fetch v1.13.8/sherpa-onnx-1.13.8.aar \
    633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96 "$AAR"
fi

# Wake word: Chinese keyword spotter (zipformer, WenetSpeech, 3.3M params).
if [ ! -f "$ASSETS/kws/tokens.txt" ]; then
  K=sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile
  fetch kws-models/$K.tar.bz2 b812a043aef628a6915f89cb9a94e55f8e87e89ff904b516f822d7e0a3e6de2b "$TMP/kws.tar.bz2"
  tar xjf "$TMP/kws.tar.bz2" -C "$TMP"
  mkdir -p "$ASSETS/kws"
  cp "$TMP/$K/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx" "$ASSETS/kws/encoder.onnx"
  cp "$TMP/$K/decoder-epoch-12-avg-2-chunk-16-left-64.onnx" "$ASSETS/kws/decoder.onnx"
  cp "$TMP/$K/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx" "$ASSETS/kws/joiner.onnx"
  cp "$TMP/$K/tokens.txt" "$ASSETS/kws/tokens.txt"
fi
# Wake word "小艾小艾", written as the model's pinyin tokens (initials and tonal finals).
printf 'x iǎo ài x iǎo ài @小艾小艾\n' > "$ASSETS/kws/keywords.txt"

# Speech recognition: streaming zipformer CTC, Chinese, int8.
if [ ! -f "$ASSETS/asr/tokens.txt" ]; then
  A=sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01
  fetch asr-models/$A.tar.bz2 b3b309f7ce4a737195fcc6963ea19b0653a7d3401580af5ae0d3e284cbb71f0b "$TMP/asr.tar.bz2"
  tar xjf "$TMP/asr.tar.bz2" -C "$TMP"
  mkdir -p "$ASSETS/asr"
  cp "$TMP/$A/model.int8.onnx" "$ASSETS/asr/model.onnx"
  cp "$TMP/$A/tokens.txt" "$ASSETS/asr/tokens.txt"
fi

# Speech synthesis: Piper VITS zh_CN xiao_ya, int8.
if [ ! -f "$ASSETS/tts/tokens.txt" ]; then
  T=vits-piper-zh_CN-xiao_ya-medium-int8
  fetch tts-models/$T.tar.bz2 eab027e194e70289233cf12308373611fa4e2e96ef2d97354ef433ab663d831f "$TMP/tts.tar.bz2"
  tar xjf "$TMP/tts.tar.bz2" -C "$TMP"
  mkdir -p "$ASSETS/tts"
  cp "$TMP/$T/zh_CN-xiao_ya-medium.onnx" "$ASSETS/tts/model.onnx"
  cp "$TMP/$T/tokens.txt" "$TMP/$T/lexicon.txt" "$TMP/$T/phone.fst" "$TMP/$T/date.fst" "$TMP/$T/number.fst" "$ASSETS/tts/"
fi

echo "voice library and models are in place"
