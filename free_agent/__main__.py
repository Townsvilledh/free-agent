"""CLI: python -m free_agent --demo        (no keys, no network)
        python -m free_agent                (interactive chat)
        python -m free_agent --task "..."   (durable task: checkpointed
                                             after every step, resumable
                                             in a new process — v0.4)
        python -m free_agent --tasks        (list durable tasks)
        python -m free_agent --resume ID    (continue a checkpointed task)
        python -m free_agent --pause ID / --cancel ID
"""
import argparse, sys, time
from .config import Config
from .agent import Agent

def demo_transport(provider, messages):
    return 200, f"[demo:{provider.name}] FreeAgent is wired. The keyless brain works with zero keys; personal keys are optional (vault only)."

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
    ap.add_argument("--max-steps", type=int, default=12, help="step budget for --task/--resume (default 12)")
    ap.add_argument("--pace", type=float, default=None,
                    help="seconds to wait between model calls (keyless tier: ~50)")
    args = ap.parse_args(argv)
    cfg = Config.from_env()

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
