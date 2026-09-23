#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.9"
# dependencies = ["phonenumbers==9.0.39"]
# ///
"""Where a number is from, as a small SQLite database the app ships in its assets.

The data is Google's libphonenumber (Apache License 2.0), by way of its Python port: the
geocoding tables that say which place a number prefix belongs to, and the metadata that
says which country a calling code - or, where several countries share one, which part of
it - belongs to. Nothing is looked up online by the app; this runs at build time, and its
output is checked in.

    ./tools/build-geo-db.py                              # -> app/src/main/assets/geo.db
    ./tools/build-geo-db.py --out /tmp/geo.db
    ./tools/build-geo-db.py --lookup +493012345678 +14165551234

The tables:

    regions(prefix INTEGER PRIMARY KEY, region TEXT)
        digits of the international number (no "+") -> ISO 3166 country. The calling code
        itself gives the main country; longer prefixes are only there where a shared code
        belongs to another country (+1 416 is Canada, +44 1481 Guernsey, +7 7 Kazakhstan).

    places(prefix INTEGER PRIMARY KEY, en INTEGER NOT NULL, de INTEGER)
        digits -> the place, as ids into `names`: the English name, and the German one
        where libphonenumber has a different one (München for Munich). A prefix whose
        names are those of the next shorter prefix already in the table is left out.

    names(id INTEGER PRIMARY KEY, name TEXT NOT NULL)
    meta(key TEXT PRIMARY KEY, value TEXT)

A number is looked up by its longest prefix in each table: all of its prefixes are asked
for at once, and the largest that exists wins - a longer prefix of the same number is
always the larger integer.
"""

import argparse
import datetime
import os
import sqlite3
import sys

import phonenumbers
from phonenumbers import geocoder
from phonenumbers.geodata import GEOCODE_DATA

LANGUAGES = ("en", "de")

# what a national number is filled up with to ask which country a prefix belongs to
FILLERS = ("2345678901234", "5550100000000", "0000000000000", "9876543210987",
           "1111111111111")

# how many national digits decide the country within a shared calling code
REGION_PREFIX_DIGITS = 4


def main_region(code):
    regions = phonenumbers.COUNTRY_CODE_TO_REGION_CODE.get(code, ())
    return regions[0] if regions else None


def national_length(region):
    example = phonenumbers.example_number(region)
    return len(str(example.national_number)) if example else 9


def region_of(code, national_prefix, length):
    """Which country a number that starts like this belongs to, or None when none says."""
    for filler in FILLERS:
        digits = (national_prefix + filler)[:max(length, len(national_prefix))]
        try:
            number = phonenumbers.parse("+%d%s" % (code, digits))
        except phonenumbers.NumberParseException:
            continue
        region = phonenumbers.region_code_for_number(number)
        if region:
            return region
    return None


def build_regions():
    """calling code -> main country, plus the prefixes of shared codes that differ."""
    rows = {}

    for code, regions in sorted(phonenumbers.COUNTRY_CODE_TO_REGION_CODE.items()):
        main = regions[0]
        if main == "001":  # non-geographic: satellite phones, international services
            continue
        rows[str(code)] = main

        if len(regions) < 2:
            continue

        length = national_length(main)
        found = {}
        for i in range(10 ** REGION_PREFIX_DIGITS):
            prefix = str(i).zfill(REGION_PREFIX_DIGITS)
            region = region_of(code, prefix, length)
            if region and region != "001":
                found[prefix] = region

        # the same answer for all ten children is one answer for the parent
        for size in range(REGION_PREFIX_DIGITS, 0, -1):
            parents = {}
            for prefix in [p for p in found if len(p) == size]:
                parents.setdefault(prefix[:-1], []).append(prefix)
            for parent, children in parents.items():
                values = {found[c] for c in children}
                if len(children) == 10 and len(values) == 1:
                    for c in children:
                        del found[c]
                    found[parent] = values.pop()

        for prefix, region in found.items():
            if prefix and region != main:
                rows[str(code) + prefix] = region
            elif not prefix:
                rows[str(code)] = region

    return rows


