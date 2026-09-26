#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""扫描 APK：找出「被字符串引用、但类不在 dex 里」的类名。

原理：R8 只跟踪**字节码里的类型引用**。若某个库把类名写成**字符串**
（config.put / Class.forName / ServiceLoader 配置 / 反射名字），R8 看不到该关联，
就会把那些类裁掉或改名，运行期抛 ClassNotFoundException / NoClassDefFoundError。

本脚本把 dex 字符串池里形如 `a.b.C` 的全限定类名挑出来，转成 `La/b/C;` 描述符，
检查是否存在于任一 dex 的 type 表中。不在的即为**可疑缺失**。

误报来源（需人工筛）：日志文本、库名、历史遗留字符串、指向被 R8 改名的类
（后者恰恰是真问题）。因此输出按「像类名的程度」和出现次数排序。

用法: python3 missing_class_scan.py <apk>
"""
import re
import struct
import sys
import zipfile
from collections import Counter


class Dex:
    def __init__(self, data):
        self.d = data
        (self.strings_size, self.strings_off) = struct.unpack_from('<II', data, 56)
        (self.types_size, self.types_off) = struct.unpack_from('<II', data, 64)
        (self.classes_size, self.classes_off) = struct.unpack_from('<II', data, 96)

    def uleb(self, off):
        r = 0; s = 0
        while True:
            b = self.d[off]; off += 1; r |= (b & 0x7f) << s
            if not (b & 0x80):
                return r, off
            s += 7

    def string(self, i):
        off = struct.unpack_from('<I', self.d, self.strings_off + i * 4)[0]
        _, p = self.uleb(off)
        e = self.d.index(b'\x00', p)
        return self.d[p:e].decode('utf-8', 'replace')

    def type(self, i):
        if i >= self.types_size:
            return None
        si = struct.unpack_from('<I', self.d, self.types_off + i * 4)[0]
        return self.string(si)

    def all_types(self):
        out = set()
        for i in range(self.classes_size):
            b = self.classes_off + i * 32
            ci = struct.unpack_from('<I', self.d, b)[0]
            t = self.type(ci)
            if t:
                out.add(t)
            sup = self.type(struct.unpack_from('<I', self.d, b + 8)[0])
            if sup:
                out.add(sup)
        # 类型表全部内容也算（含接口/字段/参数类型）
        for i in range(self.types_size):
            t = self.type(i)
            if t:
                out.add(t)
        return out


# 像「Java/Kotlin 全限定类名」的字符串：至少三段，最后一段首字母大写
CLASSNAME_RE = re.compile(
    r'^(?:[a-z][a-z0-9_]*\.){2,}[A-Z][A-Za-z0-9_]*'
    r'(?:\$[A-Za-z0-9_]+)*$'
)
# 排除明显不是类的东西
BAD_SUFFIX = re.compile(r'\.(json|xml|html|txt|md|yml|yaml|properties|so|png|jpg|jpeg|gif|js|css|proto|toml|ini|conf|log)$',
                        re.I)


def main():
    apk = sys.argv[1]
    z = zipfile.ZipFile(apk)
    dexs = {n: Dex(z.read(n)) for n in z.namelist() if n.endswith('.dex')}

    alltypes = set()
    for d in dexs.values():
        alltypes.update(d.all_types())
    print("  dex 类型总数: %d" % len(alltypes))

    # 收集候选类名
    cand = Counter()
    where = {}
    for n, d in dexs.items():
        for i in range(d.strings_size):
            s = d.string(i)
            if not s or len(s) > 120:
                continue
            if BAD_SUFFIX.search(s):
                continue
            if not CLASSNAME_RE.match(s):
                continue
            cand[s] += 1
            where.setdefault(s, n)

    print("  形似类名的字符串: %d 个（去重）" % len(cand))

    missing = []
    for s, c in cand.items():
        desc = 'L' + s.replace('.', '/') + ';'
        if desc not in alltypes:
            missing.append((s, c, where[s]))

    print("  ✗ 引用了但 dex 里不存在的类名: %d 个" % len(missing))
    print()

    # 按包分组统计
    pkgs = Counter()
    for s, c, w in missing:
        pkgs['.'.join(s.split('.')[:3])] += 1
    print("  按包分组（前 25）:")
    for p, c in pkgs.most_common(25):
        print("     %-46s %d" % (p, c))

    print()
    print("  --- 完整清单（前 80，按出现次数降序）---")
    for s, c, w in sorted(missing, key=lambda x: -x[1])[:80]:
        print("     x%-3d %-14s %s" % (c, w, s))
    return 0


if __name__ == '__main__':
    sys.exit(main())
