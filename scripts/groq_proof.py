#!/usr/bin/env python3
"""GROQ BRIDGE PROOF — run BY THE MAIN AGENT, through the launcher:

    cd ~/workspace/free-agent && \
      python3 -m free_agent.hostkeys -- python3 scripts/groq_proof.py

TEST-HARNESS ROUTE SELECTION (clearly labelled): this harness pins
the router to the `groq` provider ONLY, so the single proof task's
trace unambiguously records which route served. That pin lives here,
in the harness, and nowhere else — production ordering in config.py
is untouched (keyless routes first, Groq after them, exactly as
before). Do not import this selection into production code.

The harness refuses to run without GROQ_API_KEY in the environment —
that variable is the surrogate the launcher placed in this process's
environment; the real key is never present. It runs ONE real loop
task (a sweep, so the proof also produces a useful report), then
checks the persisted trace: the serving provider must be `groq`.
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

TASK_TEXT = (
    'Use the sweep tool to run a thorough sweep on the topic '
    '"hackathons open for solo online entry", then finish with a '
    "summary that includes the sweep report's coverage note."
)


def main():
    if not os.environ.get("GROQ_API_KEY"):
        print("groq_proof: REFUSED — GROQ_API_KEY is not set. Run me "
              "through the launcher: python3 -m free_agent.hostkeys -- "
              "python3 scripts/groq_proof.py")
        return 2

    from free_agent.config import Config
    from free_agent.loop import TaskLoop

    config = Config.from_env()
    # TEST-HARNESS SELECTION: groq only, for this proof run alone.
    config.providers = [p for p in config.providers if p.name == "groq"]
    if not config.providers:
        print("groq_proof: REFUSED — the groq provider is not enabled "
              "(GROQ_API_KEY present but config did not pick it up).")
        return 2
    print("groq_proof: TEST-HARNESS route pin active — groq only "
          "(production ordering untouched).")

    workspace = os.environ.get(
        "FREEAGENT_WORKSPACE", os.path.expanduser("~/.freeagent/groq-proof"))
    loop = TaskLoop(config=config, workspace_root=workspace)
    result = loop.run(TASK_TEXT)

    print(result.trace_text())
    trace_path = os.path.join(workspace, "task_logs", result.task_id + ".json")
    print(f"groq_proof: trace: {trace_path}")
    report = os.path.join(result.task_dir, "sweep-report.md")
    if os.path.exists(report):
        print(f"groq_proof: sweep report: {report}")
    if result.provider == "groq":
        print("PROOF_OK provider=groq")
        return 0
    print(f"PROOF_INCONCLUSIVE provider={result.provider!r} "
          f"status={result.status!r} — the trace above is the evidence.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
