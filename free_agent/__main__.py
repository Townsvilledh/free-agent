"""CLI: python -m free_agent --demo   (no keys, no network)
        python -m free_agent           (interactive, uses configured providers)
"""
import argparse, sys
from .config import Config
from .agent import Agent

def demo_transport(provider, messages):
    return 200, f"[demo:{provider.name}] FreeAgent is wired. Add keys via the vault to go live."

def main(argv=None):
    ap = argparse.ArgumentParser(prog="free_agent")
    ap.add_argument("--demo", action="store_true", help="run with a fake provider, no keys/network")
    args = ap.parse_args(argv)
    cfg = Config.from_env()
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
