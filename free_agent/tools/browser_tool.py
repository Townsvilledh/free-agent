"""Browser tool — Playwright, lazy import, host-only persistent profile.

SECURITY: the profile dir holds session cookies = SECRETS. It lives only
on the host (default ~/.freeagent/browser-profile, 0700), is gitignored,
never exported/committed/synced. Passwords are never typed from chat/logs;
they come from the vault flow. 2FA / one-time codes are approved by the
user per use — the agent never stores them.
"""
import os

PROFILE_DIR = os.environ.get(
    "FREEAGENT_PROFILE_DIR", os.path.expanduser("~/.freeagent/browser-profile"))

class BrowserTool:
    def __init__(self, profile_dir=PROFILE_DIR, headless=True):
        self.profile_dir, self.headless = profile_dir, headless
        self._ctx = None

    def start(self):
        try:
            from playwright.sync_api import sync_playwright
        except ImportError as e:
            raise RuntimeError("Playwright not installed — pip install playwright "
                               "&& playwright install chromium") from e
        os.makedirs(self.profile_dir, mode=0o700, exist_ok=True)
        self._pw = sync_playwright().start()
        self._ctx = self._pw.chromium.launch_persistent_context(
            self.profile_dir, headless=self.headless)
        return self._ctx

    def open(self, url):
        if self._ctx is None:
            self.start()
        page = self._ctx.pages[0] if self._ctx.pages else self._ctx.new_page()
        page.goto(url)
        return page

    def close(self):
        if self._ctx:
            self._ctx.close(); self._pw.stop(); self._ctx = None
