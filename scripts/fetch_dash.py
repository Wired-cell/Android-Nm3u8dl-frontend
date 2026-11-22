#!/usr/bin/env python3
"""Fetch a ClearKey DASH stream: init segment, then every media segment, in order."""
import os, re, sys, time
import urllib.request, urllib.error
import xml.etree.ElementTree as ET
from urllib.parse import urljoin, urlsplit, urlunsplit

MPD = os.environ["VIDEO_URL"]
MAX_H = int(os.environ.get("MAX_HEIGHT", "240"))
OUT = sys.argv[1]
UA = {"User-Agent": "Mozilla/5.0"}
MAX_SEGMENTS = 8000


def fetch(url, tries=5):
    delay, last = 1.0, None
    for attempt in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=90) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            last = e
            if e.code in (403, 404, 410) and attempt >= 1:
                break
            time.sleep(delay)
            delay *= 2
        except Exception as e:
            last = e
            time.sleep(delay)
            delay *= 2
    raise SystemExit(f"fetch failed ({getattr(last, 'code', 'n/a')}): {url}\n{last}")


def iso_dur(s):
    m = re.match(r"^P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?$", (s or "").strip())
    if not m:
        return 0.0
    d, h, mi, se = m.groups()
    return int(d or 0) * 86400 + int(h or 0) * 3600 + int(mi or 0) * 60 + float(se or 0)


QUERY = urlsplit(MPD).query
DIR = MPD[: MPD.rfind("/") + 1]


def abs_url(path):
    if not path:
        return ""
    u = urlsplit(path if path.startswith("http") else urljoin(DIR, path))
    return urlunsplit((u.scheme, u.netloc, u.path, QUERY, ""))


def sub(tmpl, rep_id, bw, number=None, time_=None):
    """Substitute every DASH placeholder. Number and Time are both filled when known."""
    out = tmpl.replace("$RepresentationID$", rep_id).replace("$Bandwidth$", str(bw))
    if number is not None:
        out = re.sub(r"\$Number(?:%0(\d+)d)?\$", lambda m: str(number).zfill(int(m.group(1) or 0)), out)
    if time_ is not None:
        out = re.sub(r"\$Time(?:%0(\d+)d)?\$", lambda m: str(time_).zfill(int(m.group(1) or 0)), out)
    return out


def strip_ns(root):
    for el in root.iter():
        if isinstance(el.tag, str):
            el.tag = el.tag.rsplit("}", 1)[-1]
    return root


def candidates(root):
    cands = []
    for period in root.iter("Period"):
        pbase = DIR
        b = period.find("BaseURL")
        if b is not None and b.text:
            pbase = abs_url(b.text.strip())
        for aset in period.findall("AdaptationSet"):
            ct = (aset.get("contentType") or aset.get("mimeType") or "").lower()
            abase = pbase
            b = aset.find("BaseURL")
            if b is not None and b.text:
                abase = abs_url(b.text.strip())
            for rep in aset.findall("Representation"):
                if "video" not in (rep.get("mimeType") or ct).lower() and rep.get("height") is None:
                    continue
                rbase = abase
                b = rep.find("BaseURL")
                if b is not None and b.text:
                    rbase = abs_url(b.text.strip())
                cands.append((rbase, rep, aset))
    return cands


def build(base, rep, aset, total):
    """Return (urls, sub_manifest, single). urls always has the init segment first."""
    tmpl = rep.find("SegmentTemplate")
    if tmpl is None:
        tmpl = aset.find("SegmentTemplate")
    if tmpl is None:
        return None, (base if base.endswith(".mpd") else None), base

    rep_id, bw = rep.get("id", ""), rep.get("bandwidth", "")
    init, media = tmpl.get("initialization"), tmpl.get("media")
    if not media:
        raise SystemExit("SegmentTemplate without @media")
    timescale = int(tmpl.get("timescale") or 1)
    start = int(tmpl.get("startNumber") or 1)

    # Build (number, time) pairs once, from whichever addressing the manifest uses.
    pairs = []
    tl = tmpl.find("SegmentTimeline")
    if tl is not None:
        seq, t, num = tl.findall("S"), 0, start
        for i, s in enumerate(seq):
            if s.get("t") is not None:
                t = int(s.get("t"))
            d, r = int(s.get("d") or 0), int(s.get("r") or 0)
            if r < 0:
                nxt = seq[i + 1].get("t") if i + 1 < len(seq) else None
                end = int(nxt) if nxt is not None else int(total * timescale)
                n = max(1, -(-(end - t) // d)) if d else 1
            else:
                n = r + 1
            for _ in range(n):
                pairs.append((num, t))
                num += 1
                t += d
    else:
        dur = int(tmpl.get("duration") or 0)
        if not dur or not total:
            raise SystemExit("no SegmentTimeline and no usable @duration/@mediaPresentationDuration")
        for i in range(int(-(-total * timescale // dur))):
            pairs.append((start + i, i * dur))

    if len(pairs) > MAX_SEGMENTS:
        raise SystemExit(f"too many segments ({len(pairs)})")

    urls = [abs_url(sub(init, rep_id, bw))] if init else []
    for num, t in pairs:
        u = sub(media, rep_id, bw, number=num, time_=t)
        if "$" in u:
            raise SystemExit(f"unsubstituted placeholder in media template: {media!r}")
        urls.append(abs_url(u))
    return urls, None, base


def resolve(url, depth=0):
    raw = fetch(url)
    root = strip_ns(ET.fromstring(raw))
    total = iso_dur(root.get("mediaPresentationDuration"))
    kid = re.search(rb'default_KID="([^"]+)"', raw)
    if kid:
        print(f"manifest default_KID: {kid.group(1).decode()}")
    cands = candidates(root)
    if not cands:
        raise SystemExit("no video representations in manifest")
    ok = [c for c in cands if 0 < int(c[1].get("height") or 0) <= MAX_H]
    base, rep, aset = min(
        ok or cands,
        key=lambda c: (int(c[1].get("height") or 10**9), int(c[1].get("bandwidth") or 10**18)),
    )
    print(f"picked: id={rep.get('id')} height={rep.get('height')} bw={rep.get('bandwidth')}")
    urls, sub_mpd, single = build(base, rep, aset, total)
    if urls is None:
        if sub_mpd and depth < 2:
            return resolve(sub_mpd, depth + 1)
        if single:
            return [single]
        raise SystemExit("representation has no segment addressing")
    return urls


def main():
    urls = resolve(MPD)
    print(f"segments to fetch: {len(urls)} (first media url: {urls[1].split('?')[0]})")
    written = 0
    with open(OUT, "wb") as fh:
        for i, u in enumerate(urls, 1):
            blob = fetch(u)
            fh.write(blob)
            written += len(blob)
            if i == 1 or i % 25 == 0 or i == len(urls):
                print(f"  [{i}/{len(urls)}] {written/1e6:.2f} MB")
    if written < 50_000:
        raise SystemExit(f"output suspiciously small: {written} bytes")
    print(f"done: {written} bytes -> {OUT}")


if __name__ == "__main__":
    main()
