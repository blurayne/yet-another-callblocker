#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.9"
# dependencies = ["PyYAML>=6"]
# ///
"""The app's strings, as one YAML file, and back again.

Android keeps a translation per directory: twenty-eight copies of strings.xml, each
holding its own subset of the keys, in its own order, with the German for a button
nowhere near the English for it. This turns that inside out - one entry per key, every
language of it together, and a line saying where in the app it is used, which is the
thing a translator actually needs and the thing the XML never says.

    ./tools/strings.py export                 # res/values*/strings.xml -> translations.yaml
    ./tools/strings.py import                 # translations.yaml -> res/values*/strings.xml
    ./tools/strings.py export --languages en,de
    ./tools/strings.py usage db_build_done    # just where one key is used

What is stored is the text exactly as it stands between the tags - the escapes Android
wants (\\', \\n, \\u2026) and the markup some strings carry (<b>) included. Nothing is
unescaped on the way out and re-escaped on the way in, so a key that nobody edits comes
back byte for byte.

An export of everything and an import straight after it leaves the tree unchanged. That
is the test worth running after changing anything in here.
"""

import argparse
import os
import re
import sys
from collections import OrderedDict

import yaml

# an OrderedDict is a map like any other; without this PyYAML writes it as a Python object
yaml.add_representer(OrderedDict, lambda dumper, data:
                     dumper.represent_mapping("tag:yaml.org,2002:map", data.items()))

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
YAML_FILE = os.path.join(ROOT, "translations.yaml")

# the language whose file holds every key and decides the order the rest are written in
BASE = "en"

STRING_RE = re.compile(
    r'<string(?P<attrs>\s[^>]*?)>(?P<text>.*?)</string>', re.S)
PLURALS_RE = re.compile(
    r'<plurals(?P<attrs>\s[^>]*?)>(?P<body>.*?)</plurals>', re.S)
ITEM_RE = re.compile(
    r'<item\s+quantity="(?P<quantity>\w+)"\s*>(?P<text>.*?)</item>', re.S)
NAME_RE = re.compile(r'name="(?P<name>[\w.]+)"')

# what an attribute in a layout or a preference screen makes the string into
ATTRS = {
    "title": "title",
    "summary": "summary",
    "text": "text",
    "hint": "hint",
    "contentDescription": "description",
    "placeholderText": "placeholder",
    "dialogTitle": "dialog title",
    "dialogMessage": "dialog text",
    "label": "title bar",
}

# and what a call in the code does with it
CALLS = [
    (re.compile(r'Toast\s*\.\s*makeText'), "toast"),
    (re.compile(r'\.setTitle\s*\('), "dialog title"),
    (re.compile(r'\.setMessage\s*\('), "dialog text"),
    (re.compile(r'\.setSummary\s*\('), "summary"),
    (re.compile(r'\.setError\s*\('), "field error"),
    (re.compile(r'buildLog\s*\.\s*line|log\s*\.\s*line'), "build log"),
    (re.compile(r'\.setHint\s*\('), "hint"),
    (re.compile(r'setContentTitle|setContentText|Notification'), "notification"),
]


def language_of(directory):
    """en for values/, de for values-de/, pt-rBR for values-pt-rBR/."""
    name = os.path.basename(directory)

    if name == "values":
        return BASE

    return name[len("values-"):]


def string_files():
    """Every strings.xml there is, the base one first."""
    found = {}

    for name in sorted(os.listdir(RES)):
        path = os.path.join(RES, name, "strings.xml")

        if name.startswith("values") and os.path.isfile(path):
            found[language_of(os.path.join(RES, name))] = path

    return found


