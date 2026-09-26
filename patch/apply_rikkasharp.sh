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

echo "==> 1/4 删除命理二进制包（offline_pkgs，33MB；CI 脚本含在 patch 内）"
git rm -r --quiet --ignore-unmatch app/offline_pkgs || true

echo "==> 2/4 应用代码改动"
git apply --whitespace=nowarn "$PATCH"

echo "==> 3/4 复核"
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
  if python3 "$PATCH_DIR/serializable_audit.py" app/src/main/java | sed 's/^/   /'; then
    echo "   [ok] 序列化注解完整"
  else
    echo "   [!!] 存在缺失 @Serializable 的成员（运行期会崩）"; FAIL=1
  fi
fi
[ -f app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingSshPage.kt ] \
  && echo "   [ok] SSH 客户端页面存在" || { echo "   [!!] SSH 页面缺失"; FAIL=1; }
[ "$FAIL" -ne 0 ] && { echo; echo "==> 复核未通过"; exit 1; }

echo "==> 4/4 完成"
cat <<'EOF'

接下来（推送即触发 GitHub Actions 编译）：
    git add -A
    git commit -m "feat: Rikka# — 移除命理，新增 fetch_url/github_search/wikipedia_search 与 SSH 客户端"
    git push origin mingli2

编译完成后在仓库 Actions 页面下载 artifact：rikkahub-plus-fresh
（约 20–40 分钟；无需配置任何 secrets —— app.key 与 google-services.json 已在仓库内）
EOF
