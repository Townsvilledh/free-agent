#!/usr/bin/env python3
"""Placeholder non-private scheduled job for GitHub Actions.

What this WILL do (v0.3, once wired):
  - confirm the AgentRouter daily check-in window has been hit for today
    (the actual login/check-in happens on the PHONE, where the session
    lives — see HOSTING.md Layer 0/1 split), and
  - record a metadata-only heartbeat (date, job name, result category).

What it deliberately does NOT do:
  - NO real logins, NO browser automation, NO secrets are read or used.
  - It never prints secret values; at most it reports whether a named
    environment variable is present (presence only, never the value).
  - It handles NO private content — public-repo workflow logs are
    public, so only non-private jobs may run on GitHub Actions.

Safe to run anywhere: `python3 jobs/daily_checkin.py [--dry-run]`
"""
import argparse, datetime, os, sys

JOB = "daily-checkin"

def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--dry-run", action="store_true",
                    help="log intentions only (currently the only mode)")
    args = ap.parse_args()
    now = datetime.datetime.now(datetime.timezone.utc).isoformat()
    print(f"[{JOB}] {now} starting (dry_run={args.dry_run or True})")
    for name in ("AGENTROUTER_API_KEY", "GROQ_API_KEY"):
        # Presence only — values are never read into output or logs.
        print(f"[{JOB}] secret {name}: {'present' if os.environ.get(name) else 'absent'} (value never logged)")
    print(f"[{JOB}] would: verify today's AgentRouter check-in status (phone-side session)")
    print(f"[{JOB}] would: write metadata-only heartbeat (no content, no secrets)")
    print(f"[{JOB}] done — placeholder job, no logins performed, no network calls made")
    return 0

if __name__ == "__main__":
    sys.exit(main())
