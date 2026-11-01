"""The serial port the backend opens: a pseudo-terminal whose slave is published through a symlink.

The master stays open while the host closes and reopens the slave (so controller state survives, as on the real
board, where opening the USB CDC port does not reset it). The link exists exactly while the master is open: closing
the master (power cycle, disconnect, DFU, stop) removes it, so the port is absent (ENOENT), as an unplugged ttyACM
is. A link left behind would name a /dev/pts/N the kernel hands to the next pty opened anywhere on the host.

Two Linux pty facts the runner works around:
- A write on the master succeeds while no host has the slave open, and the bytes wait in the slave's input queue for
  the next opener, as do bytes the host left unread when it closed (a USB port's buffers go with the last close). So
  output is written only while a host is open, and flush_unread() drops what is left when the host closes.
- Whether a host has the slave open shows as POLLHUP on the master; host_present() reads that without reading data,
  so it works while the port is frozen.

jSerialComm's getCommPort opens /dev/<name> or /dev/<basename> when the path does not exist and is not a symlink, so
a link named like a device (ttyACM0) would open the real board whenever the link is missing: check_link_path().

One lumen-sim per link path: each holds an flock on <link>.lock while it runs (the file stays; a killed process lets
go of the lock), so a second one on the same path is refused instead of silently taking over the first one's host.

A killed lumen-sim (SIGKILL: a stop that escalated, the OOM killer) cannot remove its link. A /dev/pts/N is no proof of
a simulator: on the lab computer Klipper's G-code input (klippy.serial) is a pty too, so a host reconnecting through a
stale link could stream G-code into a real machine. LinkGuard is a helper process that removes the links when
lumen-sim dies, however it dies.
"""
import errno
import fcntl
import json
import os
import select
import struct
import termios
import tty

LINK_PREFIX = "ttyLUMEN"


def check_link_path(path, exists=os.path.exists):
    """The absolute link path, or ValueError if it could ever resolve to a real device or cannot be created."""
    path = os.path.abspath(path) if path else ""
    name = os.path.basename(path)
    if not name.startswith(LINK_PREFIX):
        raise ValueError("link name %r must start with %r: while the link is missing, jSerialComm opens "
                         "/dev/<name> instead (a device-like name such as ttyACM0 would be the real board)"
                         % (name, LINK_PREFIX))
    for dev in ("/dev/" + name, "/dev/" + path):
        if exists(dev):
            raise ValueError("%s exists: jSerialComm would open it while the link %s is missing" % (dev, path))
    if not exists(os.path.dirname(path)):
        raise ValueError("no directory for the link %s" % path)
    return path


def lock_link(path):
    """Hold <path>.lock (an fd to keep open); ValueError if another lumen-sim holds it."""
    lock = path + ".lock"
    fd = os.open(lock, os.O_RDWR | os.O_CREAT | os.O_CLOEXEC, 0o600)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        os.close(fd)
        raise ValueError("%s is in use by another lumen-sim (it holds %s)" % (path, lock)) from None
    except BaseException:
        os.close(fd)
        raise
    return fd


def remove_stale_link(link, pts):
    """Remove the link a dead lumen-sim left, if it still points at that one's pty and no lumen-sim holds its lock (one
    may have taken the path since, and the kernel may have given it the same pts number)."""
    try:
        lock = os.open(link + ".lock", os.O_RDWR | os.O_CLOEXEC)
    except FileNotFoundError:
        lock = None
    except OSError:
        return
    try:
        if lock is not None:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)  # held while the link is removed: nobody publishes there
        if os.path.islink(link) and os.readlink(link) == pts:
            os.unlink(link)
    except OSError:  # BlockingIOError: a live lumen-sim has the path
        pass
    finally:
        if lock is not None:
            os.close(lock)


class LinkGuard:
    """A helper process, forked before lumen-sim opens anything, that removes its links when it dies, however it dies.

    It runs in a session of its own (a kill of lumen-sim's process group misses it), holds none of lumen-sim's files
    (not the pty master, not the lock), and reads a pipe on which lumen-sim announces each link it publishes and
    removes. When the pipe ends (lumen-sim exited or was killed) it removes the links still announced with
    remove_stale_link() and exits; after a clean stop there are none."""

    def __init__(self):
        r, w = os.pipe()  # not inherited by anything lumen-sim might start
        pid = os.fork()
        if pid == 0:
            try:
                os.close(w)
                _guard(r)
            finally:
                os._exit(0)
        os.close(r)
        os.set_blocking(w, False)  # lumen-sim never waits on its guardian
        self.pid, self.w = pid, w

    def published(self, link, pts):
        self._say(["+", link, pts])

    def removed(self, link):
        self._say(["-", link])

    def _say(self, msg):
        if self.w is None:
            return
        try:
            os.write(self.w, (json.dumps(msg) + "\n").encode())  # one write below PIPE_BUF: never split
        except OSError:  # the guardian is gone, or not reading: nothing lumen-sim can do about it
            pass

    def close(self):
        """lumen-sim stops: the guardian exits (it has nothing left to remove)."""
        if self.w is not None:
            os.close(self.w)
            self.w = None
            try:
                os.waitpid(self.pid, os.WNOHANG)
            except ChildProcessError:
                pass


