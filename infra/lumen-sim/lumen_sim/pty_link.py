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
"""
import errno
import fcntl
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


class PtyLink:
    def __init__(self, link):
        self.link = check_link_path(link)
        self.master = None
        self.pts = None
        self.host_open = False

    def open(self, link=None):
        """Create a new pty and publish it at the link (or at a new path, which then becomes the link)."""
        path = check_link_path(link) if link else self.link
        master, slave = os.openpty()
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
            raise
        self.link, self.master, self.pts, self.host_open = path, master, pts, False
        return pts

    def close(self):
        """Close the master (the host gets EIO) and remove the link if it is still ours."""
        if self.master is not None:
            os.close(self.master)
        if self.pts and os.path.islink(self.link) and os.readlink(self.link) == self.pts:
            os.unlink(self.link)
        self.master = self.pts = None
        self.host_open = False

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
