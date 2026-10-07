import threading
import time


def readText(path):
    with open(path) as f:
        return f.read()


# A thread Python waits for at its own exit; Kira's exit, as C++'s, does not.
def linger():
    threading.Thread(target=time.sleep, args=(20,)).start()
