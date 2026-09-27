#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""检查 Kotlin 文件中「用到但未 import」的 Compose / 常用扩展函数。

动机：run#18 编译失败于
    e: TTSProviderConfigure.kt:1330:37 Unresolved reference 'padding'.
即用了 `Modifier.padding(...)` 却没 import `androidx.compose.foundation.layout.padding`。
这类错误**本地静态检查很容易漏**（括号平衡、缩进都正常），却要等 20 分钟 CI 才暴露。

原理：维护一张「扩展/顶层函数 → 必需 import」表，扫描源码里是否调用了这些函数，
再比对 import 列表。仅在**确实缺失**时报出。

已知局限（避免误报）：
- `weight` / `align` 等是 RowScope/ColumnScope 的**成员函数**，无需 import → 不列入。
- 同名函数可能来自其它包（如 `Text` 有多处），故只收录**包唯一**的 layout 扩展。
- 变量名与函数名重名（如 `val padding = ...` 后又 `.padding(`）会被误判，属已知误差。

用法: python3 import_check.py <文件或目录> ...
"""
import io
import os
import re
import sys

# 函数名 → 必需的 import（只收录包唯一的）
REQUIRED: dict[str, str] = {
    # androidx.compose.foundation.layout
    'padding': 'androidx.compose.foundation.layout.padding',
    'fillMaxWidth': 'androidx.compose.foundation.layout.fillMaxWidth',
    'fillMaxHeight': 'androidx.compose.foundation.layout.fillMaxHeight',
    'fillMaxSize': 'androidx.compose.foundation.layout.fillMaxSize',
    # 注意：'size' / 'width' / 'height' 刻意**不收录** ——
    # 它们与普通方法（File.size()、String.width()…）重名，在全项目扫描时
    # 产生大量误报（实测 35 处里大部分是这三者）。宁可漏报也不误报，
    # 因为该检查器会被当作「必须修复」的依据。
    'aspectRatio': 'androidx.compose.foundation.layout.aspectRatio',
    'wrapContentWidth': 'androidx.compose.foundation.layout.wrapContentWidth',
    'wrapContentHeight': 'androidx.compose.foundation.layout.wrapContentHeight',
    'imePadding': 'androidx.compose.foundation.layout.imePadding',
    'navigationBarsPadding': 'androidx.compose.foundation.layout.navigationBarsPadding',
    'statusBarsPadding': 'androidx.compose.foundation.layout.statusBarsPadding',
    # androidx.compose.foundation
    'verticalScroll': 'androidx.compose.foundation.verticalScroll',
    'horizontalScroll': 'androidx.compose.foundation.horizontalScroll',
    'clickable': 'androidx.compose.foundation.clickable',
    'background': 'androidx.compose.foundation.background',
    'border': 'androidx.compose.foundation.border',
    'alpha': 'androidx.compose.ui.draw.alpha',
    'clip': 'androidx.compose.ui.draw.clip',
    'rotate': 'androidx.compose.ui.draw.rotate',
    # androidx.compose.ui.platform / unit
    'dp': 'androidx.compose.ui.unit.dp',
    'sp': 'androidx.compose.ui.unit.sp',
}

# 这些不需要 import（成员函数 / 语言内建 / 同文件可见），显式排除以免误报
IGNORE = {'weight', 'align', 'matchParentSize', 'fillMaxWidthFraction'}


def check(path: str) -> list[tuple[int, str, str]]:
    src = io.open(path, encoding='utf-8').read()
    lines = src.split('\n')
    imported = set(re.findall(r'^import\s+([\w.]+)', src, re.M))
    # 通配符 import（如 `import androidx.compose.foundation.layout.*`）会让该包下
    # 所有符号可用 —— 早期版本未处理，导致使用通配符的文件被大量误报
    # （PersonaPage.kt 即为此例，编译一直是成功的）。
    wildcard_pkgs = {m.group(1) for m in re.finditer(r'^import\s+([\w.]+)\.\*', src, re.M)}

    problems = []
    for fn, need in REQUIRED.items():
        if fn in IGNORE:
            continue
        # 包被通配符导入 → 视为已导入
        if need.rsplit('.', 1)[0] in wildcard_pkgs:
            continue
        # 精确匹配
        if need in imported:
            continue
        # 该文件是否真的调用了它（`.fn(` 或 `fn(`）
        hit = None
        for idx, l in enumerate(lines, start=1):
            # 跳过 import 行与注释行
            st = l.strip()
            if st.startswith('import ') or st.startswith('//') or st.startswith('*'):
                continue
            if re.search(r'(?<![\w.])' + re.escape(fn) + r'\s*\(', l) or \
               re.search(r'\.' + re.escape(fn) + r'\s*\(', l):
                hit = idx
                break
        if hit and not any(i.endswith('.' + fn) for i in imported):
            problems.append((hit, fn, need))
    return problems


def main():
    targets = sys.argv[1:]
    files = []
    for t in targets:
        if os.path.isdir(t):
            for root, _d, names in os.walk(t):
                if '/.git' in root or '/build/' in root:
                    continue
                files += [os.path.join(root, n) for n in names if n.endswith('.kt')]
        else:
            files.append(t)

    total = 0
    for f in sorted(files):
        try:
            ps = check(f)
        except Exception as e:
            print("  [err] %s: %s" % (f, e)); continue
        if ps:
            total += len(ps)
            print("[FAIL] %s" % f)
            for ln, fn, need in ps:
                print("         行%-5d 使用了 %s() 但缺少 import %s" % (ln, fn, need))
    print("\n缺失 import 合计: %d" % total)
    return 1 if total else 0


if __name__ == '__main__':
    sys.exit(main())
