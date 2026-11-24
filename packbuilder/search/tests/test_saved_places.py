"""Tests of packbuilder/search/saved_places.py (docs/architecture-saved-places.md 9 and 10.1 item 16).

    python -m pytest packbuilder/search/tests/test_saved_places.py

The three fixtures are the ones android/core/search's SavedPlacesTest reads (paths from the
repository root), so the Python and Kotlin readers, name keys and matching are checked against
the same data. Made-up places at public points in Erie only.
"""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / "packbuilder" / "search"))
import saved_places as sp  # noqa: E402

FIX = REPO / "android" / "core" / "search" / "src" / "test" / "resources" / "saved"
V1 = FIX / "saved-places-v1.json"
MATCHING = json.loads((FIX / "saved-matching.json").read_text(encoding="utf-8"))
NAME_KEYS = json.loads((FIX / "saved-name-keys.json").read_text(encoding="utf-8"))["names"]
QUOKKA = {"id": "s1234567890abcdef", "kind": "other", "name": "Quokka Shop", "lat": 42.06875, "lon": -80.09813,
          "place_name": "Millcreek Mall", "subtitle": "5800 Peach Street, Erie", "category": "mall"}


def doc(places, **top):
    d = {"format": "beamline-saved-places/1", "show_on_map": True, "places": places}
    d.update(top)
    return json.dumps(d)


def other(i: int, name: str, **kw):
    p = {"id": "s%016x" % i, "kind": "other", "name": name, "lat": 42.06875, "lon": -80.09813, "place_name": "Millcreek Mall"}
    p.update(kw)
    return p


def test_fixture_v1_loads_in_store_order():
    places = sp.load(V1)
    assert [p["name"] for p in places] == ["Home", "Work", "Quokka Shop"]
    home = places[0]
    assert home == {"id": "s0a1b2c3d4e5f6071", "kind": "home", "name": "Home", "lat": 42.13646, "lon": -80.08718,
                    "place_name": "Erie Maritime Museum", "subtitle": "100 East Front Street, Erie", "category": "museum"}
    assert "subtitle" not in places[1] and places[1]["place_name"] == "42.08418, -80.17321"


@pytest.mark.parametrize("case", MATCHING["cases"], ids=lambda c: ascii(c["typed"]))
def test_matching_equals_the_shared_cases(case):
    places = sp.load_text(json.dumps(MATCHING["file"]))
    got = [p["name"] for p in sp.matching(places, case["typed"], MATCHING["rules"])]
    assert got == case["expect"]


def test_matching_never_more_than_max_rows():
    places = sp.load_text(json.dumps(MATCHING["file"]))
    for typed in ("m", "mo", "o", "s", "h"):
        assert len(sp.matching(places, typed, MATCHING["rules"])) <= 3


@pytest.mark.parametrize("pair", NAME_KEYS, ids=lambda p: ascii(p["name"])[:30])
def test_name_key_equals_the_shared_list(pair):
    assert sp.name_key(pair["name"]) == pair["key"]


def test_name_key_examples_of_sp26():
    assert sp.name_key("Mom's") == sp.name_key("moms") == "moms"
    assert sp.name_key("Café") == sp.name_key("cafe") == "cafe"
    assert sp.name_key("Дом") == "дом"
    assert sp.name_key("家") == "家"
    assert sp.name_key("\U0001F3E0") != sp.name_key("\U0001F3E1")
    assert sp.name_key("***") == ""
    assert sp.name_key("HOME") == sp.name_key("Home!") == "home"
    assert sp.name_key("work ") == "work"
    assert sp.name_key("Äpfel") == "apfel"
    # the zero-width joiner of a family emoji is a format character: dropped
    assert sp.name_key("\U0001F468‍\U0001F469‍\U0001F467‍\U0001F466") == "\U0001F468\U0001F469\U0001F467\U0001F466"


