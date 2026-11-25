"""Sweep (v0.7) — the Thoroughness Law as machinery.

The law (Ethuram, 2026-11-25 ~11:03 WAT, sibling to the
Proactivity Law): when Ethuram says "go search for something",
there must be no loopholes. He finds opportunities by casually
scrolling an X feed that follows nobody — so a sweep that misses
what a five-minute scroll finds is worthless ("if I myself can
find it, why would I need an AI").

A sweep therefore:
1. Queries MULTIPLE independent surfaces in one pass — never one
   search box. Live surfaces (all keyless, stdlib only):
   DuckDuckGo HTML (the web, several query phrasings), Devpost's
   public hackathon API, GitHub's public search API, Hacker News
   (Algolia). Attempted-and-honest surfaces: DoraHacks (bot-walled
   from this host today) and X via public Nitter (answers but
   serves no tweet content — X has no working keyless route).
2. Dedups against a persisted known-items record
   (<workspace>/sweep_known.json: title+URL fingerprints from every
   prior sweep). The report separates NEW from already-known; a
   second identical sweep reports ~0 new.
3. Carries a MANDATORY coverage note: surfaces checked (with
   counts), surfaces NOT checked + the reason, and the query
   phrasings used. A surface that errors is named in the note —
   never silently skipped. validate_sweep_report() enforces the
   shape; a report without a coverage note fails validation.

Report shape (standard + coverage):

    # Lille — sweep report
    STATUS: DONE | BLOCKED
    Cost: $0 — keyless routes only
    When / Topic / Query phrasings used
    ## New (N)          — [surface] title — url
    ## Already known (M)
    ## Coverage note    — Surfaces checked: … / Surfaces NOT checked: …
"""
import hashlib, html as html_mod, json, os, re, time
import urllib.error, urllib.parse, urllib.request

from .commitments import COST_LINE

UA = {"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) Lille/0.7 "
                    "(open-source; keyless sweep)"}
_STOPWORDS = {"the", "a", "an", "for", "open", "to", "of", "in",
              "on", "and", "or", "with", "solo", "entry"}


class SurfaceError(RuntimeError):
    """A surface could not be checked; the reason goes in the
    coverage note verbatim."""


# ---------- HTTP (injectable for tests) ----------

def _http_get(url, timeout=25):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.status, r.read(1_500_000).decode("utf-8", "replace")


def _http_json(url, timeout=25):
    status, body = _http_get(url, timeout=timeout)
    if status != 200:
        raise SurfaceError(f"HTTP {status}")
    return json.loads(body)


# ---------- query phrasings ----------

def keywords(topic):
    """The load-bearing words of a topic, for structured surfaces
    whose search boxes choke on full sentences."""
    words = [w for w in re.findall(r"[a-z0-9]+", topic.lower())
             if w not in _STOPWORDS and len(w) > 2]
    return " ".join(words[:4]) or topic.strip()


def generate_phrasings(topic, year=None):
    """Several phrasings per topic — one phrasing is a loophole.
    Deterministic for a given year."""
    year = year or time.strftime("%Y")
    topic = " ".join(topic.split())
    raw = [topic,
           f"{topic} {year}",
           f"{topic} apply online",
           f"{topic} registration open",
           f'"{topic}"']
    out = []
    for p in raw:
        if p not in out:
            out.append(p)
    return out


# ---------- surfaces ----------

def _unwrap_ddg(href):
    if "uddg=" in href:
        q = urllib.parse.urlparse(href).query
        params = urllib.parse.parse_qs(q)
        if params.get("uddg"):
            return params["uddg"][0]
    if href.startswith("//"):
        return "https:" + href
    return href


def surface_web(phrasings, http_get):
    items, seen = [], set()
    for phrase in phrasings:
        url = "https://html.duckduckgo.com/html/?" + urllib.parse.urlencode(
            {"q": phrase})
        status, body = http_get(url)
        if status != 200:
            raise SurfaceError(f"DuckDuckGo HTML answered HTTP {status}")
        for href, title in re.findall(
                r'class="result__a" href="([^"]+)">(.*?)</a>', body):
            real = _unwrap_ddg(html_mod.unescape(href))
            title = html_mod.unescape(re.sub(r"<[^>]+>", "", title)).strip()
            if real not in seen and title:
                seen.add(real)
                items.append({"title": title, "url": real, "snippet": ""})
    return items


def surface_devpost(topic, http_json):
    q = urllib.parse.urlencode(
        {"search": keywords(topic)}) + "&status[]=upcoming&status[]=open"
    data = http_json(f"https://devpost.com/api/hackathons?{q}")
    items = []
    for h in data.get("hackathons", []):
        url = h.get("url") or h.get("hackathon_url") or ""
        if not url and h.get("id"):
            url = f"https://devpost.com/hackathons/{h['id']}"
        loc = (h.get("displayed_location") or {}).get("location", "")
        items.append({"title": h.get("title", ""), "url": url,
                      "snippet": f"{loc} · {h.get('open_state', '')}".strip(" ·")})
    return items


