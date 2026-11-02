"""把 rime_deployer 编译出的标准 pinyin.table.bin 转成语燕引擎用的格式。

语燕的引擎在表文件头的 index 指针后面多了一个字段，指向一个 float 数组：
按 marisa 字符串表的编号，每个词一个 ln(词频)，联想词排序用。
用法：python yuyan_table.py <标准table.bin> <词库目录cn_dicts> <输出table.bin>
需要 marisa-trie（pip install marisa-trie）。
"""
import math
import struct
import sys
from pathlib import Path

import marisa_trie

MARISA_MAGIC = b'We love Marisa.'


def load_weights(dict_dir):
    """读取 *.dict.yaml，每个词取最大词频"""
    weights = {}
    for f in sorted(Path(dict_dir).glob('*.dict.yaml')):
        started = False
        for line in f.open(encoding='utf-8'):
            if not started:
                started = line.strip() == '...'
                continue
            if not line.strip() or line.startswith('#'):
                continue
            parts = line.rstrip('\n').split('\t')
            # 有拼音的行是 词\t拼音\t词频，腾讯词表是 词\t词频
            w = parts[-1] if len(parts) >= 2 and parts[-1].isdigit() else '0'
            weights[parts[0]] = max(weights.get(parts[0], 0), int(w))
    return weights


def convert(src, dict_dir, dst):
    d = bytearray(Path(src).read_bytes())
    assert d[:15] == b'Rime::Table/4.0', '不是 Rime 4.0 表文件'
    trie = marisa_trie.Trie()
    trie.frombytes(bytes(d[d.find(MARISA_MAGIC):]))
    weights = load_weights(dict_dir)
    floats = [0.0] * len(trie)
    for key, idx in trie.items():
        w = weights.get(key, 0)
        floats[idx] = math.log(w) if w > 1 else 0.0
    # syllabary(44)、index(48) 两个指针在插入点之前，指向的数据整体后移 4 字节
    syllabary, index = struct.unpack_from('<ii', d, 44)
    head = d[:44] + struct.pack('<ii', syllabary + 4, index + 4)
    body = d[52:]
    out = head + b'\0\0\0\0' + body
    out += b'\0' * (-len(out) % 4)
    struct.pack_into('<i', out, 52, len(out) - 52)  # 新字段：指向文件末尾的 float 数组
    out += struct.pack('<%df' % len(floats), *floats)
    Path(dst).write_bytes(out)
    print(f'{dst}: {len(trie)} 个词，{len(out)} 字节')


if __name__ == '__main__':
    convert(*sys.argv[1:4])
