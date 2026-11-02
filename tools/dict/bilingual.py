"""
生成双语学习模式用的离线小词典（assets/bilingual/）。

  python -I bilingual.py <cedict.txt> <jieba_dict.txt> <ecdict.csv> <en_words.txt> <输出目录>

来源：
  CC-CEDICT  https://www.mdbg.net/chinese/dictionary?page=cc-cedict  CC BY-SA 4.0  中->英
  ECDICT     https://github.com/skywind3000/ECDICT                    MIT          英->中
  jieba dict.txt（MIT）只用来按词频挑常用中文词，不进 APK；
  en_words.txt（assets/english/，CC BY-SA 4.0）只用来按词频挑常用英文词。

输出（UTF-8，每行一个词，字段用 Tab 分隔）：
  zh-en.tsv  词 \t 读音1 \t 释义1(多条用/隔开) [\t 读音2 \t 释义2 ...]
  en-zh.tsv  词 \t 音标 \t 释义行1 [\t 释义行2 ...]
"""
import csv
import re
import sys

ZH_LIMIT = 30000   # 常用中文词条数
EN_LIMIT = 25000   # 常用英文词条数
MAX_DEFS = 4       # 每个读音最多保留几条英文释义
MAX_EN_LINES = 4   # 每个英文词最多保留几行中文释义
MAX_LINE = 60

TONE_MARKS = {
    'a': 'āáǎà', 'e': 'ēéěè', 'i': 'īíǐì', 'o': 'ōóǒò', 'u': 'ūúǔù', 'ü': 'ǖǘǚǜ',
}


def syllable_with_mark(syl):
    m = re.fullmatch(r"([A-Za-z:üÜ]+)([1-5])", syl)
    if not m:
        return syl.replace('u:', 'ü')
    body, tone = m.group(1).replace('u:', 'ü').replace('U:', 'Ü'), int(m.group(2))
    if tone == 5:
        return body
    lower = body.lower()
    # 标调规则：有 a/e 标在 a/e 上，ou 标在 o 上，否则标在最后一个元音上
    if 'a' in lower:
        pos = lower.index('a')
    elif 'e' in lower:
        pos = lower.index('e')
    elif 'ou' in lower:
        pos = lower.index('o')
    else:
        pos = max(lower.rfind(v) for v in 'iouü')
        if pos < 0:
            return body
    ch = lower[pos]
    marked = TONE_MARKS[ch][tone - 1]
    if body[pos].isupper():
        marked = marked.upper()
    return body[:pos] + marked + body[pos + 1:]


def pinyin_marks(numbered):
    return ' '.join(syllable_with_mark(s) for s in numbered.split())


SKIP_DEF = re.compile(r"^(variant of|old variant of|unofficial variant of|archaic variant of|surname |CL:|Taiwan pr\.|also written|see |used in |abbr\. for |erhua variant)", re.I)
REF_TRAD_SIMP = re.compile(r"[^\s|\[\]/]+\|([^\s|\[\]/]+)\[[^\]]*\]")
REF_SIMPLE = re.compile(r"([^\s|\[\]/]+)\[[A-Za-z0-9:, ]+\]")


def clean_def(d):
    d = REF_TRAD_SIMP.sub(r"\1", d)
    d = REF_SIMPLE.sub(r"\1", d)
    return d.strip()


def load_cedict(path):
    entries = {}  # 简体 -> [(拼音, [释义])]
    line_re = re.compile(r"^(\S+) (\S+) \[([^\]]+)\] /(.*)/\s*$")
    with open(path, encoding='utf-8') as f:
        for line in f:
            if line.startswith('#'):
                continue
            m = line_re.match(line)
            if not m:
                continue
            simp, pinyin, defs = m.group(2), m.group(3), m.group(4).split('/')
            kept = [clean_def(d) for d in defs if d and not SKIP_DEF.match(d)]
            kept = [d for d in kept if d and '\t' not in d][:MAX_DEFS]
            if not kept:
                continue
            entries.setdefault(simp, []).append((pinyin, kept))
    return entries


def reading_order(item):
    pinyin, defs = item
    return pinyin[:1].isupper()  # 大写拼音是人名地名，排后面；其余保持 CEDICT 原顺序


def build_zh(cedict_path, jieba_path, out_path):
    cedict = load_cedict(cedict_path)
    freq = []
    with open(jieba_path, encoding='utf-8') as f:
        for line in f:
            parts = line.split()
            if len(parts) >= 2 and parts[0] in cedict:
                freq.append((int(parts[1]), parts[0]))
    freq.sort(reverse=True)
    count = 0
    with open(out_path, 'w', encoding='utf-8', newline='\n') as out:
        for _, word in freq[:ZH_LIMIT]:
            merged = {}  # 繁体不同、简体相同的条目（如 了/瞭）按读音合并
            for pinyin, defs in cedict[word]:
                merged.setdefault(pinyin, []).extend(d for d in defs if d not in merged.get(pinyin, []))
            readings = sorted(((p, d[:MAX_DEFS]) for p, d in merged.items()), key=reading_order)[:3]
            fields = [word]
            for pinyin, defs in readings:
                fields += [pinyin_marks(pinyin), '/'.join(defs)]
            out.write('\t'.join(fields) + '\n')
            count += 1
    print('zh-en', count)


def build_en(ecdict_path, en_words_path, out_path):
    wanted = []
    with open(en_words_path, encoding='utf-8') as f:
        for line in f:
            word = line.split(' ')[0]
            wanted.append(word)
    wanted_set = set(wanted)
    rows = {}
    csv.field_size_limit(10 ** 7)
    with open(ecdict_path, encoding='utf-8', newline='') as f:
        for row in csv.DictReader(f):
            word = row['word']
            if word.lower() not in wanted_set or not row['translation'].strip():
                continue
            key = word.lower()
            if key in rows and word != key:
                continue  # 同时有 Apple 和 apple 时用小写那条
            rows[key] = row
    count = 0
    with open(out_path, 'w', encoding='utf-8', newline='\n') as out:
        for word in wanted:
            row = rows.get(word)
            if row is None:
                continue
            text = row['translation'].replace('\r', '').replace('\\r', '')
            lines = [l.strip() for l in text.split('\\n') if l.strip()]
            normal = [l for l in lines if not l.startswith('[')]  # [网络] [计] [医] 等释义只在没有普通释义时保留
            lines = (normal or lines)[:MAX_EN_LINES]
            lines = [l[:MAX_LINE].replace('\t', ' ') for l in lines]
            phonetic = row['phonetic'].replace('\t', ' ').strip()
            out.write('\t'.join([word, phonetic] + lines) + '\n')
            count += 1
            if count >= EN_LIMIT:
                break
    print('en-zh', count)


if __name__ == '__main__':
    cedict_path, jieba_path, ecdict_path, en_words_path, out_dir = sys.argv[1:6]
    build_zh(cedict_path, jieba_path, out_dir + '/zh-en.tsv')
    build_en(ecdict_path, en_words_path, out_dir + '/en-zh.tsv')
