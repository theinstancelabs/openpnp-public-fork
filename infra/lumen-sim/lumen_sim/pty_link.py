"""The serial port the backend opens: a pseudo-terminal whose slave is published through a stable symlink.

The master stays open while the host closes and reopens the slave (so controller state survives, as on the real
board, where opening the USB CDC port does not reset it). On Linux a read on the master gives EIO while no slave is
open; that is how a closed host is detected. Closing the master (power cycle, disconnect) gives the host EIO.
"""
import errno
import os
import termios
import tty


class PtyLink:
    def __init__(self, link):
        self.link = os.path.abspath(link)
        self.master = None
        self.pts = None
        self.host_open = False

    def open(self, link=None):
        """Create a new pty and point the symlink at it."""
        if link:
            self.link = os.path.abspath(link)
        master, slave = os.openpty()
        tty.setraw(slave, termios.TCSANOW)  # no echo, no CR/LF translation either way
        self.pts = os.ttyname(slave)
        os.close(slave)
        os.set_blocking(master, False)
        self.master = master
        self.host_open = False
        tmp = "%s.tmp%d" % (self.link, os.getpid())
        if os.path.lexists(tmp):
            os.unlink(tmp)
        os.symlink(self.pts, tmp)
        os.replace(tmp, self.link)  # atomic re-point
        return self.pts

    def close(self):
        if self.master is not None:
            os.close(self.master)
        self.master = None
        self.host_open = False

    def remove_link(self):
        if os.path.islink(self.link) and os.readlink(self.link) == self.pts:
            os.unlink(self.link)

    def read(self):
        """Bytes from the host; b'' if nothing is waiting; None if no host has the port open."""
        if self.master is None:
            return None
        try:
            data = os.read(self.master, 4096)
        except BlockingIOError:
            self.host_open = True
            return b""
        except OSError as e:
            if e.errno == errno.EIO:
                self.host_open = False
                return None
            raise
        self.host_open = bool(data)
        return data if data else None

    def write(self, data):
        """Bytes accepted (0 when the host is not draining); -1 when the host is gone."""
        if self.master is None:
            return -1
        try:
            return os.write(self.master, data)
        except BlockingIOError:
            return 0
        except OSError as e:
            if e.errno == errno.EIO:
                self.host_open = False
                return -1
            raise
