#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""扫描 Kotlin/Compose：在**非 @Composable** 的 lambda 里调用 @Composable 函数。

背景：`buildList {}` / `forEach {}` / `onClick = {}` / `onCheckedChange = {}` 这类
标准库或普通回调的 lambda **不是** @Composable 上下文，在其中调用
`stringResource()` / `Text()` / `remember()` 会报
  "@Composable invocations can only happen from the context of a @Composable function"

**实现要点（踩过的坑）**：
1. 必须**逐字符**扫描 lambda 范围，遇到使括号深度归零的 `}` 立即结束。
   早期版本按行做 `count('{') - count('}')` 累加，遇到 `}) {` 这种
   「先闭后开」的行净变化为 0 → 不会截断，把紧随其后的**另一个 lambda**
   （如 Button 的 content）也算进来，误报率极高（实测 101 处里几乎全为误报）。
2. 名字匹配必须**精确**（比较标识符最后一段）。早期用 endswith 后缀匹配，
   把 Toast.makeText / getText / setContentText / bigText / newPlainText
   都误判成 Compose 的 `Text`。
3. 扫描时要跳过字符串字面量（含三引号）、字符、行注释、块注释。

用法: python3 composable_context_scan.py <文件或目录> ...
"""
import io
import os
import re
import sys

# 接收「非 @Composable lambda」的函数名（精确匹配标识符最后一段）
NON_COMPOSABLE_FNS = {
    'buildList', 'buildMap', 'buildSet', 'forEach', 'map', 'mapNotNull', 'filter',
    'filterNot', 'any', 'all', 'none', 'first', 'firstOrNull', 'lastOrNull',
    'joinToString', 'sortedBy', 'sortedWith', 'sumOf', 'count', 'associate',
    'associateBy', 'groupBy', 'distinctBy', 'takeIf', 'takeUnless', 'let', 'run',
    'apply', 'also', 'with', 'repeat', 'onEach', 'flatMap', 'fold', 'reduce',
    'onClick', 'onCheckedChange', 'onValueChange', 'onDismissRequest', 'onSelect',
    'ifEmpty', 'ifBlank', 'launch', 'invoke',
}

# @Composable 函数名（不含普通属性访问，如 MaterialTheme.xxx 属正常代码）
COMPOSABLE_CALLS = {
    'stringResource', 'pluralStringResource', 'remember', 'rememberSaveable',
    'rememberCoroutineScope', 'rememberUpdatedState', 'produceState',
    'derivedStateOf', 'collectAsStateWithLifecycle',
    'LaunchedEffect', 'DisposableEffect', 'SideEffect',
    'koinViewModel', 'koinInject',
    'Text', 'Icon', 'Row', 'Column', 'Box', 'Card', 'Scaffold', 'Switch',
    'Button', 'TextButton', 'IconButton', 'Surface', 'Spacer', 'Divider',
    'HorizontalDivider', 'CardGroup', 'ToggleSurface', 'BackButton',
    'LargeFlexibleTopAppBar', 'OutlinedTextField', 'AlertDialog', 'LazyColumn',
    'PermissionManager', 'MaterialTheme',
}

# 只匹配**裸标识符**调用（无点）。带点的如 UIMessagePart.Text / Toast.makeText
# 不是 Compose 组件，早期版本做 split('.')[-1] 导致大量误报。
IDENT_RE = re.compile(r'(?<![A-Za-z0-9_.])([A-Za-z_][A-Za-z0-9_]*)\s*\(')

# @Composable 的**参数名**（slot）。这些名字后面的 lambda 是 composable 上下文，
# 其内部再调用 Compose 组件是合法的，必须跳过而不是报错。
COMPOSABLE_SLOTS = {
    'content', 'headlineContent', 'supportingContent', 'leadingContent',
    'trailingContent', 'overlineContent', 'title', 'text', 'actions',
    'navigationIcon', 'topBar', 'bottomBar', 'confirmButton', 'dismissButton',
    'label', 'description', 'tail', 'icon', 'trailingIcon', 'leadingIcon',
    'floatingActionButton', 'snackbarHost', 'badge', 'indicator', 'thumb',
    'leading', 'trailing', 'media', 'subtitle', 'helper', 'placeholder',
}

SLOT_LAMBDA_RE = re.compile(r'\b(' + '|'.join(sorted(COMPOSABLE_SLOTS)) + r')\s*(?:=\s*)?\{')
CALL_LAMBDA_RE = re.compile(r'(?<![A-Za-z0-9_.])([A-Za-z_][A-Za-z0-9_]*)\s*(?:=\s*)?\{')


def _skip_string(s, i):
    """i 指向引号或 /，返回跳过后的位置（未跳过返回 None）"""
    n = len(s)
    if s.startswith('"""', i):
        j = s.find('"""', i + 3)
        return n if j < 0 else j + 3
    if s[i] == '"':
        j = i + 1
        while j < n:
            if s[j] == '\\':
                j += 2; continue
            if s[j] == '"' or s[j] == '\n':
                return j + 1
            j += 1
        return n
    if s[i] == "'":
        j = i + 1
        while j < n:
            if s[j] == '\\':
                j += 2; continue
            if s[j] == "'" or s[j] == '\n':
                return j + 1
            j += 1
        return n
    if s.startswith('//', i):
        j = s.find('\n', i)
        return n if j < 0 else j + 1
    if s.startswith('/*', i):
        j = s.find('*/', i + 2)
        return n if j < 0 else j + 2
    return None


