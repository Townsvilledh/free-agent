"""CLI: python -m free_agent --demo        (no keys, no network)
        python -m free_agent                (interactive chat)
        python -m free_agent --task "..."   (durable task: checkpointed
                                             after every step, resumable
                                             in a new process — v0.4)
        python -m free_agent --tasks        (list durable tasks)
        python -m free_agent --resume ID    (continue a checkpointed task)
        python -m free_agent --pause ID / --cancel ID
        python -m free_agent --enqueue "..."  (queue a task for idle
                                             pickup — nobody starts it)
        python -m free_agent --commit "T" --check SPEC --every MINS
                                            (register a commitment: a
                                             result Lille must surface
                                             when it lands — v0.7)
        python -m free_agent --commitments  (list the register)
        python -m free_agent --proactive-run  (one proactive pass:
                                             due checks, idle pickup,
                                             waiting resumes, outbox
                                             reports — PROACTIVITY.md)
"""
import argparse, sys, time
from .config import Config
from .agent import Agent

def demo_transport(provider, messages):
    return 200, f"[demo:{provider.name}] FreeAgent is wired. The keyless brain works with zero keys; personal keys are optional (vault only)."

def _fmt_commitment(rec):
    line = (f"{rec['id']}  [{rec['state']}]  ({rec['kind']})  "
            f"{rec['title'][:70]!r}")
    if rec.get("private"):
        line += "  PRIVATE (never leaves the device)"
    if rec["state"] == "open":
        line += (f"  next_check={time.strftime('%H:%M:%S UTC', time.gmtime(rec['next_check_at']))}"
                 f"  checks={rec['check_count']}")
        if rec.get("deadline"):
            line += f"  deadline={time.strftime('%Y-%m-%d %H:%M UTC', time.gmtime(rec['deadline']))}"
    if rec.get("result"):
        line += f"\n    result: {str(rec['result'])[:200]}"
    if rec.get("report_file"):
        line += f"\n    reported: reports/{rec['report_file']}"
    return line


def _fmt_rec(rec):
    ck = rec.get("checkpoint") or {}
    steps = len(ck.get("steps", []))
    line = f"{rec['id']}  [{rec['state']}]  steps={steps}  {rec['text'][:70]!r}"
    if rec.get("next_retry_at"):
        line += f"  retry_at={time.strftime('%H:%M:%S UTC', time.gmtime(rec['next_retry_at']))}"
    if rec.get("summary"):
        line += f"\n    summary: {rec['summary'][:160]}"
    if rec.get("error"):
        line += f"\n    note: {str(rec['error'])[:160]}"
    return line

