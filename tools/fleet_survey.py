# Surveys every bus on the road right now and checks it against the app's
# fleet table (app/src/main/assets/fleet.tsv), so the "what bus is coming"
# lookup can be kept up to date as operators add buses.
#
#   AT_API_KEY=... python tools/fleet_survey.py
#
# Prints the label formats in use, a sample of vehicles per operator prefix,
# and every label the fleet table doesn't recognise, grouped into ranges.
import collections, json, os, re, sys, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
FLEET = os.path.join(HERE, "..", "app", "src", "main", "assets", "fleet.tsv")
KEY = os.environ.get("AT_API_KEY", "")


def api(path):
    req = urllib.request.Request("https://api.at.govt.nz" + path,
                                 headers={"Ocp-Apim-Subscription-Key": KEY,
                                          "Accept": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=60))


def load_fleet():
    """Rows of (prefixes, first, last, model, power, decks)."""
    rows = []
    if not os.path.exists(FLEET):
        return rows
    for line in open(FLEET, encoding="utf-8"):
        line = line.rstrip("\n")
        if not line or line.startswith("#"):
            continue
        p = line.split("\t")
        rows.append((p[0].split(","), int(p[1]), int(p[2]), p[3], p[4], p[5]))
    return rows


def split_label(label):
    """'TR3881' -> ('TR', 3881); 'NB 3563' -> ('NB', 3563)."""
    m = re.match(r"^\s*([A-Za-z]*)\s*0*(\d+)", label or "")
    return (m.group(1).upper(), int(m.group(2))) if m else ("", None)


def lookup(fleet, prefix, num):
    for pre, a, b, model, power, decks in fleet:
        if prefix in pre and a <= num <= b:
            return model, power
    return None


def ranges(nums):
    nums = sorted(set(nums))
    out, start, prev = [], None, None
    for n in nums:
        if start is None:
            start = prev = n
        elif n <= prev + 3:
            prev = n
        else:
            out.append((start, prev)); start = prev = n
    if start is not None:
        out.append((start, prev))
    return ", ".join(str(a) if a == b else "%d-%d" % (a, b) for a, b in out)


def main():
    if not KEY:
        print("No AT_API_KEY, skipping the survey")
        return
    fleet = load_fleet()
    j = api("/realtime/legacy/vehiclelocations")
    ents = j.get("response", {}).get("entity", [])
    by_prefix = collections.defaultdict(list)
    keys = collections.Counter()
    for e in ents:
        v = e.get("vehicle") or {}
        info = v.get("vehicle") or {}
        keys.update(info.keys())
        vid = str(info.get("id", ""))
        if vid.startswith("59"):                        # trains
            continue
        label = re.sub(r"\s+", " ", str(info.get("label", ""))).strip()
        route = ((v.get("trip") or {}).get("route_id") or "").split("-")[0]
        by_prefix[split_label(label)[0]].append((label, vid, info.get("license_plate", ""), route))

    print("vehicles: %d, vehicle descriptor keys: %s" % (len(ents), dict(keys)))
    if "--raw" in sys.argv:
        buses = [e for e in ents if not str(((e.get("vehicle") or {}).get("vehicle") or {}).get("id", "")).startswith("59")]
        for e in buses[:3]:
            print(json.dumps(e))
    print()
    print("%-6s %5s  %-44s %s" % ("prefix", "count", "fleet numbers", "sample (label | id | plate | route)"))
    for pre, vs in sorted(by_prefix.items(), key=lambda kv: -len(kv[1])):
        nums = [split_label(l)[1] for l, _, _, _ in vs if split_label(l)[1] is not None]
        sample = "; ".join("%s | %s | %s | %s" % s for s in vs[:4])
        print("%-6s %5d  %-44s %s" % (pre or "-", len(vs), ranges(nums)[:44], sample))

    print()
    known = 0
    total = 0
    print("every range on the road (prefix, fleet numbers, count, top routes, fleet table model):")
    for pre, vs in sorted(by_prefix.items()):
        runs = collections.defaultdict(list)             # range start -> [(n, route)]
        nums = sorted((split_label(l)[1], r) for l, _, _, r in vs if split_label(l)[1] is not None)
        start = prev = None
        for n, r in nums:
            if start is None or n > prev + 3:
                start = n
            prev = n
            runs[start].append((n, r))
        for st, items in sorted(runs.items()):
            lo, hi = items[0][0], items[-1][0]
            total += len(items)
            hit = lookup(fleet, pre, lo)
            known += sum(1 for n, _ in items if lookup(fleet, pre, n))
            routes = collections.Counter(r for _, r in items if r).most_common(5)
            print("  %-4s %4d-%-4d %3d  %-34s %s" % (pre or "-", lo, hi, len(items),
                  " ".join("%s(%d)" % rc for rc in routes), "%s / %s" % hit if hit else "?"))
    print("fleet table recognises %d of %d labelled buses" % (known, total))

if __name__ == "__main__":
    main()
