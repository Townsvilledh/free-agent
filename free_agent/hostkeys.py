"""Host-side own-key bridge: run Lille host jobs with Ethuram's own
Groq key WITHOUT the key ever being present on this machine.

How it works (the credential path is the custom.groq connector's
surrogate exchange — see ~/workspace/custom-connectors/custom.groq):

1. This launcher asks the connector exchange for a *surrogate* for the
   registered `custom.groq` credential (host api.groq.com). A surrogate
   is a stand-in token: worthless anywhere except on this host's
   approved egress, where the runtime swaps it for the real key on the
   way out. The real key is never present — not in this process, not
   in the child, not on disk, not in any log.
2. The launcher spawns the child (`python -m free_agent <args>` by
   default, or an explicit command after `--`) with
   GROQ_API_KEY=<surrogate> in the CHILD environment only — exactly
   how the core router already sends GROQ_API_KEY (config.py: the
   `groq` provider, ordered after the keyless routes, enabled only
   when the env var is present). Production ordering is untouched.
3. Everything the child prints is relayed through a scrubber that
   removes the surrogate value (bare or as `Bearer <value>`) before
   it can reach our own stdout/stderr — a child that echoes its
   environment cannot leak the token into a log.

If the exchange yields nothing, the launcher REFUSES to run: no
child is spawned and the message says why. There is no fallback to a
plaintext key, because there is no plaintext key here to fall back to.

Usage:
    python -m free_agent.hostkeys --task "do the thing"
    python -m free_agent.hostkeys -- python3 scripts/groq_proof.py
"""
import os
import subprocess
import sys
import urllib.request

CONNECTOR = "custom.groq"
HOST = "api.groq.com"
PROBE_URL = "https://api.groq.com/openai/v1/models"
_DYNAMIC_CREDENTIALS_BIN = "/opt/muse/skills/custom-connector-setup/bin"

REDACTED = "[surrogate-redacted]"


def exchange_surrogate():
    """Ask the connector exchange for a fresh surrogate and return the
    bare token (no 'Bearer ' prefix), or None when the exchange yields
    nothing (not connected, exchange error, unexpected placement)."""
    try:
        if _DYNAMIC_CREDENTIALS_BIN not in sys.path:
            sys.path.insert(0, _DYNAMIC_CREDENTIALS_BIN)
        from dynamic_credentials import add_surrogate_to_request
        request = urllib.request.Request(PROBE_URL)
        add_surrogate_to_request(request, CONNECTOR, allowed_hosts=[HOST])
        header = request.get_header("Authorization") or ""
    except Exception:
        return None
    if header.startswith("Bearer "):
        token = header[len("Bearer "):].strip()
        return token or None
    return None


class Scrubber:
    """Streaming scrubber: removes every occurrence of the secret —
    bare or in `Bearer <secret>` form — from text fed in chunks,
    including occurrences split across chunk boundaries (replacement
    runs over the whole buffered text each feed, and a tail shorter
    than the longest pattern is held back until more input or flush).
    """

    def __init__(self, secret):
        self.secret = secret or ""
        self._buf = ""

    def _patterns(self):
        if not self.secret:
            return ()
        return ("Bearer " + self.secret, self.secret)

    def feed(self, chunk):
        if not self.secret:
            return chunk
        self._buf += chunk
        # Replace on the WHOLE buffer first: any complete occurrence
        # is caught wherever it sits. Only then emit, holding back a
        # tail long enough that a partial occurrence (a prefix of the
        # longest pattern, split across chunks) can never be emitted
        # half now, half later.
        for pat in self._patterns():
            self._buf = self._buf.replace(pat, REDACTED)
        hold = max(len(p) for p in self._patterns()) - 1
        if len(self._buf) <= hold:
            return ""
        safe, self._buf = self._buf[:-hold], self._buf[-hold:]
        return safe

    def flush(self):
        out, self._buf = self._buf, ""
        for pat in self._patterns():
            out = out.replace(pat, REDACTED)
        return out


def scrub_text(text, secret):
    """One-shot scrub (tests, short strings)."""
    s = Scrubber(secret)
    return s.feed(text) + s.flush()


def launch(child_cmd, surrogate, popen=subprocess.Popen, out=None, env_base=None):
    """Spawn child_cmd with GROQ_API_KEY=<surrogate> in its environment
    only; relay its merged stdout/stderr through the scrubber; return
    the child's exit code."""
    out = out if out is not None else sys.stdout
    env = dict(env_base if env_base is not None else os.environ)
    env["GROQ_API_KEY"] = surrogate
    proc = popen(child_cmd, env=env, stdout=subprocess.PIPE,
                 stderr=subprocess.STDOUT, text=True, bufsize=1)
    scrubber = Scrubber(surrogate)
    try:
        for chunk in proc.stdout:
            out.write(scrubber.feed(chunk))
            out.flush()
    finally:
        tail = scrubber.flush()
        if tail:
            out.write(tail)
            out.flush()
    return proc.wait()


def child_command(argv):
    """`-- cmd...` runs cmd verbatim; anything else is passed to
    `python -m free_agent` as its arguments."""
    if argv and argv[0] == "--":
        return argv[1:] or None
    return [sys.executable, "-m", "free_agent"] + list(argv)


def main(argv=None, surrogate_getter=None, popen=subprocess.Popen, out=None, err=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    err = err if err is not None else sys.stderr
    cmd = child_command(argv)
    if not cmd:
        err.write("hostkeys: no command given after '--'.\n")
        return 2
    getter = surrogate_getter or exchange_surrogate
    surrogate = getter()
    if not surrogate:
        err.write(
            "hostkeys: REFUSED — the connector exchange yielded no surrogate "
            f"for {CONNECTOR} ({HOST}). Nothing was run. Check that the Groq "
            "connection is registered and healthy (see the custom.groq "
            "connector doc); there is no plaintext key on this host to fall "
            "back to, by design.\n")
        return 3
    return launch(cmd, surrogate, popen=popen, out=out)


if __name__ == "__main__":
    sys.exit(main())