def read(path):
    """What one strings.xml holds: {key: raw text} and {key: {quantity: raw text}}."""
    with open(path, encoding="utf-8") as handle:
        source = handle.read()

    values = OrderedDict()

    for match in STRING_RE.finditer(source):
        name = NAME_RE.search(match.group("attrs"))
        if name:
            values[name.group("name")] = match.group("text")

    for match in PLURALS_RE.finditer(source):
        name = NAME_RE.search(match.group("attrs"))
        if not name:
            continue

        items = OrderedDict()
        for item in ITEM_RE.finditer(match.group("body")):
            items[item.group("quantity")] = item.group("text")

        values[name.group("name")] = items

    return values


def order_of(path):
    """The keys of a file in the order the file has them."""
    return list(read(path).keys())


def usages():
    """Where each key is used: {key: [short description, ...]}."""
    found = {}

    def note(key, where):
        found.setdefault(key, [])
        if where not in found[key]:
            found[key].append(where)

    # the layouts and the preference screens, where the attribute says what it becomes,
    # and the manifest, where the label of an activity is its title bar
    paths = [os.path.join(base, name)
             for base, _, names in os.walk(RES)
             for name in names
             if name.endswith(".xml") and name != "strings.xml"]

    paths.append(os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml"))

    for path in paths:
        if not os.path.exists(path):
            continue

        name = os.path.basename(path)

        with open(path, encoding="utf-8") as handle:
            text = handle.read()

        for match in re.finditer(r'([\w:]+)="@(string|plurals)/([\w.]+)"', text):
            attr = match.group(1).split(":")[-1]

            if attr == "text" and match.group(1).startswith("tools:"):
                continue  # only ever seen in the editor

            note(match.group(3), "%s %s" % (name, ATTRS.get(attr, attr)))

        # an entry of a list to choose from, which is a name on its own line
        for match in re.finditer(r'<item>@(?:string|plurals)/([\w.]+)</item>', text):
            note(match.group(1), "%s list entry" % name)

    # and the code, where the call around it says what it becomes
    java = os.path.join(ROOT, "app", "src", "main", "java")

    for base, _, names in os.walk(java):
        for name in names:
            if not name.endswith(".java"):
                continue

            path = os.path.join(base, name)
            with open(path, encoding="utf-8") as handle:
                lines = handle.readlines()

            for index, line in enumerate(lines):
                for match in re.finditer(r'R\.(?:string|plurals)\.([\w.]+)', line):
                    # the call is on this line or the one it is continued from
                    context = "".join(lines[max(0, index - 1):index + 1])

                    what = ""
                    for pattern, label in CALLS:
                        if pattern.search(context):
                            what = " " + label
                            break

                    note(match.group(1), "%s%s" % (name[:-len(".java")], what))

    return found


def comment_of(key, where, limit=3):
    """One short line saying where it is used, or that it isn't."""
    places = where.get(key)

    if not places:
        return "unused"

    if len(places) <= limit:
        return "; ".join(places)

    return "; ".join(places[:limit]) + "; +%d more" % (len(places) - limit)


def export(languages=None, path=YAML_FILE):
    files = string_files()

    if languages:
        files = OrderedDict((lang, files[lang]) for lang in languages if lang in files)

    if BASE not in files:
        sys.exit("there is no %s: nothing to take the order from" % BASE)

    values = {lang: read(file) for lang, file in files.items()}
    where = usages()

    # the base file's order, then whatever only the others have
    keys = order_of(files[BASE])
    for lang, holds in values.items():
        for key in holds:
            if key not in keys:
                keys.append(key)

    rest = sorted(lang for lang in values if lang not in (BASE, "de"))

    document = OrderedDict()

    for key in keys:
        entry = OrderedDict()
        entry["comment"] = comment_of(key, where)

        for lang in [BASE, "de"] + rest:
            if lang in values and key in values[lang]:
                entry[lang] = values[lang][key]

        document[key] = entry

    with open(path, "w", encoding="utf-8") as handle:
        handle.write("# The app's strings, one entry per key, every language together.\n")
        handle.write("# Written by tools/strings.py - edit the text here and import it back.\n")
        handle.write("#\n")
        handle.write("#   ./tools/strings.py import\n")
        handle.write("#\n")
        handle.write("# comment says where the key is used; it is not written back.\n")
        handle.write("# The text is exactly what stands between the tags in strings.xml:\n")
        handle.write("# Android's own escapes (\\' \\n \\u2026) and markup (<b>) and all.\n\n")

        yaml.dump(document, handle, allow_unicode=True, default_flow_style=False,
                  sort_keys=False, width=10 ** 9)

    print("%s: %d keys, %d languages" % (
        os.path.relpath(path, ROOT), len(document), len(values)))


def write_back(path, values):
    """Puts the text of each key back into one strings.xml, leaving everything else alone."""
    with open(path, encoding="utf-8") as handle:
        source = handle.read()

    changed = [0]

    def replace_string(match):
        name = NAME_RE.search(match.group("attrs"))
        if not name or name.group("name") not in values:
            return match.group(0)

        text = values[name.group("name")]
        if not isinstance(text, str) or text == match.group("text"):
            return match.group(0)

        changed[0] += 1

        return "<string%s>%s</string>" % (match.group("attrs"), text)

    def replace_plurals(match):
        name = NAME_RE.search(match.group("attrs"))
        if not name or name.group("name") not in values:
            return match.group(0)

        items = values[name.group("name")]
        if not isinstance(items, dict):
            return match.group(0)

        def replace_item(item):
            text = items.get(item.group("quantity"))

            if text is None or text == item.group("text"):
                return item.group(0)

            changed[0] += 1

            return '<item quantity="%s">%s</item>' % (item.group("quantity"), text)

        body = ITEM_RE.sub(replace_item, match.group("body"))

        return "<plurals%s>%s</plurals>" % (match.group("attrs"), body)

    updated = STRING_RE.sub(replace_string, source)
    updated = PLURALS_RE.sub(replace_plurals, updated)

    if updated != source:
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(updated)

    return changed[0]


def import_(path=YAML_FILE, languages=None):
    if not os.path.exists(path):
        sys.exit("%s isn't there; run export first" % os.path.relpath(path, ROOT))

    with open(path, encoding="utf-8") as handle:
        document = yaml.safe_load(handle) or {}

    files = string_files()

    by_language = {}
    missing = set()

    for key, entry in document.items():
        if not isinstance(entry, dict):
            continue

        for lang, text in entry.items():
            if lang == "comment":
                continue

            if languages and lang not in languages:
                continue

            if lang not in files:
                missing.add(lang)
                continue

            by_language.setdefault(lang, {})[key] = text

    for lang in sorted(by_language):
        changed = write_back(files[lang], by_language[lang])

        if changed:
            print("%s: %d changed" % (os.path.relpath(files[lang], ROOT), changed))

    if missing:
        print("no strings.xml for: %s (a new language needs its directory first)"
              % ", ".join(sorted(missing)), file=sys.stderr)


def usage(keys):
    where = usages()

    for key in keys:
        print("%s: %s" % (key, comment_of(key, where, limit=99)))


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    commands = parser.add_subparsers(dest="command", required=True)

    out = commands.add_parser("export", help="strings.xml -> YAML")
    out.add_argument("--languages", help="only these, comma separated (en,de)")
    out.add_argument("--file", default=YAML_FILE)

    back = commands.add_parser("import", help="YAML -> strings.xml")
    back.add_argument("--languages", help="only these, comma separated")
    back.add_argument("--file", default=YAML_FILE)

    used = commands.add_parser("usage", help="where a key is used")
    used.add_argument("keys", nargs="+")

    args = parser.parse_args()

    languages = args.languages.split(",") if getattr(args, "languages", None) else None

    if args.command == "export":
        export(languages, args.file)
    elif args.command == "import":
        import_(args.file, languages)
    else:
        usage(args.keys)


if __name__ == "__main__":
    main()