def resolve(table, prefix):
    """What the table says for the longest prefix of `prefix` it has, below it."""
    for size in range(len(prefix) - 1, 0, -1):
        value = table.get(prefix[:size])
        if value is not None:
            return value
    return None


def build_places():
    """prefix -> (en, de), without the entries that repeat what a shorter prefix says."""
    kept = {}

    for prefix in sorted(GEOCODE_DATA, key=lambda p: (len(p), p)):
        names = GEOCODE_DATA[prefix]
        en = names.get("en")
        if not en:
            continue
        de = names.get("de")
        value = (en, de if de and de != en else None)

        if resolve(kept, prefix) == value:
            continue
        kept[prefix] = value

    return kept


def write(path, regions, places):
    if os.path.exists(path):
        os.remove(path)

    db = sqlite3.connect(path)
    db.executescript("""
        PRAGMA page_size = 4096;
        CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);
        CREATE TABLE names (id INTEGER PRIMARY KEY, name TEXT NOT NULL);
        CREATE TABLE regions (prefix INTEGER PRIMARY KEY, region TEXT NOT NULL);
        CREATE TABLE places (prefix INTEGER PRIMARY KEY, en INTEGER NOT NULL, de INTEGER);
    """)

    ids = {}

    def name_id(name):
        if name is None:
            return None
        if name not in ids:
            ids[name] = len(ids) + 1
        return ids[name]

    db.executemany("INSERT INTO regions VALUES (?, ?)",
                   sorted((int(p), r) for p, r in regions.items()))
    db.executemany("INSERT INTO places VALUES (?, ?, ?)",
                   sorted((int(p), name_id(en), name_id(de)) for p, (en, de) in places.items()))
    db.executemany("INSERT INTO names VALUES (?, ?)", [(i, n) for n, i in ids.items()])

    db.executemany("INSERT INTO meta VALUES (?, ?)", [
        ("source", "libphonenumber (Apache License 2.0), via phonenumbers "
                   + phonenumbers.__version__),
        ("phonenumbers_version", phonenumbers.__version__),
        ("built", datetime.date.today().isoformat()),
        ("languages", ",".join(LANGUAGES)),
        ("regions", str(len(regions))),
        ("places", str(len(places))),
    ])
    db.commit()
    db.execute("VACUUM")
    db.close()


def lookup(path, numbers):
    db = sqlite3.connect(path)
    for text in numbers:
        digits = text.lstrip("+")
        candidates = [int(digits[:i]) for i in range(1, len(digits) + 1)]
        marks = ",".join("?" * len(candidates))
        region = db.execute("SELECT region FROM regions WHERE prefix IN (%s) "
                            "ORDER BY prefix DESC LIMIT 1" % marks, candidates).fetchone()
        place = db.execute("SELECT (SELECT name FROM names WHERE id = COALESCE(de, en)), "
                           "(SELECT name FROM names WHERE id = en) FROM places "
                           "WHERE prefix IN (%s) ORDER BY prefix DESC LIMIT 1" % marks,
                           candidates).fetchone()
        reference = geocoder.description_for_number(phonenumbers.parse(text), "de")
        print(f"{text}: region={region[0] if region else None} "
              f"place={place[0] if place else None}  (libphonenumber: {reference!r})")


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--out", default=os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets",
        "geo.db"))
    parser.add_argument("--lookup", nargs="*", help="look numbers up in --out and stop")
    args = parser.parse_args()

    if args.lookup:
        lookup(args.out, args.lookup)
        return

    regions = build_regions()
    places = build_places()
    write(args.out, regions, places)

    print(f"{os.path.normpath(args.out)}: {len(regions)} region prefixes, "
          f"{len(places)} places, {os.path.getsize(args.out):,} bytes", file=sys.stderr)


if __name__ == "__main__":
    main()
