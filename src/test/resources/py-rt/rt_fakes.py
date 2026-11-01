# Fake host modules for the runtime harness: stand-ins for `_k_os`, `_k_builtins` and the like that forward to
# the real module except where a case overrides a name, so a helper meets an errno a real disk would not give.
import errno
import os
import subprocess

MISSING = object()


class FakeMod:
    """The real module `real`, but with the names in `over` replaced (MISSING removes one)."""

    def __init__(self, real, over):
        object.__setattr__(self, "_real", real)
        object.__setattr__(self, "_over", over)

    def __getattr__(self, name):
        over = object.__getattribute__(self, "_over")
        if name in over:
            if over[name] is MISSING:
                raise AttributeError(name)
            return over[name]
        return getattr(object.__getattribute__(self, "_real"), name)


def boom(code, text="boom", cls=OSError):
    """A function that raises cls(code, text) however it is called."""

    def fail(*args, **kwargs):
        raise cls(code, text)

    return fail


def boom_exc(make):
    """A function that raises make() however it is called."""

    def fail(*args, **kwargs):
        raise make()

    return fail


class Ended(BaseException):
    """What a fake os._exit or os.abort raises in place of ending the process."""

    def __init__(self, what, code=None):
        super().__init__(what, code)
        self.what = what
        self.code = code


def fake_end():
    """os overrides that record the end of the process instead of making it."""

    def leave(code):
        raise Ended("_exit", code)

    def abort():
        raise Ended("abort")

    return {"_exit": leave, "abort": abort}


class Clock:
    """A sleep that only adds up what it was asked for."""

    def __init__(self):
        self.asked = []

    def sleep(self, s):
        self.asked.append(s)


ERRNOS = [errno.ENOENT, errno.EEXIST, errno.EACCES, errno.EPERM, errno.EINTR, errno.EIO, errno.ENOSPC,
          errno.EBADF, errno.ENOTDIR, errno.EISDIR, errno.ETIMEDOUT, errno.ECONNRESET, errno.EPIPE]

# The host errors the os helpers sort by class: each as a factory so every run raises a fresh one.
HOST_ERRORS = [
    ("oserror-none", lambda: OSError("plain")),
    ("oserror-errno", lambda: OSError(errno.EIO, "io")),
    ("notfound", lambda: FileNotFoundError(errno.ENOENT, "gone", "p")),
    ("exists", lambda: FileExistsError(errno.EEXIST, "there", "p")),
    ("permission", lambda: PermissionError(errno.EACCES, "no", "p")),
    ("timeout", lambda: TimeoutError(errno.ETIMEDOUT, "late")),
    ("interrupted", lambda: InterruptedError(errno.EINTR, "intr")),
    ("blockingio", lambda: BlockingIOError(errno.EAGAIN, "again")),
    ("broken-pipe", lambda: BrokenPipeError(errno.EPIPE, "pipe")),
    ("value", lambda: ValueError("embedded null byte")),
    ("overflow", lambda: OverflowError("Python int too large to convert to C long")),
    ("import", lambda: ImportError("no module")),
    ("lookup", lambda: LookupError("key")),
    ("subprocess", lambda: subprocess.SubprocessError("sub")),
    ("timeout-expired", lambda: subprocess.TimeoutExpired(["x"], 1.5)),
    ("called", lambda: subprocess.CalledProcessError(2, ["x"])),
    ("errno-in-other-class", lambda: type("SerialException", (IOError,), {})(errno.ENOENT, "serial")),
    ("errno-string", lambda: OSError("not-an-int", "x")),
    ("unicode", lambda: UnicodeDecodeError("utf-8", b"\xff", 0, 1, "invalid start byte")),
    ("runtime", lambda: RuntimeError("kira: panic")),
]
