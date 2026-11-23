"""Scheduler stub — cron-driven jobs (host cron / systemd timers in prod).

Jobs (design, see ARCHITECTURE.md):
- daily-agentrouter-checkin: opens AgentRouter in the host browser profile
  and performs the daily login that collects the free check-in credit.
  Uses the saved session only; if the session is dead it ASKS the user —
  it never handles a raw password or a 2FA code on its own.
- gmail-triage (readonly), memory-consolidation, provider-health-check.
"""
JOBS = [
    {"name": "daily-agentrouter-checkin", "cron": "15 7 * * *", "needs_session": "agentrouter"},
    {"name": "gmail-triage", "cron": "0 8,13,18 * * *", "scope": "gmail.readonly"},
    {"name": "provider-health-check", "cron": "*/30 * * * *", "scope": "metadata-only"},
]

def list_jobs():
    return JOBS
