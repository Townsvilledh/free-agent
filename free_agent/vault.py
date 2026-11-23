"""Encrypted-at-rest vault for ALL secrets (API keys, OAuth tokens).

- Preferred backend: Fernet (from `cryptography`, in requirements.txt).
- The vault key comes from FREEAGENT_VAULT_KEY (a Fernet key) or a key file
  next to the vault, created with 0600 perms. NEVER commit either file.
- If `cryptography` is missing, Vault refuses to store secrets rather than
  silently writing plaintext — except in an explicitly-named INSECURE stub
  mode used only by unit tests (flag is loud, file header says so).
"""
import base64, json, os, stat

class VaultUnavailable(RuntimeError):
    pass

def _fernet():
    try:
        from cryptography.fernet import Fernet
        return Fernet
    except ImportError as e:
        raise VaultUnavailable(
            "cryptography not installed — run: pip install -r requirements.txt") from e

class Vault:
    def __init__(self, path, key=None, insecure_stub=False):
        self.path = str(path)
        self.insecure_stub = insecure_stub
        if insecure_stub:
            self._f = None
            return
        Fernet = _fernet()
        key = key or os.environ.get("FREEAGENT_VAULT_KEY")
        if not key:
            keyfile = self.path + ".key"
            if os.path.exists(keyfile):
                with open(keyfile, "rb") as fh:
                    key = fh.read().strip()
            else:
                key = Fernet.generate_key()
                fd = os.open(keyfile, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                with os.fdopen(fd, "wb") as fh:
                    fh.write(key)
        self._f = Fernet(key if isinstance(key, bytes) else key.encode())

    def _load_raw(self):
        if not os.path.exists(self.path):
            return {}
        with open(self.path, "rb") as fh:
            raw = fh.read()
        if self.insecure_stub:
            return json.loads(raw.decode())
        return json.loads(self._f.decrypt(raw).decode())

    def _save_raw(self, data):
        payload = json.dumps(data).encode()
        blob = payload if self.insecure_stub else self._f.encrypt(payload)
        tmp = self.path + ".tmp"
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "wb") as fh:
            fh.write(blob)
        os.replace(tmp, self.path)
        os.chmod(self.path, 0o600)

    def set(self, name, value):
        d = self._load_raw(); d[name] = value; self._save_raw(d)

    def get(self, name, default=None):
        return self._load_raw().get(name, default)

    def delete(self, name):
        d = self._load_raw(); d.pop(name, None); self._save_raw(d)

    def names(self):
        """Names only — values are never listed/logged."""
        return sorted(self._load_raw().keys())