def _guard(r):
    """The guardian's whole life (in the forked child)."""
    os.setsid()
    os.chdir("/")
    os.closerange(3, r)
    os.closerange(r + 1, os.sysconf("SC_OPEN_MAX"))
    null = os.open(os.devnull, os.O_RDWR)
    for fd in (0, 1, 2):
        os.dup2(null, fd)
    os.close(null)
    links, buf = {}, b""
    while True:
        try:
            data = os.read(r, 65536)
        except InterruptedError:
            continue
        except OSError:
            break
        if not data:
            break
        buf += data
        *lines, buf = buf.split(b"\n")
        for line in lines:
            try:
                op, link, *pts = json.loads(line)
            except ValueError:
                continue
            if op == "+":
                links[link] = pts[0]
            else:
                links.pop(link, None)
    for link, pts in links.items():
        remove_stale_link(link, pts)


class PtyLink:
    def __init__(self, link, guard=None):
        self.link = check_link_path(link)
        self.lock = lock_link(self.link)
        self.guard = guard  # a LinkGuard, told about each link published and removed
        self.master = None
        self.pts = None
        self.host_open = False

    def open(self, link=None):
        """Create a new pty and publish it at the link (or at a new path, which then becomes the link)."""
        path = check_link_path(link) if link else self.link
        lock = lock_link(path) if path != self.link else None
        try:
            master, slave = os.openpty()
        except BaseException:
            if lock is not None:
                os.close(lock)
            raise
        try:
            tty.setraw(slave, termios.TCSANOW)  # no echo, no CR/LF translation either way
            pts = os.ttyname(slave)
            os.close(slave)
            os.set_blocking(master, False)
            tmp = "%s.tmp%d" % (path, os.getpid())
            if os.path.lexists(tmp):
                os.unlink(tmp)
            os.symlink(pts, tmp)
            os.replace(tmp, path)  # atomic: also replaces a link a killed lumen-sim left behind
        except BaseException:
            os.close(master)
            if lock is not None:
                os.close(lock)
            raise
        if lock is not None:
            os.close(self.lock)
            self.lock = lock
        self.link, self.master, self.pts, self.host_open = path, master, pts, False
        if self.guard:
            self.guard.published(path, pts)
        return pts

    def close(self):
        """Close the master (the host gets EIO) and remove the link if it is still ours."""
        if self.master is not None:
            os.close(self.master)
        if self.pts and os.path.islink(self.link) and os.readlink(self.link) == self.pts:
            os.unlink(self.link)
        if self.pts and self.guard:
            self.guard.removed(self.link)
        self.master = self.pts = None
        self.host_open = False

    def release(self):
        """For good: close, and let go of the link path."""
        self.close()
        if self.lock is not None:
            os.close(self.lock)
            self.lock = None

    def host_present(self):
        """Whether a host has the slave open: no POLLHUP on the master. Reads nothing."""
        if self.master is None:
            return False
        p = select.poll()
        p.register(self.master, 0)  # POLLHUP is always reported
        return not any(ev & select.POLLHUP for _, ev in p.poll(0))

    def flush_unread(self):
        """Drop what the host left unread in the slave's input queue; the byte count, or None if it failed."""
        try:
            fd = os.open(self.pts, os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK)
        except (OSError, TypeError):
            return None
        try:
            n = struct.unpack("i", fcntl.ioctl(fd, termios.FIONREAD, b"\0\0\0\0"))[0]
            termios.tcflush(fd, termios.TCIFLUSH)
            return n
        except OSError:
            return None
        finally:
            os.close(fd)

    def read(self):
        """Bytes from the host; b'' if nothing is waiting; None if no host has the port open (EIO)."""
        if self.master is None:
            return None
        try:
            data = os.read(self.master, 4096)
        except BlockingIOError:
            return b""
        except OSError as e:
            if e.errno == errno.EIO:
                return None
            raise
        return data or None

    def write(self, data):
        """Bytes accepted (0 when the slave's input queue is full); -1 when the master is closed or broken.

        A write succeeds while no host has the slave open (the bytes would wait for the next opener): only call this
        while host_open."""
        if self.master is None:
            return -1
        try:
            return os.write(self.master, data)
        except BlockingIOError:
            return 0
        except OSError as e:
            if e.errno == errno.EIO:
                return -1
            raise
