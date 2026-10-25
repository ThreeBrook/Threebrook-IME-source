"""Turn docs/legal/*.md into the in-app pages under src/main/assets/legal/.
Usage: python tools/gen_legal_html.py <docs/legal dir> src/main/assets/legal
Only the Markdown these documents use: headings, paragraphs, lists, tables, code blocks, links, bold.
Colours follow docs/01-ui/三溪外观规范.md (light and dark)."""
import html
import os
import re
import sys

SRC, OUT = sys.argv[1], sys.argv[2]
PAGES = {"三溪输入法-隐私政策.md": "privacy.html", "三溪输入法-软件许可协议.md": "eula.html", "开源许可.md": "licenses.html"}

CSS = """
:root{--bg:#F6FAF6;--card:#FFFFFF;--text:#1D3331;--sub:#4E6461;--border:#D6E4DB;--accent:#1C8780;--code:#EAF3EC}
@media (prefers-color-scheme: dark){:root{--bg:#0F1A19;--card:#172826;--text:#E4F0EC;--sub:#A8BDB8;--border:#27403C;--accent:#4CC0B6;--code:#14231F}}
html{-webkit-text-size-adjust:100%}
body{margin:0;padding:16px 16px 40px;background:var(--bg);color:var(--text);font:15px/1.6 system-ui,sans-serif;word-wrap:break-word}
h1{font-size:22px;line-height:1.3;margin:4px 0 12px}
h2{font-size:17px;margin:24px 0 8px;padding-top:12px;border-top:1px solid var(--border)}
p,li{margin:6px 0}
ul,ol{padding-left:22px}
a{color:var(--accent)}
.meta{color:var(--sub);font-size:13px;margin:2px 0}
table{width:100%;border-collapse:collapse;margin:10px 0;font-size:13px;background:var(--card)}
th,td{border:1px solid var(--border);padding:6px 8px;text-align:left;vertical-align:top}
th{color:var(--sub);font-weight:600}
pre{background:var(--code);border:1px solid var(--border);border-radius:8px;padding:10px;overflow-x:auto;font-size:11.5px;line-height:1.45;white-space:pre-wrap}
"""


def inline(text):
    out = html.escape(text, quote=False)
    out = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", out)
    out = re.sub(r"\[([^\]]+)\]\((https?://[^)\s]+)\)", r'<a href="\2">\1</a>', out)
    out = re.sub(r'(?<!href=")(?<![">])(https?://[^\s<，。、；）)]+)', r'<a href="\1">\1</a>', out)
    return out


def convert(md):
    lines = md.replace("\r", "").split("\n")
    body, i, title = [], 0, ""
    while i < len(lines):
        line = lines[i]
        if line.startswith("```"):
            j = i + 1
            while j < len(lines) and not lines[j].startswith("```"):
                j += 1
            # 许可证原文按固定宽度断行，在手机上会断成很短的行：段内的行合并，空行仍分段
            paragraphs = re.split(r"\n\s*\n", "\n".join(lines[i + 1:j]).strip("\n"))
            text = "\n\n".join(" ".join(l.strip() for l in p.split("\n")) for p in paragraphs)
            body.append("<pre>" + html.escape(text) + "</pre>")
            i = j + 1
        elif line.startswith("# "):
            title = line[2:].strip()
            body.append(f"<h1>{inline(title)}</h1>")
            i += 1
        elif line.startswith("## "):
            body.append(f"<h2>{inline(line[3:].strip())}</h2>")
            i += 1
        elif line.startswith("|"):
            rows = []
            while i < len(lines) and lines[i].startswith("|"):
                cells = [c.strip() for c in lines[i].strip().strip("|").split("|")]
                if not all(re.fullmatch(r"-+", c) for c in cells):
                    rows.append(cells)
                i += 1
            head, *rest = rows
            table = "<table><tr>" + "".join(f"<th>{inline(c)}</th>" for c in head) + "</tr>"
            table += "".join("<tr>" + "".join(f"<td>{inline(c)}</td>" for c in r) + "</tr>" for r in rest)
            body.append(table + "</table>")
        elif re.match(r"(- |\d+\. )", line):
            ordered = bool(re.match(r"\d+\. ", line))
            items = []
            while i < len(lines) and re.match(r"(- |\d+\. )", lines[i]):
                items.append(re.sub(r"^(- |\d+\. )", "", lines[i]))
                i += 1
            tag = "ol" if ordered else "ul"
            body.append(f"<{tag}>" + "".join(f"<li>{inline(t)}</li>" for t in items) + f"</{tag}>")
        elif line.strip():
            para = [line]
            i += 1
            while i < len(lines) and lines[i].strip() and not re.match(r"(#|\||- |\d+\. |```)", lines[i]):
                para.append(lines[i])
                i += 1
            text = " ".join(para)
            cls = ' class="meta"' if re.match(r"(版本|发行方)：", text) else ""
            body.append(f"<p{cls}>{inline(text)}</p>")
        else:
            i += 1
    return title, "\n".join(body)


os.makedirs(OUT, exist_ok=True)
for src, out in PAGES.items():
    title, body = convert(open(os.path.join(SRC, src), encoding="utf-8").read())
    page = (f'<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">'
            f'<meta name="viewport" content="width=device-width,initial-scale=1">'
            f'<meta name="color-scheme" content="light dark"><title>{html.escape(title)}</title>'
            f"<style>{CSS}</style></head><body>\n{body}\n</body></html>\n")
    with open(os.path.join(OUT, out), "w", encoding="utf-8", newline="\n") as f:
        f.write(page)
    print(out, len(page))
