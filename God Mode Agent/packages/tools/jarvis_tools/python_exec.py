"""Sandboxed Python execution: isolated subprocess, restricted builtins, hard
timeout, plus Phase 2 stricter resource limits.

The child runs `python -I -E` (isolated mode) and executes user code with a
restricted builtins table and an import whitelist. On POSIX the child also
applies RLIMIT_CPU / RLIMIT_AS / RLIMIT_NPROC (blocking subprocess spawning);
on Windows those rlimits are unavailable, so isolation relies on the import
whitelist (which blocks os/subprocess/socket) plus the parent wall-clock kill.
"""

from __future__ import annotations

import subprocess
import sys

# Resource ceilings applied in the child on POSIX.
CPU_SECONDS = 4
ADDRESS_SPACE_BYTES = 256 * 1024 * 1024  # 256 MB

_RUNNER = r"""
import sys, builtins

# --- POSIX resource limits (no-op on Windows) ---
try:
    import resource
    resource.setrlimit(resource.RLIMIT_CPU, (%(cpu)d, %(cpu)d))
    try:
        resource.setrlimit(resource.RLIMIT_AS, (%(mem)d, %(mem)d))
    except (ValueError, OSError):
        pass
    try:
        resource.setrlimit(resource.RLIMIT_NPROC, (0, 0))  # block subprocess/fork
    except (ValueError, OSError):
        pass
except Exception:
    pass

code = sys.stdin.read()
SAFE = {}
for name in (
    "print","range","len","abs","min","max","sum","sorted","enumerate","zip","map",
    "filter","list","dict","set","tuple","str","int","float","bool","round","divmod",
    "pow","repr","isinstance","issubclass","reversed","any","all","hash","ord","chr",
    "format","iter","next","slice","frozenset","bytes","bytearray","complex",
    "Exception","BaseException","ValueError","TypeError","KeyError","IndexError",
    "ZeroDivisionError","ArithmeticError","StopIteration","RuntimeError","NameError",
    "AttributeError","OverflowError","True","False","None",
):
    if hasattr(builtins, name):
        SAFE[name] = getattr(builtins, name)

# Import whitelist: pure-computation stdlib only. Blocks os/sys/subprocess/socket/etc.
_ALLOWED_MODULES = {
    "math","json","statistics","datetime","re","random","itertools","functools",
    "collections","decimal","fractions","string","textwrap","heapq","bisect",
}

def _safe_import(name, globals=None, locals=None, fromlist=(), level=0):
    root = name.split(".")[0]
    if root in _ALLOWED_MODULES:
        return __import__(name, globals, locals, fromlist, level)
    raise ImportError("import of %%r is blocked in the sandbox" %% name)

SAFE["__import__"] = _safe_import
try:
    exec(compile(code, "<sandbox>", "exec"), {"__builtins__": SAFE})
except Exception as exc:
    print("SANDBOX_ERROR: %%s: %%s" %% (type(exc).__name__, exc), file=sys.stderr)
    sys.exit(1)
""" % {"cpu": CPU_SECONDS, "mem": ADDRESS_SPACE_BYTES}

MAX_OUTPUT_CHARS = 16000


def run_python_sandboxed(code: str, timeout: float = 8.0) -> dict:
    """Execute code in the sandbox. Returns stdout/stderr/returncode/timed_out."""
    if not isinstance(code, str) or not code.strip():
        return {"stdout": "", "stderr": "empty code", "returncode": 2, "timed_out": False}
    try:
        proc = subprocess.run(
            [sys.executable, "-I", "-E", "-c", _RUNNER],
            input=code,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
        return {
            "stdout": proc.stdout[:MAX_OUTPUT_CHARS],
            "stderr": proc.stderr[:MAX_OUTPUT_CHARS],
            "returncode": proc.returncode,
            "timed_out": False,
        }
    except subprocess.TimeoutExpired as exc:
        stdout = exc.stdout.decode(errors="replace") if isinstance(exc.stdout, bytes) else (exc.stdout or "")
        return {
            "stdout": stdout[:MAX_OUTPUT_CHARS],
            "stderr": f"execution timed out after {timeout}s and was killed",
            "returncode": -1,
            "timed_out": True,
        }
