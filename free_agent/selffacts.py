"""Her own facts (v1.0.14) — the canonical sources for Lille's
own product, recorded once, in code, and available to every
task and chat she runs.

Born of the 1.0.13 head-to-head battery (verify-a / verify-b):
asked to check her own app's latest version and the Fluid
names registry, she invented hosts (lille.dev/download,
fluidnames.com) and never fetched a real source. The facts
were nowhere in her context — the app side has held them all
along (FluidGate.kt's registry sources, UpdateCheck.kt's
version.json URL). This module is the core-side record of the
same facts; the two must not drift.

Rules that ride on these facts:
- A task about her own product uses THESE sources (the loop
  puts them in her system prompt and derives browser
  authorisation from them — tasktools.derive_browser_domains).
- A finish/report that presents an address for her own
  product's surfaces (download, version, registry, code) that
  is neither one of these nor present in tool evidence is
  ungrounded — the loop rejects it (loop._check_finish).
"""

# Her code and distribution (the apk branch is the published
# line: app-debug.apk + version.json, written by CI from
# build.gradle — UpdateCheck.kt polls the same URL).
REPO_URL = "https://github.com/Townsvilledh/free-agent"
REPO_NAME = "Townsvilledh/free-agent"
APK_VERSION_URL = ("https://raw.githubusercontent.com/"
                   "Townsvilledh/free-agent/apk/version.json")
APK_DOWNLOAD_URL = ("https://raw.githubusercontent.com/"
                    "Townsvilledh/free-agent/apk/app-debug.apk")
LANDING_URL = "https://townsvilledh.github.io/free-agent/"

# The Fluid names registry and its independent mirrors — the
# same three sources FluidGate.kt validates on the device
# (canonical + GitHub raw gh-pages + jsDelivr; the Netlify
# copy is frozen and deliberately absent).
NAMES_REGISTRY_URL = "https://fluidwallet.dpdns.org/names.json"
NAMES_REGISTRY_MIRRORS = [
    "https://raw.githubusercontent.com/Townsvilledh/"
    "fluid-wallet/gh-pages/names.json",
    "https://cdn.jsdelivr.net/gh/Townsvilledh/"
    "fluid-wallet@gh-pages/names.json",
]

CANONICAL_URLS = [APK_VERSION_URL, APK_DOWNLOAD_URL, REPO_URL,
                  LANDING_URL, NAMES_REGISTRY_URL] + \
    NAMES_REGISTRY_MIRRORS


def _host(url):
    return url.split("://", 1)[-1].split("/", 1)[0].lower()


CANONICAL_HOSTS = sorted({_host(u) for u in CANONICAL_URLS})


def facts_block():
    """The compact fact block for prompts and state sheets.
    Facts only — where her own product's sources live."""
    return (
        "Facts about your own product (recorded, canonical — "
        "use these sources; never invent an address for your "
        "own product):\n"
        f"- Your latest released version is published at "
        f"{APK_VERSION_URL} (the app itself: {APK_DOWNLOAD_URL}).\n"
        f"- Your code lives at {REPO_URL}.\n"
        f"- The Fluid names registry is {NAMES_REGISTRY_URL} "
        f"(mirrors: {', '.join(NAMES_REGISTRY_MIRRORS)})."
    )


def mentions_own_product(text):
    """Does this job text talk about HER OWN product — her
    app/version/download, her code, or the Fluid names
    registry she logs in against? Conservative word shapes,
    mirroring the device derivation's discipline."""
    low = (text or "").lower()
    needles = ("lille", "your app", "her app", "the app's",
               "names registry", "fluid names", "fluid wallet",
               "your code", "your repo", "your download",
               "your version")
    return any(n in low for n in needles)