def surface_dorahacks(topic, http_get):
    url = ("https://dorahacks.io/api/hackathon/list?"
           + urllib.parse.urlencode({"paginate": 10, "page": 1}))
    try:
        status, body = http_get(url)
    except (urllib.error.URLError, OSError) as e:
        raise SurfaceError(f"request failed: {e}")
    if status != 200 or "Human Verification" in body \
            or "<!DOCTYPE html" in body[:200]:
        raise SurfaceError(
            f"bot verification wall (HTTP {status} + human-verification "
            "page) — DoraHacks serves no keyless public results to "
            "this host")
    try:
        data = json.loads(body)
    except ValueError:
        raise SurfaceError("response was not JSON")
    items = []
    rows = data.get("data", data) if isinstance(data, dict) else data
    for h in rows if isinstance(rows, list) else []:
        items.append({"title": h.get("title", ""),
                      "url": h.get("url", ""), "snippet": ""})
    return items


def surface_github(topic, http_json):
    q = urllib.parse.urlencode(
        {"q": keywords(topic), "per_page": 10, "sort": "updated"})
    data = http_json(f"https://api.github.com/search/repositories?{q}")
    return [{"title": r.get("full_name", ""), "url": r.get("html_url", ""),
             "snippet": (r.get("description") or "")[:160]}
            for r in data.get("items", [])]


def surface_hackernews(topic, http_json):
    q = urllib.parse.urlencode({"query": keywords(topic),
                                "hitsPerPage": 10})
    data = http_json(f"https://hn.algolia.com/api/v1/search?{q}")
    items = []
    for h in data.get("hits", []):
        title = h.get("title") or h.get("story_title") or ""
        url = h.get("url") or \
            f"https://news.ycombinator.com/item?id={h.get('objectID')}"
        items.append({"title": title, "url": url, "snippet": ""})
    return items


def surface_x(topic, http_get):
    q = urllib.parse.urlencode({"f": "tweets", "q": keywords(topic)})
    status, body = http_get(f"https://nitter.net/search?{q}")
    tweets = re.findall(r'class="tweet-content[^"]*"[^>]*>(.*?)</div>',
                        body, re.S)
    if status != 200 or not tweets:
        raise SurfaceError(
            "public Nitter instance answered HTTP "
            f"{status} but returned no tweet content — X has no "
            "working keyless public route from this host, and no "
            "credential will be collected to force one (no-keys law)")
    return [{"title": re.sub(r"<[^>]+>", "", t).strip()[:120],
             "url": "https://nitter.net/search?" + q, "snippet": ""}
            for t in tweets]


def default_surfaces(http_get, http_json):
    """[(name, callable)] in sweep order. Callables take the topic
    (web takes the phrasing list) and return item dicts, or raise
    SurfaceError with the coverage-note reason."""
    return [
        ("web (DuckDuckGo HTML)", lambda topic, phr: surface_web(phr, http_get)),
        ("devpost", lambda topic, phr: surface_devpost(topic, http_json)),
        ("dorahacks", lambda topic, phr: surface_dorahacks(topic, http_get)),
        ("github", lambda topic, phr: surface_github(topic, http_json)),
        ("hackernews", lambda topic, phr: surface_hackernews(topic, http_json)),
        ("x (via public Nitter)", lambda topic, phr: surface_x(topic, http_get)),
    ]


# ---------- known-items record (dedup) ----------

def normalize_url(url):
    u = (url or "").strip().lower()
    u = re.sub(r"^https?://", "", u)
    u = re.sub(r"^www\.", "", u)
    u = u.split("#")[0]
    if "?" in u:
        base, qs = u.split("?", 1)
        keep = [p for p in qs.split("&")
                if not p.split("=")[0].startswith("utm_")]
        u = base + ("?" + "&".join(keep) if keep else "")
    return u.rstrip("/")


def fingerprint(item):
    title = re.sub(r"[^a-z0-9]+", " ", (item.get("title") or "").lower())
    title = " ".join(title.split())
    raw = f"{title}|{normalize_url(item.get('url', ''))}"
    return hashlib.sha1(raw.encode()).hexdigest()[:16]


class KnownItems:
    """Persisted dedup record: every item any sweep has ever seen,
    by fingerprint. Atomic writes, like the other stores."""

    def __init__(self, workspace_root):
        self.path = os.path.join(workspace_root, "sweep_known.json")
        self.items = {}
        if os.path.isfile(self.path):
            try:
                with open(self.path) as f:
                    self.items = json.load(f)
            except Exception:
                self.items = {}

    def save(self):
        tmp = self.path + ".tmp"
        with open(tmp, "w") as f:
            json.dump(self.items, f, indent=2)
        os.replace(tmp, self.path)

    def __contains__(self, fp):
        return fp in self.items

    def __len__(self):
        return len(self.items)


# ---------- the sweep ----------

def _iso(ts):
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(ts))


