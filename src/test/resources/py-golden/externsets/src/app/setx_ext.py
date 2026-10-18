def count(s):
    s.add(99)
    return len(s)


def evens(up_to):
    return {x for x in range(0, up_to + 1, 2)}


def words(text):
    return frozenset(text.split())
