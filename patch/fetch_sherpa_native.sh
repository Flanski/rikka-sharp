#!/bin/bash
# ============================================================
# 下载 sherpa-onnx 的预编译 Android native 库（Rikka# 的本地 TTS 用）
#
# 用法: bash fetch_sherpa_native.sh /path/to/rikkahub-plus
#
# ── 为什么不把 .so 提交进仓库 ──
# 单个 libsherpa-onnx-jni.so 就有 23MB。若入库：
#   1) 仓库 clone 变慢；
#   2) 我们的 patch 是二进制 diff，会把 23MB 编码进 patch（实际膨胀更多），
#      而 patch 要经 GitHub API 逐字上传，成本极高。
# 因此改为「构建前按需下载」，并加入 .gitignore。
#
# ── 为什么用「动态版」而非「静态链接版」 ──
# 官方两个 Android 包：
#   sherpa-onnx-vX-android.tar.bz2                     44MB，含 libonnxruntime.so(21MB)
#                                                      + libsherpa-onnx-jni.so(4.6MB)
#   sherpa-onnx-vX-android-static-link-onnxruntime...  33MB，只有一个自包含的
#                                                      libsherpa-onnx-jni.so(23MB)
#
# 一开始为省体积（23MB vs 25.8MB）与少一个文件而选了**静态版**，结果 App 内
# 加载模型时 native 崩溃（aishell3 与 theresa **都会**崩，且底层在 Termux 里
# 用 C API 验证全部正常）→ 高度怀疑是静态链接版在 App 进程内初始化 ORT 有问题。
#
# 改用动态版的关键理由：**动态版的 libonnxruntime.so 已经在设备上实测验证过**
# （用 c-api.so + libonnxruntime.so 跑通了创建/合成/并发/callback），
# 而静态版因为只导出 JNI 符号、没有 C API，**根本无法用 C 程序验证** ——
# 也就是说静态版是一条从未被验证过的路径。
#
# ── 版本必须与 Tts.kt 一致 ──
# speech/src/main/java/com/k2fsa/sherpa/onnx/Tts.kt 取自同一 tag。
# 二者不一致会导致 JNI 符号不匹配，运行期抛 UnsatisfiedLinkError。
# 校验过的对应关系（v1.13.8）：
#   Tts.kt 的 native 方法 newFromAsset/newFromFile/delete/getSampleRate/
#   getNumSpeakers/generateImpl/generateWithCallbackImpl/generateWithConfigImpl
#   ←→ .so 导出的 Java_com_k2fsa_sherpa_onnx_OfflineTts_* 共 8 个符号
# ============================================================
set -e

REPO="${1:?用法: bash fetch_sherpa_native.sh /path/to/rikkahub-plus}"
VER="${SHERPA_ONNX_VERSION:-1.13.8}"
PKG="sherpa-onnx-v${VER}-android.tar.bz2"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${VER}/${PKG}"

DEST="$REPO/speech/src/main/jniLibs/arm64-v8a"
SO="libsherpa-onnx-jni.so"
ORT="libonnxruntime.so"

if [ -s "$DEST/$SO" ] && [ -s "$DEST/$ORT" ]; then
  echo "   [ok] 已有 $SO + $ORT，跳过下载"
  exit 0
fi

TMPBASE="${TMPDIR:-/tmp}"
TMP="$(mktemp -d "$TMPBASE/sherpa.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

echo "   下载 $PKG (v$VER) ..."
curl -sL --retry 3 --retry-delay 2 --retry-all-errors --max-time 900 \
  -o "$TMP/pkg.tar.bz2" "$URL"

echo "   解压 ..."
tar -xjf "$TMP/pkg.tar.bz2" -C "$TMP"

mkdir -p "$DEST"
for f in "$SO" "$ORT"; do
  SRC="$TMP/jniLibs/arm64-v8a/$f"
  [ -s "$SRC" ] || { echo "   [!!] 包内未找到 $f"; find "$TMP" -name '*.so' | head; exit 1; }
  cp "$SRC" "$DEST/"
  echo "   [ok] $f -> $DEST ($(du -h "$DEST/$f" | cut -f1))"
done