def test_order_by_name_key_then_id():
    places = sp.load_text(doc([other(3, "Mom's"), other(2, "apple"), other(1, "Äpfel"),
                               {"id": "s00000000000000ff", "kind": "work", "name": "Work", "lat": 42.08418, "lon": -80.17321,
                                "place_name": "42.08418, -80.17321"}]))
    assert [p["name"] for p in places] == ["Work", "Äpfel", "apple", "Mom's"]


@pytest.mark.parametrize("text", [
    '{"format": "beamline-saved-places/1", "places": [{"id": "s1234567890abcdef", "name": "Quokka Shop", "lat": 42.0687',
    doc([dict(QUOKKA, id="s0000000000000001", kind="home"), dict(QUOKKA, id="s0000000000000002", kind="home")]),
    doc([QUOKKA, dict(QUOKKA, name="Other")]),
    doc([dict(QUOKKA, id="x12")]),
    doc([dict(QUOKKA, lat=91)]),
    doc([dict(QUOKKA, name="")]),
    doc([dict(QUOKKA, kind="garage")]),
    json.dumps({"show_on_map": True, "places": [QUOKKA]}),
    doc([dict(QUOKKA, lat="42.06875")]),
    doc([dict(QUOKKA, subtitle=None)]),
    doc([QUOKKA], show_on_map="yes"),
    doc([dict(QUOKKA, lat=True)]),
    '{"format": "beamline-saved-places/1", "places": [{"id": "s1234567890abcdef", "kind": "other", "name": "Q", '
    '"lat": NaN, "lon": -80.09813, "place_name": "M"}]}',
    json.dumps([QUOKKA]),
    doc([QUOKKA], format="beamline-saved-places/x"),
    doc([QUOKKA], format="other-format/1"),
])
def test_broken_structure(text):
    with pytest.raises(sp.SavedPlacesError) as e:
        sp.load_text(text)
    assert e.value.kind == "broken"
    # the error never carries the file's text (PV2)
    assert "quokka" not in str(e.value).lower() and "42.068" not in str(e.value)


def test_newer_format():
    with pytest.raises(sp.SavedPlacesError) as e:
        sp.load_text(doc([QUOKKA], format="beamline-saved-places/2"))
    assert e.value.kind == "newer"


def test_read_as_they_are():
    long_name = "x" * 60
    places = sp.load_text(doc(
        [other(1, long_name), other(2, "Mom's"), other(3, "moms"), other(4, "Home"),
         {"id": "s00000000000000aa", "kind": "home", "name": "House", "lat": 42.13646, "lon": -80.08718,
          "place_name": "Erie Maritime Museum"}]
        + [other(0x1000 + i, "Place %03d" % i) for i in range(120)]))
    names = [p["name"] for p in places]
    assert names[0] == "Home" and places[0]["kind"] == "home"   # a home reads as "Home" whatever it says
    assert long_name in names and "Mom's" in names and "moms" in names
    assert sum(1 for p in places if p["kind"] == "other") == 124


def test_unknown_keys_are_ignored_and_notice_allowed():
    places = sp.load_text(doc([dict(QUOKKA, color="blue")], notice="broken", extra={"a": 1}))
    assert places[0]["name"] == "Quokka Shop" and "color" not in places[0]


def test_check_command_prints_counts_only(tmp_path):
    ok = subprocess.run([sys.executable, str(REPO / "packbuilder" / "search" / "saved_places.py"), "--check", str(V1)],
                        capture_output=True, text=True)
    assert ok.returncode == 0
    assert "3 places" in ok.stdout and "home 1, work 1, other 1" in ok.stdout
    for word in ("Quokka", "Millcreek", "Maritime", "42.", "80."):
        assert word not in ok.stdout + ok.stderr
    bad = tmp_path / "saved-places.json"
    bad.write_text("{", encoding="utf-8")
    r = subprocess.run([sys.executable, str(REPO / "packbuilder" / "search" / "saved_places.py"), "--check", str(bad)],
                       capture_output=True, text=True)
    assert r.returncode == 1 and "broken" in r.stdout
