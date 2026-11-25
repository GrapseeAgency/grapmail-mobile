#!/usr/bin/env python3
"""Generate Android string resources (and the iOS SecurityCenter table) from
translated JSON.

Usage
-----
    python translations/generate.py            # write what exists
    python translations/generate.py --check    # report coverage, write nothing

Input
-----
`translations/source.json` holds the English source set:

    {
      "android": {"<resource file>": {"<key>": "<English>" | {"one": ..., "other": ...}}},
      "ios_security": ["<English sentence used as the secL key>", ...],
      "locales": ["de", "fr", ...]
    }

`translations/<locale>.json` holds that locale's work:

    {"android": {"<resource file>": {"<key>": "<translation>"}},
     "ios_security": {"<English sentence>": "<translation>"}}

A key that is absent falls back to English and is NOT written to the locale
directory, so Android's own resource fallback serves the English string and the
coverage report stays honest.

Placeholders (`%1$s`, `%d`, …) are compared before a translation is accepted; a
mismatch falls back to English and is reported instead of shipping a crash.
"""
import os, re, json, sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
RES = os.path.join(ROOT, "android", "app", "src", "main", "res")
IOS = os.path.join(ROOT, "ios", "GrapMail")

LOCALE_DIR = {
    "ar": "values-ar", "bn": "values-bn", "de": "values-de", "es": "values-es",
    "fa": "values-fa", "fr": "values-fr", "hi": "values-hi", "in": "values-in",
    "it": "values-it", "iw": "values-iw", "ja": "values-ja", "ko": "values-ko",
    "pl": "values-pl", "pt-rBR": "values-pt-rBR", "ru": "values-ru", "tr": "values-tr",
    "ur": "values-ur", "vi": "values-vi", "zh-rCN": "values-zh-rCN", "zh-rTW": "values-zh-rTW",
}
IOS_DIR = {
    "ar": "ar", "bn": "bn", "de": "de", "es": "es", "fa": "fa", "fr": "fr", "hi": "hi",
    "in": "id", "it": "it", "iw": "he", "ja": "ja", "ko": "ko", "pl": "pl",
    "pt-rBR": "pt-BR", "ru": "ru", "tr": "tr", "ur": "ur", "vi": "vi",
    "zh-rCN": "zh-Hans", "zh-rTW": "zh-Hant",
}


def ph(value):
    return sorted(re.findall(r"%(?:\d+\$)?[-+ 0#]*\d*(?:\.\d+)?[sdif]", value))


def xml_escape(s):
    s = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    return s.replace("'", "\\'").replace('"', '\\"')


def strings_escape(s):
    return s.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")


def main():
    check_only = "--check" in sys.argv
    src = json.load(open(os.path.join(HERE, "source.json"), encoding="utf-8"))
    total = sum(len(v) for v in src["android"].values())
    print("source: %d android keys in %d files, %d iOS security keys"
          % (total, len(src["android"]), len(src["ios_security"])))

    problems = []
    for loc in src["locales"]:
        path = os.path.join(HERE, loc + ".json")
        if not os.path.exists(path):
            print("%-7s missing input" % loc)
            continue
        data = json.load(open(path, encoding="utf-8"))
        android = data.get("android", {})
        a_used = a_all = 0
        for fname, entries in src["android"].items():
            body, used_f = [], 0
            for key, english in entries.items():
                if isinstance(english, str) and english.startswith("@string/"):
                    body.append('    <string name="%s" translatable="false">%s</string>' % (key, english))
                    continue
                a_all += 1
                trans = android.get(fname, {}).get(key)
                if isinstance(english, str):
                    if not isinstance(trans, str) or not trans.strip():
                        body.append('    <string name="%s">%s</string>' % (key, xml_escape(english)))
                        continue
                    if ph(english) != ph(trans):
                        problems.append((loc, fname, key, "placeholder", english, trans))
                        body.append('    <string name="%s">%s</string>' % (key, xml_escape(english)))
                        continue
                    used_f += 1
                    body.append('    <string name="%s">%s</string>' % (key, xml_escape(trans)))
                else:
                    untranslated = not isinstance(trans, dict) or not trans
                    if untranslated:
                        trans = english
                    items, ok = [], not untranslated
                    for q, ev in english.items():
                        tv = trans.get(q) if isinstance(trans, dict) else None
                        if not isinstance(tv, str) or not tv.strip():
                            tv, ok = ev, False
                        elif ph(ev) != ph(tv) and not (
                            q in ("zero", "one", "two") and set(ph(ev)) - set(ph(tv)) == {"%d"}
                        ):
                            problems.append((loc, fname, key + "[" + q + "]", "placeholder", ev, tv))
                            tv, ok = ev, False
                        items.append((q, tv))
                    if ok:
                        used_f += 1
                    body.extend(['    <plurals name="%s">' % key]
                                + ['        <item quantity="%s">%s</item>' % (q, xml_escape(tv)) for q, tv in items]
                                + ['    </plurals>'])
            a_used += used_f
            if used_f == 0 and not all(isinstance(v, str) and v.startswith("@string/") for v in entries.values()):
                continue
            if check_only:
                continue
            out_dir = os.path.join(RES, LOCALE_DIR[loc])
            os.makedirs(out_dir, exist_ok=True)
            with open(os.path.join(out_dir, fname), "w", encoding="utf-8", newline="\n") as fh:
                fh.write("\n".join(['<?xml version="1.0" encoding="utf-8"?>', "<resources>"] + body + ["</resources>", ""]))

        ios = data.get("ios_security", {})
        i_used = 0
        lines = ["/* GrapMail — Security center. Keys are the English sentences. */", ""]
        for key in src["ios_security"]:
            val = ios.get(key)
            if not isinstance(val, str) or not val.strip() or ph(key) != ph(val):
                val = key
            else:
                i_used += 1
            lines.append('"%s" = "%s";' % (strings_escape(key), strings_escape(val)))
        lines.append("")
        if i_used and not check_only:
            ldir = os.path.join(IOS, IOS_DIR[loc] + ".lproj")
            os.makedirs(ldir, exist_ok=True)
            with open(os.path.join(ldir, "SecurityCenter.strings"), "w", encoding="utf-8", newline="\n") as fh:
                fh.write("\n".join(lines))
        print("%-7s android %3d/%-3d   ios %3d/%d" % (loc, a_used, a_all, i_used, len(src["ios_security"])))

    print("placeholder problems:", len(problems))
    for p in problems[:20]:
        print("  ", p)
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
