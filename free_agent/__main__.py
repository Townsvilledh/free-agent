"""CLI: python -m free_agent --demo        (no keys, no network)
        python -m free_agent                (interactive chat)
        python -m free_agent --task "..."   (v0.3 tool-call loop: Lille
                                             plans, calls tools in a
                                             sandbox, verifies, finishes)
"""
import argparse, sys
from .config import Config
from .agent import Agent

def demo_transport(provider, messages):
    return 200, f"[demo:{provider.name}] FreeAgent is wired. The keyless brain works with zero keys; personal keys are optional (vault only)."

def main(argv=None):
    ap = argparse.ArgumentParser(prog="free_agent")
    ap.add_argument("--demo", action="store_true", help="run with a fake provider, no keys/network")
    ap.add_argument("--task", metavar="TEXT", help="run one autonomous task through the tool-call loop")
    ap.add_argument("--max-steps", type=int, default=12, help="step budget for --task (default 12)")
    ap.add_argument("--pace", type=float, default=None,
                    help="seconds to wait between model calls (keyless tier: ~50)")
    args = ap.parse_args(argv)
    cfg = Config.from_env()
    if args.task:
        from .loop import TaskLoop
        loop = TaskLoop(config=cfg, max_steps=args.max_steps,
                        pace_seconds=args.pace)
        result = loop.run(args.task)
        print(result.trace_text())
        return 0 if result.status == "done" else 1
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
