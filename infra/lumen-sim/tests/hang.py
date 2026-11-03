"""A guard for fake-clock tests: fail instead of hanging when a bug makes the simulator loop for ever."""
import contextlib
import signal


class Hung(BaseException):
    """Not an Exception: the firmware's model-error handlers must not swallow it."""


@contextlib.contextmanager
def within(test, seconds):
    """Fail instead of hanging (and filling memory) when a bug makes the simulator loop for ever (SIGALRM)."""
    def expired(signum, frame):
        raise Hung()
    old = signal.signal(signal.SIGALRM, expired)
    signal.setitimer(signal.ITIMER_REAL, seconds)
    try:
        yield
    except Hung:
        test.fail("the simulator was still looping after %s s of wall clock" % seconds)
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, old)
