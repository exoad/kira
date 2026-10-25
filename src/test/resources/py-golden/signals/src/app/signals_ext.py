# The sidecar of signals.kira: SIGINT raised in this very process, as Ctrl-C delivers it.
import signal


def raiseInt():
    signal.raise_signal(signal.SIGINT)