def main(argv=None):
    ap = argparse.ArgumentParser(prog="free_agent")
    ap.add_argument("--demo", action="store_true", help="run with a fake provider, no keys/network")
    ap.add_argument("--task", metavar="TEXT", help="run one durable autonomous task (checkpointed, resumable)")
    ap.add_argument("--task-id", metavar="ID", help="task id for --task (default: generated)")
    ap.add_argument("--resume", metavar="ID", help="resume a checkpointed task in this new process")
    ap.add_argument("--tasks", action="store_true", help="list durable tasks and their states")
    ap.add_argument("--pause", metavar="ID", help="pause a task (takes effect at the next step)")
    ap.add_argument("--cancel", metavar="ID", help="cancel a task (takes effect at the next step)")
    ap.add_argument("--spec", metavar="FILE", help="run a spec pack (v0.5 spec mode): spec in -> worked -> verified -> STATUS report")
    ap.add_argument("--repo", metavar="URL_OR_PATH", help="repo for --spec: a github.com URL or a local path (pre-cloned host-side)")
    ap.add_argument("--max-steps", type=int, default=12, help="step budget for --task/--resume (default 12)")
    ap.add_argument("--pace", type=float, default=None,
                    help="seconds to wait between model calls (keyless tier: ~50)")
    ap.add_argument("--enqueue", metavar="TEXT",
                    help="enqueue a durable task for idle pickup by a proactive pass (does NOT start it)")
    ap.add_argument("--commit", metavar="TITLE",
                    help="register a commitment (v0.7): a pending result Lille must check and report unprompted")
    ap.add_argument("--check", metavar="SPEC",
                    help="landing condition for --commit: url:<URL> | url-contains:<URL>::<text> | "
                         "file:<relpath> | actions-run:<owner>/<repo>:<run_id> | task-state:<id>[:<state>]")
    ap.add_argument("--every", type=float, default=30, metavar="MINS",
                    help="check interval in minutes for --commit (default 30)")
    ap.add_argument("--deadline", metavar="ISO",
                    help="deadline for --commit, ISO 8601 (e.g. 2026-12-01T00:00:00Z); "
                         "passing it unlanded is reported, never silent")
    ap.add_argument("--kind", default="watch", choices=["watch", "deadline", "queued_task"],
                    help="commitment kind for --commit (default watch)")
    ap.add_argument("--private", action="store_true",
                    help="flag the commitment private: it never leaves the device (refused by the Actions lane)")
    ap.add_argument("--commitments", action="store_true", help="list the commitments register")
    ap.add_argument("--close-commitment", metavar="ID", help="close a commitment (stops its checks)")
    ap.add_argument("--proactive-run", action="store_true",
                    help="run ONE proactive pass: due checks, idle pickup, waiting resumes, outbox reports")
    ap.add_argument("--lane", action="store_true",
                    help="lane mode for --proactive-run (scheduled Actions lane): private commitments are refused")
    ap.add_argument("--export-lane", metavar="DIR",
                    help="export lane-eligible state (non-private commitments + reports) to DIR; private entries are refused")
    args = ap.parse_args(argv)
    cfg = Config.from_env()

    if args.commit or args.commitments or args.close_commitment \
            or args.enqueue or args.proactive_run or args.export_lane:
        import os
        from .commitments import CommitmentStore, export_for_lane
        from .taskstore import TaskStore
        ws = os.environ.get("FREEAGENT_WORKSPACE",
                            os.path.expanduser("~/.freeagent/tasks"))
        if args.enqueue:
            store = TaskStore(ws)
            rec = store.create(args.enqueue)
            cmt = CommitmentStore(ws).create(
                title=args.enqueue, kind="queued_task",
                check=f"task-state:{rec['id']}:completed",
                task_id=rec["id"], private=args.private)
            print(f"Enqueued task {rec['id']} (commitment {cmt['id']}). "
                  "Nobody has started it — a proactive pass will pick it up.")
            return 0
        if args.commit:
            deadline = None
            if args.deadline:
                import datetime
                deadline = datetime.datetime.strptime(
                    args.deadline.replace("Z", "+0000"),
                    "%Y-%m-%dT%H:%M:%S%z").timestamp()
            try:
                rec = CommitmentStore(ws).create(
                    args.commit, check=args.check,
                    every_seconds=int(args.every * 60), deadline=deadline,
                    private=args.private, kind=args.kind)
            except ValueError as e:
                print(f"Refused: {e}")
                return 1
            print(_fmt_commitment(rec))
            return 0
        if args.commitments:
            recs = CommitmentStore(ws).list()
            if not recs:
                print("No commitments registered.")
            for rec in recs:
                print(_fmt_commitment(rec))
            return 0
        if args.close_commitment:
            rec = CommitmentStore(ws).close(args.close_commitment)
            print(_fmt_commitment(rec) if rec
                  else f"No such commitment: {args.close_commitment}")
            return 0 if rec else 1
        if args.export_lane:
            exported, refused = export_for_lane(CommitmentStore(ws),
                                                args.export_lane)
            print(f"Exported {len(exported)} commitment(s) to {args.export_lane}")
            if refused:
                print(f"REFUSED (private, never leaves the device): "
                      f"{', '.join(refused)}")
            return 0
        from .proactive import ProactiveRunner
        runner = ProactiveRunner(config=cfg, max_steps=args.max_steps,
                                 pace_seconds=args.pace, lane=args.lane)
        s = runner.run_pass()
        print(f"Proactive pass @ {s['when']}: checked={s['checked']} "
              f"landed={len(s['landed'])} expired={len(s['expired'])} "
              f"picked_up={len(s['picked_up'])} resumed={len(s['resumed'])} "
              f"reports={len(s['reports'])}")
        for fn in s["reports"]:
            print(f"  report: reports/{fn}")
        if s["refused_private"]:
            print(f"  refused (private, lane mode): "
                  f"{', '.join(s['refused_private'])}")
        return 0

    if args.spec:
        from .specmode import run_spec
        with open(args.spec) as f:
            spec_text = f.read()
        report, result, task_dir = run_spec(
            spec_text, repo=args.repo, config=cfg,
            pace_seconds=args.pace, max_steps=max(args.max_steps, 25))
        print(report)
        print(f"(task dir: {task_dir})")
        return 0 if report.startswith("STATUS: SHIPPED") else 1

    if args.tasks or args.resume or args.pause or args.cancel or args.task:
        from .taskstore import TaskEngine
        engine = TaskEngine(config=cfg, max_steps=args.max_steps,
                            pace_seconds=args.pace)
        if args.tasks:
            recs = engine.list()
            if not recs:
                print("No durable tasks yet.")
            for rec in recs:
                print(_fmt_rec(rec))
            return 0
        if args.pause:
            rec = engine.pause(args.pause)
            print(_fmt_rec(rec) if rec else f"No such task: {args.pause}")
            return 0 if rec else 1
        if args.cancel:
            rec = engine.cancel(args.cancel)
            print(_fmt_rec(rec) if rec else f"No such task: {args.cancel}")
            return 0 if rec else 1
        if args.resume:
            try:
                rec = engine.resume(args.resume)
            except KeyError as e:
                print(str(e))
                return 1
            print(_fmt_rec(rec))
            return 0 if rec["state"] == "completed" else 1
        rec = engine.start(args.task, task_id=args.task_id)
        ck = rec.get("checkpoint") or {}
        for s in ck.get("steps", []):
            print(f"  step {s['n']}: {s['kind']} {s.get('tool','')} "
                  f"{s.get('args_summary','')} -> {s.get('result_summary','')}")
        print(_fmt_rec(rec))
        return 0 if rec["state"] == "completed" else 1

    agent = Agent(cfg, transport=demo_transport if args.demo else None)
    if args.demo:
        print(agent.chat("hello")["text"])
        print("Demo OK — router, memory and privacy layers loaded with zero keys.")
        return 0
    print("FreeAgent (Ctrl-C to exit). Keys are read from env/vault only, never typed here.")
    try:
        while True:
            text = input("you> ").strip()
            if text:
                print(agent.chat(text)["text"])
    except (KeyboardInterrupt, EOFError):
        print("\nbye")
    return 0

if __name__ == "__main__":
    sys.exit(main())
