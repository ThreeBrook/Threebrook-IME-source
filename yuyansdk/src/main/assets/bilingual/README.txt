双语学习模式的离线词典，由 tools/dict/bilingual.py 生成。

zh-en.tsv  汉英：CC-CEDICT（https://www.mdbg.net/chinese/dictionary?page=cc-cedict）
           许可：CC BY-SA 4.0（https://creativecommons.org/licenses/by-sa/4.0/），本文件按同一许可提供。
           改动：按 jieba 词频挑选约 3 万个常用简体词；去掉异体字、姓氏、量词等释义；拼音改为声调符号。
           格式：词 \t 拼音 \t 释义(用/分隔) [\t 拼音2 \t 释义2 ...]
en-zh.tsv  英汉：ECDICT（https://github.com/skywind3000/ECDICT），Copyright (c) 2017 Linwei，MIT 许可。
           改动：按 assets/english/en_words.txt 词频挑选约 2.5 万个常用词，每词最多 4 行释义。
           格式：词 \t 音标 \t 释义行1 [\t 释义行2 ...]

换别的目标语言：加 zh-<代码>.tsv 和 <代码>-zh.tsv，并在 Bilingual.Target 里加一项。
