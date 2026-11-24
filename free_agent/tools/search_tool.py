"""Web search — keyless fallback chain (see routes.py / ROUTES.md).

Live routes (all keyless, stdlib only):
  1. duckduckgo-instant — Instant Answers API. HONEST LIMIT: returns
     instant answers / abstracts only, NOT full web results.
  2. wikipedia-api       — factual lookups (encyclopaedic topics).
  3. openlibrary-api     — books lookups.

Candidates (documented in ROUTES.md, not wired): Brave Search free
tier (needs the user's own key), DuckDuckGo HTML (fragile scraping,
off by default).

Each route is tried in order; a route that errors or returns nothing
falls through to the next. If every live route returns nothing, the
result says so plainly (no silent dead end) instead of inventing an
answer. Results are public-web data only — search queries are never
private content; do not search for private strings.
"""
import json, urllib.parse, urllib.request

UA = {"User-Agent": "FreeAgent/0.2 (open-source; keyless search)"}

def _get_json(url, timeout=20):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8", "replace"))

def _ddg_instant(query):
    url = "https://api.duckduckgo.com/?" + urllib.parse.urlencode(
        {"q": query, "format": "json", "no_html": "1", "skip_disambig": "0"})
    d = _get_json(url)
    text = d.get("AbstractText") or d.get("Answer") or ""
    source = d.get("AbstractSource") or d.get("Heading") or ""
    if text:
        return {"route": "duckduckgo-instant", "title": d.get("Heading", ""),
                "text": text, "source": source, "url": d.get("AbstractURL", "")}
    # Related topics sometimes carry the only useful text.
    for t in d.get("RelatedTopics", []):
        if isinstance(t, dict) and t.get("Text"):
            return {"route": "duckduckgo-instant", "title": d.get("Heading", ""),
                    "text": t["Text"], "source": "DuckDuckGo", "url": t.get("FirstURL", "")}
    return None

def _wikipedia(query):
    url = "https://en.wikipedia.org/w/api.php?" + urllib.parse.urlencode(
        {"action": "query", "prop": "extracts", "explaintext": "1",
         "exintro": "1", "titles": query, "format": "json", "redirects": "1"})
    d = _get_json(url)
    pages = d.get("query", {}).get("pages", {})
    for page in pages.values():
        extract = (page.get("extract") or "").strip()
        if extract:
            return {"route": "wikipedia-api", "title": page.get("title", ""),
                    "text": extract[:1200], "source": "Wikipedia",
                    "url": "https://en.wikipedia.org/wiki/" +
                           urllib.parse.quote(page.get("title", "").replace(" ", "_"))}
    return None

def _openlibrary(query):
    url = "https://openlibrary.org/search.json?" + urllib.parse.urlencode(
        {"q": query, "limit": "1"})
    d = _get_json(url)
    docs = d.get("docs", [])
    if docs:
        b = docs[0]
        text = f"{b.get('title','')} by {', '.join(b.get('author_name', []) or ['unknown author'])}" \
               f" (first published {b.get('first_publish_year', 'n/a')})."
        return {"route": "openlibrary-api", "title": b.get("title", ""),
                "text": text, "source": "Open Library",
                "url": "https://openlibrary.org" + (b.get("key", ""))}
    return None

LIVE_ROUTES = [_ddg_instant, _wikipedia, _openlibrary]

def search(query, routes=None):
    """Try the live keyless chain in order. Returns a result dict or an
    honest no-result dict (never raises for an ordinary miss)."""
    for fn in (routes or LIVE_ROUTES):
        try:
            out = fn(query)
        except Exception:
            out = None  # route down -> fall through, Lubrica-style
        if out:
            return out
    return {"route": None, "title": "", "text":
            "No live keyless search route found an answer for that. "
            "The chain (DuckDuckGo Instant, Wikipedia, Open Library) is "
            "limited to instant/factual answers — full web search is a "
            "documented candidate, not a live route yet.",
            "source": "", "url": ""}
