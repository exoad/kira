# The sidecar of conn.kira, which declares no function: its Busy is the one raises = names.
class Busy(Exception):
    pass


class Conn:
    def wait(self):
        raise Busy("try later")
