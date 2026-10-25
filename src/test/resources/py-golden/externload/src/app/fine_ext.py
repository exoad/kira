import time

sleepS = time.sleep


class Missing(LookupError):
    pass


def g():
    raise Missing("nothing here")
