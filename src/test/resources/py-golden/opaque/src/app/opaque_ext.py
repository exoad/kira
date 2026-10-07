# The sidecar of opaque.kira: a plain Python class whose one-word methods Kira calls directly.
class Counter:
    def __init__(self, label):
        self.text = label
        self.n = 0

    def bump(self, by):
        self.n += by
        return self.n

    def total(self):
        return self.n

    def label(self):
        if not self.text:
            raise ValueError("unlabelled")
        return self.text

    def wrong(self):
        return None

    def absorb(self, pair):
        pair[0].append(99)
        return len(pair[0])


def counter(label):
    return Counter(label)


def maybeCounter(label):
    return Counter(label) if label else None


def sameObject(a, b):
    return a is b


def broken():
    return None
