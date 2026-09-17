import os, re, sys

root = sys.argv[1] if len(sys.argv) > 1 else "/tmp/v336/app/src/main/java"
zlit = re.compile(r'SimpleDateFormat\(\s*"([^"]*\'Z\'[^"]*)"')
# A formatter counts as zone-pinned when the zone is set explicitly to UTC/GMT (the old honest
# writers), or to the record zone / Asia/Kolkata (everything since the IST mandate), or when it is
# built by RecordTime.formatter, which pins IST by construction.
# The TimeZone reference may be fully qualified (`java.util.TimeZone.getTimeZone("UTC")`), which an
# earlier version of this pattern did not accept - it then reported five honest UTC writers as
# DISHONEST and the summary was quoted from that. A classifier that cannot see the qualified form
# over-reports the defect it is looking for, which is the worst direction to be wrong in.
tzre = re.compile(
    r'timeZone\s*=\s*(?:(?:java\.util\.)?TimeZone\.getTimeZone\(\s*"(?:UTC|GMT|Asia/Kolkata)"\s*\)'
    r'|recordZone'
    r'|(?:com\.example\.data\.)?RecordTime\.zone)'
)
rtfmt = re.compile(r'RecordTime\.formatter\s*\(|RecordTime\.format\s*\(')

def is_comment_line(src, pos):
    """True when the match sits on a comment line.

    Explanatory comments quote the code they replaced - `// Was Date(x).toString()` - and a scanner
    that cannot tell a comment from a statement reports the fixed site as still broken. A tool that
    cries wolf on its own fix notes gets ignored, which is worse than no tool.
    """
    start = src.rfind("\n", 0, pos) + 1
    line = src[start:src.find("\n", pos)].lstrip()
    return line.startswith("//") or line.startswith("*") or line.startswith("/*")


hits = []
for dp, _, fns in os.walk(root):
    for fn in fns:
        if not fn.endswith(".kt"):
            continue
        p = os.path.join(dp, fn)
        src = open(p, encoding="utf-8").read()
        for m in zlit.finditer(src):
            win = src[m.end():m.end() + 320]
            honest = tzre.search(win) is not None
            if is_comment_line(src, m.start()):
                continue
            line = src[:m.start()].count("\n") + 1
            hits.append((
                os.path.relpath(p, root), line,
                "HONEST-UTC" if honest else "DISHONEST-Z (device zone -> IST digits, 'Z' label)",
            ))

print("%-50s %-6s %s" % ("FILE", "LINE", "VERDICT"))
for f, l, v in sorted(hits, key=lambda x: (x[2], x[0])):
    print("%-50s %-6d %s" % (f, l, v))

d = sum(1 for h in hits if h[2].startswith("DISHONEST"))
print()
print("'Z'-literal formatters: %d   honest UTC: %d   DISHONEST: %d" % (len(hits), len(hits) - d, d))

# Also: naive formatters (no 'Z', no zone) -> device zone, i.e. IST wall time on his phone.
print()
print("=== naive formatters (no 'Z', zone never set -> device default = IST) ===")
# NOTE: the pattern is matched with a lazy [^"]* rather than anything that excludes apostrophes.
# An earlier version used [^"\']*?, which silently skipped every pattern containing a quoted
# literal such as 'T' - "yyyy-MM-dd'T'HH:mm" - so the audit under-reported and an unpinned
# formatter in FuelCostsScreen walked through as clean. Date patterns quote their separators
# routinely; a regex that cannot see them is worse than no regex.
naive = re.compile(r'SimpleDateFormat\(\s*"([^"]*?)"')
seen = 0
for dp, _, fns in os.walk(root):
    for fn in fns:
        if not fn.endswith(".kt"):
            continue
        p = os.path.join(dp, fn)
        src = open(p, encoding="utf-8").read()
        for m in naive.finditer(src):
            fmt = m.group(1)
            if "'Z'" in fmt or "XXX" in fmt or "Z" == fmt[-1:]:
                continue
            win = src[max(0, m.start() - 80):m.end() + 320]
            if tzre.search(win) or rtfmt.search(src[max(0, m.start() - 120):m.end() + 60]):
                continue
            if is_comment_line(src, m.start()):
                continue
            line = src[:m.start()].count("\n") + 1
            print("%-50s %-6d %s" % (os.path.relpath(p, root), line, fmt))
            seen += 1
print("naive: %d" % seen)

# Date.toString() renders in the DEVICE zone in Java's own format, so it is the same defect with no
# SimpleDateFormat to find. An earlier version of this tool only looked for formatters and reported
# the tree clean while DriveBackupScreen printed Date(millis).toString() straight into a Text().
print()
print("=== Date.toString() rendered directly (device zone, Java's own format) ===")
dtostr = re.compile(r'Date\([^)]*\)\.toString\(\)')
found = 0
for dp, _, fns in os.walk(root):
    for fn in fns:
        if not fn.endswith(".kt"):
            continue
        p = os.path.join(dp, fn)
        src = open(p, encoding="utf-8").read()
        for m in dtostr.finditer(src):
            if is_comment_line(src, m.start()):
                continue
            line = src[:m.start()].count("\n") + 1
            print("%-50s %-6d %s" % (os.path.relpath(p, root), line, m.group(0)))
            found += 1
print("Date.toString(): %d" % found)
