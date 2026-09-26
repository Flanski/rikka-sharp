#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""结构化审计 kotlinx.serialization 的注解完整性。

覆盖两类易错模式：
  A) @Serializable sealed class/interface X { ... }        → 每个子类都要 @Serializable
  B) sealed interface X : SomeBase { ... }                 → 若成员带 @Serializable（NavKey 模式），
                                                             也要每个成员都带
判定规则：成员的**紧邻上一非空行**必须是 @Serializable（不能用宽松窗口，否则会误取
上一条目的注解——这正是漏掉 Screen.SettingSsh 的原因）。

用法: python3 serializable_audit.py <源码根目录>
"""
import io
import os
import re
import sys

MEMBER_RE = re.compile(
    r'^\s*(?:@\w+(?:\([^)]*\))?\s+)*(data object|data class|data object|object|class)\s+(\w+)'
    r'\s*(\([^)]*\))?\s*(?::|$)')


def prev_non_empty(lines, i):
    j = i - 1
    while j >= 0 and lines[j].strip() == '':
        j -= 1
    return (lines[j].strip() if j >= 0 else None), j


def annotation_block(lines, i):
    """收集紧邻声明之上的**连续注解块**（跳过空行）。
    正确判定必须看整块，而不是只看上一行：
        @Serializable
        @SerialName("x")     <- 紧邻行是 @SerialName
        data object X
    只看上一行会把所有正常条目误报成缺 @Serializable。
    """
    anns = []
    j = i - 1
    while j >= 0:
        t = lines[j].strip()
        if t == '':
            j -= 1
            continue
        if t.startswith('@'):
            anns.append(t)
            j -= 1
            continue
        break
    return anns


def audit_file(path):
    lines = io.open(path, encoding='utf-8').read().split('\n')
    problems = []
    hierarchies = []

    # 找出 sealed 声明（含 @Serializable 前缀或继承 NavKey 等）
    for i, l in enumerate(lines):
        m = re.match(r'^\s*(@Serializable\s+)?sealed\s+(class|interface)\s+(\w+)(.*)$', l)
        if not m:
            continue
        has_ser = (m.group(1) is not None) or (i > 0 and lines[i-1].strip() == '@Serializable')
        supertypes = m.group(4)
        name = m.group(3)
        # 情况 B：成员是否带 @Serializable（用首个成员判断）
        # 收集该 sealed 块的成员
        members = []
        depth = 0
        started = False
        for k in range(i, min(len(lines), i + 1500)):
            lk = lines[k]
            depth += lk.count('{') - lk.count('}')
            if '{' in lk:
                started = True
            if k > i:
                mm = re.match(r'^\s{4,}(data object|data class|object|class)\s+(\w+)', lk)
                if mm:
                    anns = annotation_block(lines, k)
                    members.append((k + 1, mm.group(2), '@Serializable' in anns, anns))
            if started and depth <= 0:
                break
        if not members:
            continue
        members_with_ann = sum(1 for _, _, has, _a in members if has)
        # 只有「层级自身 @Serializable」或「多数成员已带注解」才要求全部带
        if has_ser or members_with_ann >= max(1, len(members) // 2):
            hierarchies.append((name, i + 1, has_ser, len(members)))
            for ln, mname, has, anns in members:
                if not has:
                    problems.append((ln, name, mname, anns))
    return hierarchies, problems


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/java"
    total_h = 0
    allp = []
    for dirpath, _d, files in os.walk(root):
        for fn in files:
            if not fn.endswith('.kt'):
                continue
            p = os.path.join(dirpath, fn)
            try:
                hs, ps = audit_file(p)
            except Exception as e:
                print("  [err] %s: %s" % (p, e))
                continue
            if hs:
                total_h += len(hs)
                for name, ln, ser, n in hs:
                    print("  [层级] %-22s %-46s 行%-5d 自身@Serializable=%-5s 成员%d" %
                          (name, os.path.basename(p), ln, ser, n))
            allp += [(p, ln, h, m, pr) for ln, h, m, pr in ps]

    print()
    if allp:
        print("  ✗ 缺 @Serializable 的成员 %d 个:" % len(allp))
        for p, ln, h, m, pr in allp:
            print("     %-40s 行%-5d %s.%s   注解块=%s" %
                  (os.path.basename(p), ln, h, m, pr if pr else '(空)'))
    else:
        print("  ✓ 未发现缺注解的成员")
    print("\n  共检查 @Serializable 层级 %d 个" % total_h)
    return 1 if allp else 0


if __name__ == '__main__':
    sys.exit(main())
