#!/bin/bash
# ============================================================
# 将「Rikka#」改造应用到 rikkahub-plus 源码
#
# 包含三项改动：
#   1. 删除命理功能（工具 / 知识库 / JS 引擎 / Python 路由 / CI 步骤）
#   2. 新增网络检索工具：fetch_url / github_search / wikipedia_search
#   3. 新增 SSH 客户端：5 个工具 + 设置页「SSH 客户端」入口
#   4. 应用 ID 改为 me.rerere.rikkasharp，显示名 Rikka#（可与 Rikka+ 共存）
#
# 用法：
#   git clone --depth 1 --branch mingli2 https://github.com/<你的账号>/rikkahub-plus.git
#   cd rikkahub-plus
#   bash apply_rikkasharp.sh "$PWD"
#   然后 git add -A && git commit && git push   → GitHub Actions 自动编译
# ============================================================
set -e

REPO="${1:?用法: bash apply_rikkasharp.sh /path/to/rikkahub-plus}"
PATCH_DIR="$(cd "$(dirname "$0")" && pwd)"
PATCH="$PATCH_DIR/01_rikkasharp_all.patch"

cd "$REPO"
[ -f "$PATCH" ] || { echo "!! 找不到 patch: $PATCH"; exit 1; }

echo "==> 0/4 校验仓库"
VER=$(grep -oP 'versionCode = \K[0-9]+' app/build.gradle.kts | head -1 || echo "?")
echo "    versionCode = $VER (期望 173 / v2.4.6)"
[ "$VER" = "173" ] || echo "    ⚠️ 版本不一致，请确认在同一基线上操作"

echo "==> 1/5 删除命理二进制包（offline_pkgs，33MB；CI 脚本含在 patch 内）"
git rm -r --quiet --ignore-unmatch app/offline_pkgs || true

echo "==> 2/5 应用代码改动"
git apply --whitespace=nowarn "$PATCH"

echo "==> 3/5 下载 sherpa-onnx native 库（本地 TTS 用，23MB，不入库）"
if [ -f "$PATCH_DIR/fetch_sherpa_native.sh" ]; then
  bash "$PATCH_DIR/fetch_sherpa_native.sh" "$REPO"
else
  echo "   [!!] 缺少 fetch_sherpa_native.sh（TTS 将无法在运行期加载 native 库）"
fi

echo "==> 4/5 复核"
FAIL=0
check() {
  local n
  n=$(grep -rl "$2" --include="$3" . 2>/dev/null | grep -v '^\./\.git' | wc -l)
  if [ "$n" -ne 0 ]; then echo "   [!!] $1: $n 个文件仍命中"; FAIL=1; else echo "   [ok] $1"; fi
}
check "命理 Kotlin 引用"   "MingliTool\|createMingliTool\|enableMingliTools" "*.kt"
check "命理 Python 引用"   "mingli_router\|bazi_china\|ziwei_paipan"           "*.py"
check "命理资产引用"       "mingli/\|de440s"                                   "*.kt"
grep -q "createFetchUrlTool" app/src/main/java/me/rerere/rikkahub/service/ChatService.kt \
  && echo "   [ok] 搜索工具已注册" || { echo "   [!!] 搜索工具未注册"; FAIL=1; }
grep -q "createSshTools" app/src/main/java/me/rerere/rikkahub/service/ChatService.kt \
  && echo "   [ok] SSH 工具已注册" || { echo "   [!!] SSH 工具未注册"; FAIL=1; }
grep -q 'applicationId = "me.rerere.rikkasharp"' app/build.gradle.kts \
  && echo "   [ok] 应用 ID = me.rerere.rikkasharp" || { echo "   [!!] 应用 ID 未改"; FAIL=1; }

# kotlinx.serialization 注解完整性审计
# （新增 @Serializable 层级的成员时必须补注解，否则运行期 "Serializer not found" 崩溃）
if [ -f "$PATCH_DIR/serializable_audit.py" ]; then
  echo "   --- @Serializable 注解审计 ---"
  # 同时审计 app 与 speech 两个模块（TTSProviderSetting 在 speech 模块，
  # 本次新增的 SherpaOnnx 就在那里）
  AUDIT_OK=1
  for MOD in app/src/main/java speech/src/main/java; do
    echo "   --- $MOD ---"
    if ! python3 "$PATCH_DIR/serializable_audit.py" "$MOD" | sed 's/^/   /'; then
      AUDIT_OK=0
    fi
  done
  if [ "$AUDIT_OK" = "1" ]; then
    echo "   [ok] 序列化注解完整"
  else
    echo "   [!!] 存在缺失 @Serializable 的成员（运行期会崩）"; FAIL=1
  fi
fi
[ -f app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingSshPage.kt ] \
  && echo "   [ok] SSH 客户端页面存在" || { echo "   [!!] SSH 页面缺失"; FAIL=1; }
[ -f speech/src/main/java/com/k2fsa/sherpa/onnx/Tts.kt ] \
  && echo "   [ok] sherpa-onnx Kotlin API 存在" || { echo "   [!!] Tts.kt 缺失"; FAIL=1; }
[ -s speech/src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so ] \
  && echo "   [ok] sherpa-onnx native 库就位" || { echo "   [!!] 缺少 libsherpa-onnx-jni.so（CI 会下载，本地请跑 fetch_sherpa_native.sh）"; FAIL=1; }
grep -q "SherpaOnnx" speech/src/main/java/me/rerere/tts/provider/TTSProviderSetting.kt 2>/dev/null \
  && echo "   [ok] TTS provider 设置已含 SherpaOnnx" || { echo "   [!!] TTSProviderSetting 未含 SherpaOnnx"; FAIL=1; }
[ "$FAIL" -ne 0 ] && { echo; echo "==> 复核未通过"; exit 1; }

echo "==> 5/5 完成"
cat <<'EOF'

接下来（推送即触发 GitHub Actions 编译）：
    git add -A
    git commit -m "feat: Rikka# — 移除命理，新增 fetch_url/github_search/wikipedia_search 与 SSH 客户端"
    git push origin mingli2

编译完成后在仓库 Actions 页面下载 artifact：rikkahub-plus-fresh
（约 20–40 分钟；无需配置任何 secrets —— app.key 与 google-services.json 已在仓库内）
EOF
