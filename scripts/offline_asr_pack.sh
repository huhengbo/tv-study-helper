#!/usr/bin/env bash
# 生成离线语音包：下载 sherpa-onnx 的 Android 运行库和中英双语流式模型，只取 int8 模型，
# 由 bpe.model 导出热词要用的 bpe.vocab，逐个 gzip，输出 LocalAsrPack.kt 里要填的文件清单。
# 产物上传到 GitHub Release 的 offline-asr-<版本> 预发布（不影响 releases/latest）。
#
# 用法：scripts/offline_asr_pack.sh <输出目录>
# 依赖：curl、tar（bzip2）、gzip、sha256sum、python3（venv，用来装 sentencepiece）
set -euo pipefail

SHERPA_VERSION=2.0.1
MODEL=sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16
BASE=https://github.com/k2-fsa/sherpa-onnx/releases/download

out=${1:?用法：$0 <输出目录>}
mkdir -p "$out"
out=$(cd "$out" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

echo "下载运行库和模型（模型压缩包约 460MB，里面含 fp32 模型和测试音频，只取 int8 部分）…" >&2
curl -fL --retry 3 -o "$work/lib.tar.bz2" "$BASE/v$SHERPA_VERSION/sherpa-onnx-v$SHERPA_VERSION-android-static-link-onnxruntime.tar.bz2"
curl -fL --retry 3 -o "$work/model.tar.bz2" "$BASE/asr-models/$MODEL.tar.bz2"

mkdir -p "$work/lib" "$work/model"
tar xjf "$work/lib.tar.bz2" -C "$work/lib" ./jniLibs/armeabi-v7a/libsherpa-onnx-jni.so ./jniLibs/arm64-v8a/libsherpa-onnx-jni.so
tar xjf "$work/model.tar.bz2" -C "$work/model" --strip-components=1 \
  "$MODEL/encoder-epoch-99-avg-1.int8.onnx" "$MODEL/decoder-epoch-99-avg-1.int8.onnx" \
  "$MODEL/joiner-epoch-99-avg-1.int8.onnx" "$MODEL/tokens.txt" "$MODEL/bpe.model"

python3 -m venv "$work/venv"
"$work/venv/bin/pip" -q install sentencepiece
"$work/venv/bin/python" -I - "$work/model/bpe.model" "$work/model/bpe.vocab" <<'EOF'
import sys, sentencepiece as spm
sp = spm.SentencePieceProcessor(model_file=sys.argv[1])
with open(sys.argv[2], "w", encoding="utf-8") as f:
    for i in range(sp.get_piece_size()):
        f.write(f"{sp.id_to_piece(i)}\t{sp.get_score(i)}\n")
EOF

cp "$work/model/encoder-epoch-99-avg-1.int8.onnx" "$out/encoder.int8.onnx"
cp "$work/model/decoder-epoch-99-avg-1.int8.onnx" "$out/decoder.int8.onnx"
cp "$work/model/joiner-epoch-99-avg-1.int8.onnx" "$out/joiner.int8.onnx"
cp "$work/model/tokens.txt" "$work/model/bpe.vocab" "$out/"
cp "$work/lib/jniLibs/armeabi-v7a/libsherpa-onnx-jni.so" "$out/libsherpa-onnx-jni-armeabi-v7a.so"
cp "$work/lib/jniLibs/arm64-v8a/libsherpa-onnx-jni.so" "$out/libsherpa-onnx-jni-arm64-v8a.so"
(cd "$out" && gzip -9 -n -f encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx tokens.txt bpe.vocab \
  libsherpa-onnx-jni-armeabi-v7a.so libsherpa-onnx-jni-arm64-v8a.so)

echo "文件清单（名称 大小 SHA-256）：" >&2
for f in "$out"/*.gz; do
  echo "$(basename "$f") $(stat -c%s "$f") $(sha256sum "$f" | cut -c1-64)"
done