def scan_file(path):
    src = io.open(path, encoding='utf-8').read()
    problems = []
    # 行号索引：offset -> line
    line_starts = [0]
    for m in re.finditer(r'\n', src):
        line_starts.append(m.end())

    def line_of(off):
        lo, hi = 0, len(line_starts) - 1
        while lo < hi:
            mid = (lo + hi + 1) // 2
            if line_starts[mid] <= off:
                lo = mid
            else:
                hi = mid - 1
        return lo + 1

    # 找所有 `name {`（名字在黑名单）
    for m in re.finditer(r'([A-Za-z_][A-Za-z0-9_]*)\s*(?:=\s*)?\{', src):
        name = m.group(1)
        if name not in NON_COMPOSABLE_FNS:
            continue
        brace = m.end() - 1                     # `{` 的位置
        # 从 brace 起逐字符扫描，depth 归零即结束
        depth = 0
        i = brace
        end = len(src)
        while i < len(src):
            sk = _skip_string(src, i)
            if sk is not None:
                i = sk; continue
            c = src[i]
            if c == '{':
                depth += 1
            elif c == '}':
                depth -= 1
                if depth == 0:
                    end = i
                    break
            i += 1
        body = src[brace:end]
        _walk_body(src, brace, end, line_of, name, m.start(), problems)
    return problems


def _walk_body(src, start, end, line_of, outer_name, outer_off, problems):
    """扫描 [start, end) 区间：
    - 遇到 composable slot lambda（headlineContent = {..} 等）→ **跳过**，其内部合法
    - 遇到非 composable lambda（forEach/let/...）→ 递归检查
    - 遇到裸标识符调用且在白名单 → 报告
    """
    i = start + 1
    while i < end:
        sk = _skip_string(src, i)
        if sk is not None:
            i = sk; continue
        # composable slot lambda：跳过整块
        sm = SLOT_LAMBDA_RE.match(src, i)
        if sm:
            j = _match_brace(src, sm.end() - 1)
            i = j; continue
        # 非 composable lambda：递归
        cm2 = CALL_LAMBDA_RE.match(src, i)
        if cm2 and cm2.group(1) in NON_COMPOSABLE_FNS:
            j = _match_brace(src, cm2.end() - 1)
            _walk_body(src, cm2.end() - 1, j, line_of, cm2.group(1), cm2.start(), problems)
            i = j; continue
        # 裸标识符调用
        im = IDENT_RE.match(src, i)
        if im and im.group(1) in COMPOSABLE_CALLS:
            problems.append((line_of(outer_off), outer_name, line_of(i), im.group(1) + '()'))
            i = im.end(); continue
        i += 1


def _match_brace(src, brace):
    """返回与 brace 处 '{' 配对的 '}' 的下标"""
    depth = 0
    i = brace
    while i < len(src):
        sk = _skip_string(src, i)
        if sk is not None:
            i = sk; continue
        c = src[i]
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return len(src)


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
            ps = scan_file(f)
        except Exception as e:
            print("  [err] %s: %s" % (f, e)); continue
        if ps:
            total += len(ps)
            print("[FAIL] %s" % f)
            for lam_line, lam_name, call_line, fn in ps:
                print("         lambda `%s`(行%d) 内 行%d 调用了 %s" % (lam_name, lam_line, call_line, fn))
    print("\n可疑处合计: %d" % total)
    return 1 if total else 0


if __name__ == '__main__':
    sys.exit(main())
