"""Approval gates: consequential actions NEVER run silently.

send_email / post / delete / money always require explicit user approval.
Reading within granted scopes does not. Standing rules can be added later
via config, but the default is ask-every-time.
"""
class ApprovalRequired(RuntimeError):
    def __init__(self, action, detail=""):
        super().__init__(f"Approval required for {action}: {detail}")
        self.action = action

def require_approval(config, action, approver=None, detail=""):
    if action in config.require_approval_for:
        if approver is None or not approver(action, detail):
            raise ApprovalRequired(action, detail)
    return True