def run_sweep(topic, workspace_root, surfaces=None, year=None,
              http_get=None, http_json=None, clock=None):
    """Run one sweep. Returns (report_text, summary dict).
    Also writes the report into <workspace>/reports/."""
    clock = clock or time.time
    now = clock()
    os.makedirs(workspace_root, exist_ok=True)  # fresh workspace is legal
    http_get = http_get or _http_get
    http_json = http_json or _http_json
    surfaces = surfaces if surfaces is not None else \
        default_surfaces(http_get, http_json)
    phrasings = generate_phrasings(topic, year=year)

    checked, not_checked, found = [], [], {}
    for name, fn in surfaces:
        try:
            items = fn(topic, phrasings)
        except Exception as e:  # a surface error is coverage data
            reason = str(e) or type(e).__name__
            not_checked.append({"surface": name, "reason": reason[:220]})
            continue
        checked.append({"surface": name, "count": len(items)})
        for it in items:
            if not it.get("title") or not it.get("url"):
                continue
            fp = fingerprint(it)
            if fp in found:
                if name not in found[fp]["surfaces"]:
                    found[fp]["surfaces"].append(name)
            else:
                found[fp] = {**it, "surfaces": [name], "fp": fp}

    known = KnownItems(workspace_root)
    new_items, known_items = [], []
    for fp, it in found.items():
        (known_items if fp in known else new_items).append(it)
        rec = known.items.get(fp) or {
            "title": it["title"], "url": it["url"],
            "source": ", ".join(it["surfaces"]),
            "first_seen": _iso(now), "topics": []}
        if topic not in rec["topics"]:
            rec["topics"].append(topic)
        rec["last_seen"] = _iso(now)
        known.items[fp] = rec
    known.save()

    status = "DONE" if checked else "BLOCKED"
    lines = ["# Lille — sweep report",
             f"STATUS: {status}",
             COST_LINE,
             f"When: {_iso(now)}",
             f"Topic: {topic}",
             "Query phrasings used:"]
    lines += [f"- {p}" for p in phrasings]
    lines.append("")
    lines.append(f"## New ({len(new_items)})")
    lines += [f"- [{', '.join(it['surfaces'])}] {it['title']} — {it['url']}"
              + (f" — {it['snippet']}" if it.get("snippet") else "")
              for it in new_items] or ["- (none)"]
    lines.append("")
    lines.append(f"## Already known ({len(known_items)})")
    lines += [f"- [{', '.join(it['surfaces'])}] {it['title']} — {it['url']}"
              for it in known_items] or ["- (none)"]
    lines.append("")
    lines.append("## Coverage note")
    if checked:
        lines.append("Surfaces checked: " + "; ".join(
            f"{c['surface']} ({c['count']} results)" for c in checked))
    else:
        lines.append("Surfaces checked: none — every surface failed; "
                     "this sweep is BLOCKED, not quietly empty")
    if not_checked:
        lines.append("Surfaces NOT checked: " + "; ".join(
            f"{n['surface']} — {n['reason']}" for n in not_checked))
    else:
        lines.append("Surfaces NOT checked: none")
    lines.append(f"Dedup: {len(known_items)} of {len(found)} unique "
                 f"results were already in the known-items record "
                 f"(record now holds {len(known)} items across all "
                 "sweeps).")
    if status == "BLOCKED":
        lines.append("Blocker: no surface could be checked at all — "
                     "the sweep reports that plainly instead of "
                     "returning an empty list dressed as a result.")
    report = "\n".join(lines) + "\n"
    problems = validate_sweep_report(report)
    if problems:  # our own builder must never emit a malformed report
        report += "\n[report self-check FAILED: " + "; ".join(problems) + "]\n"

    os.makedirs(os.path.join(workspace_root, "reports"), exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S", time.gmtime(now))
    fn = f"{stamp}-sweep-{re.sub(r'[^a-z0-9]+', '-', topic.lower())[:32].strip('-')}.md"
    with open(os.path.join(workspace_root, "reports", fn), "w") as f:
        f.write(report)
    summary = {"status": status, "new": len(new_items),
               "known": len(known_items), "checked": checked,
               "not_checked": not_checked, "phrasings": phrasings,
               "report_file": fn, "record_size": len(known)}
    return report, summary


def validate_sweep_report(text):
    """Enforce the sweep report shape — above all, the coverage
    note. Returns a list of problems ([] = ok)."""
    problems = []
    if not re.search(r"^STATUS: (DONE|BLOCKED)$", text or "", re.M):
        problems.append("missing/invalid STATUS line")
    if COST_LINE not in (text or ""):
        problems.append("missing Cost line (bar 1)")
    if "Query phrasings used:" not in (text or ""):
        problems.append("missing query phrasings (thoroughness: the "
                        "sweep must show its phrasings)")
    if "## Coverage note" not in (text or ""):
        problems.append("missing coverage note (bar 9: no loopholes — "
                        "unchecked surfaces must be named)")
    if "Surfaces checked:" not in (text or ""):
        problems.append("coverage note must list surfaces checked")
    if "Surfaces NOT checked:" not in (text or ""):
        problems.append("coverage note must name surfaces NOT checked "
                        "(or state 'none')")
    if not re.search(r"^## New \(\d+\)$", text or "", re.M):
        problems.append("missing New section (dedup split is mandatory)")
    if not re.search(r"^## Already known \(\d+\)$", text or "", re.M):
        problems.append("missing Already known section (dedup split "
                        "is mandatory)")
    return problems
