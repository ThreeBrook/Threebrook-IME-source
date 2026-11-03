"""把站酷小薇裁成只含界面文字的小字体，给设置页面的标题用。

完整字体 6 MB 多，App 里标题只用到界面文案里的几百个字，所以只保留 res/values 下所有字符串里出现的字，
加上 ASCII 和常用中文标点。新加的标题里如果有字体里没有的字，系统会自动用默认字体补上，不会显示成方块，
但最好改完文案后重新跑一次本脚本。

用法：python subset_title_font.py <ZCOOLXiaoWei-Regular.ttf>
需要 fonttools（pip install fonttools）。字体许可 SIL OFL 1.1，许可全文在 yuyansdk/docs/legal/开源许可.md。
"""
import glob
import os
import re
import sys

from fontTools import subset

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RES = os.path.join(ROOT, "yuyansdk", "src", "main", "res")
OUT = os.path.join(RES, "font", "zcool_xiaowei.ttf")
EXTRA = "".join(chr(c) for c in range(0x20, 0x7F)) + "，。、；：？！“”‘’（）《》【】…—·"


def ui_text() -> str:
    text = []
    for path in glob.glob(os.path.join(RES, "values", "*.xml")):
        content = open(path, encoding="utf-8").read()
        text += re.findall(r"<(?:string|item)\b[^>]*>([^<]*)</(?:string|item)>", content)
    return "".join(text)


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    chars = sorted(set(ui_text() + EXTRA))
    options = subset.Options()
    options.layout_features = ["*"]
    options.name_IDs = ["*"]  # 保留字体名和版权信息
    options.notdef_outline = True
    font = subset.load_font(sys.argv[1], options)
    subsetter = subset.Subsetter(options)
    subsetter.populate(text="".join(chars))
    subsetter.subset(font)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    subset.save_font(font, OUT, options)
    print(f"{len(chars)} chars -> {OUT} ({os.path.getsize(OUT) // 1024} KB)")


if __name__ == "__main__":
    main()
