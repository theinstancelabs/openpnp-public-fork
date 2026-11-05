"""Marlin as built for the LumenPnP: command loop, serial reader, parser, planner front end, G28, quick-stop, kill.

Marlin waits by spinning idle() (read serial, keepalive, ...) inside planner.synchronize(), dwell(), homing moves and
so on, and those waits nest (an M410 read inside G28's wait runs its own wait). The firmware here is a generator with
the same nesting: every wait yields the simulator time it next needs to run; the driver resumes it then, or at once
when host bytes arrive. Source references are to wt/marlin-stock-7776c73 unless they say il/safety.
"""
import math
import re
import struct
from collections import deque

from .machine import PA5_LINE
from .motion import MINIMAL_STEP_RATE, Block, Stepper, Trapezoid

AXES = "XYZAB"
X, Y, Z, A, B = range(5)
BUFSIZE = 4                     # command ring (Configuration_adv.h)
MAX_CMD_SIZE = 96               # 95 characters per line are kept
MINIMUM_PLANNER_SPEED = 0.05
TRAVEL_MAX = [433.0, 487.0, 63.0, 720.0, 720.0]  # soft endstops: 0..max, native
BOOT_NATIVE = [0.0, 0.0, 0.0, 720.0, 720.0]
HOMING_FEED = [150.0, 150.0, 150.0]               # HOMING_FEEDRATE_MM_M / 60
HOMING_BUMP = [10.0, 10.0, 2.0]
HOMING_BUMP_DIVISOR = [2, 2, 4]
Z_AFTER_HOMING = 31.5
XY_PROBE_FEEDRATE = 133.0
KILL_DELAY_S = 0.6              # minkill(): messages get out, the stepper ISR still runs, then cli()
STEPPERS_OFF_DELAY_S = 0.25     # minkill(): after cli(), DELAY_US(250000), then disable_all_steppers() (M112)
DFU_DRAIN_S = 0.1               # il/safety flashFirmware(): 20 x delay(5) for USB CDC to send, then the reset into DFU
DEFAULT_STEPPER_TIMEOUT_MS = 3600 * 1000  # DEFAULT_STEPPER_TIMEOUT_SEC (Configuration_adv.h)
DISABLE_IDLE = AXES             # DISABLE_IDLE_X/Y/Z/I/J: an idle timeout releases every axis
MILLIS_HALF = 2 ** 31           # ELAPSED()/PENDING() compare millis_t as int32_t (core/millis_t.h:32-33)
# Passes of the loop at one instant with nothing to wait for: a wait that never moves on is a model bug. It ends
# as a sim error (the board halts, the process stays up and answers SIGTERM) instead of a spin.
STALL_LIMIT = 10000
FAN_HISTORY = 1000              # output changes kept for the control state

PS_NORMAL, PS_EOL, PS_QUOTED, PS_ESC = 0, 1, 2, 4
NOT_BUSY, IN_HANDLER = 0, 1

# emergency parser states (il/safety, feature/e_parser.h)
EP_RESET, EP_N, EP_M, EP_M1, EP_M10, EP_M11, EP_M4, EP_M41, EP_IGNORE, EP_M108, EP_M112, EP_M410 = range(12)
ERROR_M990 = "Error:M990 S must be 0 or 100..3600000"

# No exponent: GCodeParser::value_float() cuts the number at e/E/x/X before strtof, and value_long() is strtol, so
# "P1e999" reads as 1 on the board (parser.h:258-277).
NUM_RE = re.compile(r"[-+]?(?:\d+\.?\d*|\.\d+)")


class Halted(Exception):
    """kill(): the MCU stops answering."""


def f32(v):
    try:
        return struct.unpack("f", struct.pack("f", v))[0]
    except OverflowError:
        return v


def lround(v):
    return int(math.floor(v + 0.5)) if v >= 0 else -int(math.floor(-v + 0.5))


def mfloat(v, digits=2):
    """Marlin's print of a float: sign, add half of the last digit, then truncate (Print::printFloat)."""
    v = f32(v)
    out = ""
    if v < 0:
        out, v = "-", -v
    rounding = 0.5
    for _ in range(digits):
        rounding /= 10.0
    v += rounding
    whole = int(v)
    rem = v - whole
    out += str(whole)
    if digits:
        out += "."
        for _ in range(digits):
            rem *= 10.0
            d = int(rem)
            out += str(d)
            rem -= d
    return out


class Command:
    """GCodeParser::parse() for one queued line (FASTER_GCODE_PARSER, upper-case letters only)."""

    def __init__(self, text):
        self.text = text
        p = text.lstrip(" ")
        if p[:1] == "N" and len(p) > 1 and (p[1].isdigit() or p[1] in "+-"):
            p = p[2:].lstrip("0123456789").lstrip(" ")
        star = p.find("*", 1)
        if star >= 0:
            p = p[:star].rstrip(" ")
        self.ptr = p                       # command_ptr, echoed by "Unknown command"
        self.letter = self.code = self.string_arg = None
        self.params = {}
        if not p or p[0] not in "GM":
            return
        rest = p[1:].lstrip(" ")
        m = re.match(r"\d+", rest)
        if not m:
            return
        self.letter, self.code = p[0], int(m.group())
        rest = rest[m.end():].lstrip(" ")
        if self.letter == "M" and self.code in (117, 118, 485):
            self.string_arg = rest
            return
        i = 0
        while i < len(rest):
            c = rest[i]
            if "A" <= c <= "Z":
                j = i + 1
                while j < len(rest) and rest[j] == " ":
                    j += 1
                v = NUM_RE.match(rest, j)
                if v:
                    self.params[c] = float(v.group())
                    i = v.end()
                else:
                    self.params[c] = None
                    i = j
            else:
                i += 1

    def val(self, letter, default=None):
        v = self.params.get(letter)
        return default if v is None else v

    def seen(self, letter):
        return letter in self.params


class Firmware:
    def __init__(self, personality, machine, now, emit, on_line=None, kill_messages=False, on_error=None,
                 sim_cap=False):
        p = self.p = personality
        self.machine = machine
        self.now = now
        self._emit_cb = emit                # emit(t, bytes, kind, seq)
        self._on_line = on_line             # on_line(text, t) -> seq
        self._on_error = on_error           # on_error({"t", "line", "error"}): a gap in this model, not a board reply
        self.kill_messages = kill_messages
        self.sim_cap = sim_cap              # M115 says it is lumen-sim (BR-47); never the board's own
        self._load_defaults()
        self.feedrate = 25.0                # mm/s (1500 mm/min)
        self.feedrate_percentage = 100
        self.relative = False
        self.cur = [f32(v) for v in BOOT_NATIVE]   # current_position, native
        self.ws = [0.0] * 5                         # position_shift (G92)
        self.position = [lround(f32(self.cur[i] * self.steps[i])) for i in range(5)]  # planner.position
        self.stepper = Stepper(self.position, machine.physical, self.steps, machine.switches)
        self.stepper.on_move = self.pa5  # each move's end: a press, an overtravel
        self.pa5_pull = 0  # PA5's input mode (M42 P53 T): what it reads with nothing wired
        self.homed = [False, False, False]
        self.enabled = {a: False for a in AXES}
        # M18/M84 S: stepper_inactive_time (0 = never), previous_move_ms, already_shutdown_steppers
        self.stepper_timeout_ms = DEFAULT_STEPPER_TIMEOUT_MS
        self.previous_move = now
        self.idle_released = False
        self.soft_endstops = True
        self.fans = [0, 0, 0, 0]
        self.fan_history = deque(maxlen=FAN_HISTORY)  # the state reply must stay small
        self.led = None
        self.i2c_addr, self.i2c_buf, self.mux, self.sensor_reg = 0, [], None, None
        # serial reader and command ring
        self.input = bytearray()
        self.line = bytearray(MAX_CMD_SIZE)
        self.count = 0
        self.input_state = PS_NORMAL
        self.ring = deque()                 # (text, seq); [0] is the command being run
        self.cur_seq = None
        self.busy_state = NOT_BUSY
        self.keepalive_interval = 2.0
        self.next_busy = now + self.keepalive_interval
        self.halted, self.halt_reason, self.halt_time = False, None, None
        self.steppers_off_at = None  # an M112 kill's disable_all_steppers(), at the end of minkill()
        self.homing_phase, self.in_g28 = None, False
        # il/safety
        self.wd_timeout, self.wd_last, self.wd_tripped = 0, now, False
        self.ep_state, self.ep_m410, self.ep_m112, self.task_busy = EP_RESET, False, False, False
        self.homing_active = self.homing_aborted = False
        self._stall_t, self._stalls = now, 0
        self._gen = self._main()
        self._wake = now
        self._resume()

    def _load_defaults(self):
        p = self.p
        if not hasattr(self, "steps"):
            self.steps = list(p.steps)
            self.mm_per_step = [0.0] * 5
        self.steps[:] = p.steps
        self.mm_per_step[:] = [f32(1.0 / s) for s in self.steps]
        self.max_feed = list(p.max_feed)
        self.max_accel = list(p.max_accel)
        self.accel_print, self.accel_retract, self.accel_travel = p.accel
        self.min_segment_time_us, self.min_feedrate, self.min_travel_feedrate = 20000.0, 0.0, 0.0
        self.junction_deviation = 0.013
        self.currents = dict(p.currents)

    # ------------------------------------------------------------------ driver interface
    def next_wake(self):
        if not self.halted:
            return self._wake
        # a halted board still runs a kill's coast (each move's end, then its freeze) and then lets its steppers go,
        # each at its own time (next_event is inf once frozen)
        coast = self.stepper.freeze_at if self.stepper.freeze_at is not None and not self.stepper.frozen else math.inf
        off = math.inf if self.steppers_off_at is None else self.steppers_off_at
        return min(coast, self.stepper.next_event(self.now), off)

    def advance(self, t):
        """Run everything due up to simulator time t."""
        while not self.halted and self._wake <= t:
            self.now = max(self.now, self._wake)
            self._resume()
        while self.halted and self.next_wake() <= t:
            self.now = max(self.now, self.next_wake())
            self.stepper.update(self.now)
            if self.steppers_off_at is not None and self.steppers_off_at <= self.now:
                self.steppers_off_at = None
                for a in AXES:
                    self.release(a)
        self.now = max(self.now, t)

    def feed(self, data, t):
        """Host bytes arriving at time t."""
        self.advance(t)
        if self.halted:
            return
        if self.p.emergency_parser:
            for c in data:
                self._ep_update(c)
        self.input += data
        self._resume()

    def wake(self, t):
        """Something outside changed what the firmware waits for (a physical fault): run one more pass at t."""
        self.advance(t)
        if not self.halted:
            self._resume()

    def halt(self, reason, t):
        """External fault: the MCU stops dead (no output, input ignored)."""
        self.advance(t)
        if not self.halted:
            self.stepper.freeze(self.now)
            self._dead(reason)
            self._gen.close()

    def _resume(self):
        try:
            self._wake = next(self._gen)
        except Halted:
            self._wake = math.inf
        except Exception as e:  # a model error outside any command: stop dead with the reason, keep the process up
            self._model_error(None, e)
            self.stepper.freeze(self.now)
            self._dead("sim error: %s: %s" % (type(e).__name__, e))
            self._wake = math.inf

    def _dead(self, reason):
        """The MCU stops answering from now on (halt_time: the output it had not sent by then never goes)."""
        self.halted, self.halt_reason, self.halt_time = True, reason, self.now

    def _model_error(self, line, error):
        if self._on_error:
            text = error if isinstance(error, str) else "%s: %s" % (type(error).__name__, error)
            self._on_error({"t": self.now, "line": line, "error": text})

    # ------------------------------------------------------------------ output
    def _emit(self, text, kind="line"):
        data = text if isinstance(text, bytes) else text.encode("latin-1")
        self._emit_cb(self.now, data + b"\n", kind, self.cur_seq)

    def _set_fan(self, p, s, source):
        self.fans[p] = s
        self.fan_history.append({"t": self.now, "p": p, "s": s, "source": source})

    # ------------------------------------------------------------------ loop and waits
    def _main(self):
        while True:
            yield from self._poll()
            if self.ring:
                yield from self._process()
            else:
                d = self._deadline(math.inf)
                if d > self.now:
                    yield d

    def _poll(self):
        """One pass of idle(): stepper progress, serial reader, emergency flags, keepalive, host watchdog."""
        self.stepper.update(self.now)
        yield from self._read_serial()
        self._manage_inactivity()
        if self.p.emergency_parser:
            yield from self._ep_task()
        self._keepalive()
        if self.p.host_watchdog:
            yield from self._watchdog_idle()

    def _idle(self, until=math.inf):
        t = self.now
        yield from self._poll()
        if self.now != t:
            return  # a nested wait ran inside the poll (early M410): let the caller re-check its condition
        d = self._deadline(until)
        if d > self.now:
            yield d

    def _deadline(self, until):
        d = min(until, self.stepper.next_event(self.now))
        if self.busy_state != NOT_BUSY and self.keepalive_interval:
            d = min(d, self.next_busy)
        if self.stepper.cleaning_until > self.now:
            d = min(d, self.stepper.cleaning_until)
        if self.p.host_watchdog and self.wd_timeout and self.busy_state == NOT_BUSY and not self.ring:
            d = min(d, self.wd_last + self.wd_timeout / 1000.0)
        if self.stepper_timeout_ms and not self.stepper.blocks:
            d = min(d, self._idle_timeout_change())
        if d > self.now or self.now != self._stall_t:
            self._stall_t, self._stalls = self.now, 0
        if d <= self.now:
            self._stalls += 1
            if self._stalls > STALL_LIMIT:
                self._stalls = 0
                raise RuntimeError("no progress: %d passes at t=%r with something due and nothing done"
                                   % (STALL_LIMIT, self.now))
        return d

    def _synchronize(self):
        """planner.synchronize(); the last idle() pass runs as the motion ends (busy line on a tie)."""
        if not self.stepper.busy(self.now):
            return
        while self.stepper.busy(self.now):
            yield from self._idle()
        yield from self._poll()

    def _dwell(self, seconds):
        end = self.now + seconds
        if self.now >= end:
            return
        while self.now < end:
            yield from self._idle(end)
        yield from self._poll()

    def _busy_wait(self, seconds):
        """A wait that does not call idle() (M485's packet wait)."""
        end = self.now + seconds
        while self.now < end:
            yield end

    def _idle_at(self, ms):
        """The simulator time ms after the last move. The timeout check and its wake both use this one expression:
        computed two ways, rounding can make the check say 'not yet' at the very time of its wake."""
        return self.previous_move + ms / 1000.0

    def _idle_timed_out(self):
        """stepper_inactive_timeout(): ELAPSED(ms, previous_move_ms + stepper_inactive_time), a signed 32-bit compare.
        A timeout of 2^31 ms or more has run out from the start, until 2^31 ms before its end."""
        timeout = self.stepper_timeout_ms
        return self.now >= self._idle_at(timeout) or (
            timeout > MILLIS_HALF and self.now < self._idle_at(timeout - MILLIS_HALF))

    def _idle_timeout_change(self):
        """When _manage_inactivity() next has something to do while the planner stays empty."""
        if not self.idle_released:
            return self.now if self._idle_timed_out() else self._idle_at(self.stepper_timeout_ms)
        if self.stepper_timeout_ms > MILLIS_HALF:  # the window where it reads 'pending' again
            end = self._idle_at(self.stepper_timeout_ms - MILLIS_HALF)
            if end > self.now:
                return end
        return math.inf

    def _manage_inactivity(self):
        """The M18/M84 idle timeout: once the planner has been empty that long, release every axis, once."""
        if self.stepper.blocks:
            self.previous_move = self.now
        else:  # the board polls without pause, so its last look at a queued block was when the last one ended
            self.previous_move = max(self.previous_move, self.stepper.last_end)
        if not self.stepper_timeout_ms:
            return
        if not self.stepper.blocks and self._idle_timed_out():
            if not self.idle_released:
                self.idle_released = True
                for a in DISABLE_IDLE:
                    self.release(a)
        else:
            self.idle_released = False

    def _keepalive(self):
        if self.busy_state != NOT_BUSY and self.keepalive_interval:
            if self.now < self.next_busy:
                return
            self._emit("echo:busy: processing", "busy")
        self.next_busy = self.now + self.keepalive_interval

    # ------------------------------------------------------------------ serial reader (gcode/queue.cpp)
    def _read_serial(self):
        while self.input and len(self.ring) < BUFSIZE:
            c = self.input.pop(0)
            if c in (10, 13):
                self.input_state = PS_NORMAL
                self.line[self.count] = 0
                if self.count == 0:
                    continue
                self.count = 0
                text = self._line_text()
                seq = self._on_line(text, self.now) if self._on_line else None
                if self.p.host_watchdog:
                    self.wd_last = self.now
                cmd = text.lstrip(" ")
                if not self.p.emergency_parser and cmd[:1] == "M" and len(cmd) >= 4:
                    # "Process critical commands early" (queue.cpp:543-548): while reading, inside any wait
                    if cmd[1:4] == "112":
                        self._kill("M112 Shutdown", steppers_off=True)
                    elif cmd[1:4] == "410":
                        yield from self._quickstop_stepper()
                # the shared line buffer may hold a later line by now (read during the quick-stop's wait)
                self._enqueue(self._line_text(), seq)
            else:
                self._stream_char(c)

    def _line_text(self):
        raw = bytes(self.line)
        return raw[:raw.index(0)].decode("latin-1") if 0 in raw else raw.decode("latin-1")

    def _stream_char(self, c):
        s = self.input_state
        if s == PS_EOL:
            return
        if s >= PS_ESC:
            self.input_state = s - PS_ESC
        elif c == 0x5C:  # backslash
            self.input_state = s + PS_ESC
            if self.input_state == PS_ESC:
                return
        elif s == PS_QUOTED:
            if c == 0x22:
                self.input_state = PS_NORMAL
        elif c == 0x22:
            self.input_state = PS_QUOTED
        elif c == 0x3B:  # ';' comment to end of line
            self.input_state = PS_EOL
            return
        if c == 0x08:
            if self.count:
                self.count -= 1
                self.line[self.count] = 0
        else:
            self.line[self.count] = c
            self.count += 1
            if self.count >= MAX_CMD_SIZE - 1:
                self.input_state = PS_EOL

    def _enqueue(self, text, seq):
        if text.startswith(";") or len(self.ring) >= BUFSIZE:
            return False
        self.ring.append((text, seq))
        return True

    def _process(self):
        text, seq = self.ring[0]
        self.cur_seq = seq
        prev, self.busy_state = self.busy_state, IN_HANDLER
        try:
            no_ok = yield from self._dispatch(Command(text))
        except Halted:
            raise
        except Exception as e:  # the model cannot run this line; the board runs it and acks, so ack and record it
            self._model_error(text, e)
            no_ok = False
        if not no_ok:
            self._emit("ok", "ok")
        self.busy_state = prev
        self.ring.popleft()
        self.cur_seq = None

    # ------------------------------------------------------------------ emergency parser (il/safety)
    def _ep_update(self, c):
        s = self.ep_state
        eol = c in (10, 13)
        if s == EP_RESET:
            s = {32: EP_RESET, 10: EP_RESET, 13: EP_RESET, 78: EP_N, 77: EP_M}.get(c, EP_IGNORE)
        elif s == EP_N:
            s = s if (48 <= c <= 57 or c in (45, 32)) else (EP_M if c == 77 else EP_IGNORE)
        elif s == EP_M:
            s = {32: EP_M, 49: EP_M1, 52: EP_M4}.get(c, EP_IGNORE)
        elif s == EP_M1:
            s = {48: EP_M10, 49: EP_M11}.get(c, EP_IGNORE)
        elif s == EP_M10:
            s = EP_M108 if c == 56 else EP_IGNORE
        elif s == EP_M11:
            s = EP_M112 if c == 50 else EP_IGNORE
        elif s == EP_M4:
            s = EP_M41 if c == 49 else EP_IGNORE
        elif s == EP_M41:
            s = EP_M410 if c == 48 else EP_IGNORE
        elif s == EP_IGNORE:
            s = EP_RESET if eol else s
        elif eol:
            if s == EP_M112:
                self.ep_m112 = True
            elif s == EP_M410:
                self.ep_m410 = True
            s = EP_RESET
        self.ep_state = s

    def _ep_task(self):
        """Temperature::task(): acts on the emergency flags; no re-entry while its quick-stop waits."""
        if self.task_busy:
            return
        self.task_busy = True
        try:
            if self.ep_m112:
                self._kill("M112 Shutdown", steppers_off=True)
            if self.ep_m410:
                self.ep_m410 = False
                yield from self._quickstop_stepper()
        finally:
            self.task_busy = False

    # ------------------------------------------------------------------ host watchdog (il/safety M990)
    def _watchdog_idle(self):
        if not self.wd_timeout:
            return
        if self.busy_state != NOT_BUSY or self.ring:
            self.wd_last = self.now
            return
        if self.now >= self.wd_last + self.wd_timeout / 1000.0:
            self.wd_timeout, self.wd_tripped = 0, True
            self.stepper.quick_stop(self.now)
            self._outputs_off("watchdog")
            if self.homing_active:
                self.homing_aborted = True
            self.release("B")
            self._emit("Error:host watchdog")
            yield from self._synchronize()
            self._set_current_from_steppers()
            self._sync_plan_position()

    def _outputs_off(self, source):
        for p in (3, 2, 1, 0):  # valve first
            self._set_fan(p, 0, source)

    # ------------------------------------------------------------------ quick-stop and kill
    def _quickstop_stepper(self):
        self.stepper.quick_stop(self.now)
        if self.p.homing_abort:  # il/safety: vent (valve, then pump) now, and end a G28 in progress cleanly
            self._set_fan(3, 0, "M410")
            self._set_fan(2, 0, "M410")
            if self.homing_active:
                self.homing_aborted = True
        yield from self._synchronize()
        self._set_current_from_steppers()
        self._sync_plan_position()

    def _kill(self, reason, steppers_off=False):
        if self.kill_messages:
            self._emit("echo:" + reason)
            self._emit("Error:Printer halted. kill() called!")
        if self.p.outputs_off_on_kill:
            self._outputs_off("kill")
            self.stepper.freeze(self.now)  # planner.quick_stop() first: the motion ends now, the drivers stay on
        else:
            self.stepper.freeze(self.now + KILL_DELAY_S)
        if steppers_off:  # both: minkill() holds the drivers until then (advance lets them go)
            self.steppers_off_at = self.now + KILL_DELAY_S + STEPPERS_OFF_DELAY_S
        self._dead(reason)
        raise Halted(reason)

    def release(self, axis):
        """stepper.disable_axis(): the driver lets go. A released Z moves by the machine's z_drop."""
        if axis == "Z" and self.enabled["Z"]:
            self.machine.release_z()
            self.pa5(self.now)  # a drop under or off the switch, where the head is (a quick stop's move may run)
        self.enabled[axis] = False

    # ------------------------------------------------------------------ positions
    def logical(self):
        return [f32(self.cur[i] + self.ws[i]) for i in range(5)]

    def _report_line(self, counts):
        return "X:%s Y:%s Z:%s A:%s B:%s Count X:%d Y:%d Z:%d A:%d B:%d" % (
            *[mfloat(v) for v in self.logical()], *counts)

    def _report_current_position(self):
        self._emit(self._report_line(self.stepper.realtime_counts(self.now)))

    def _set_machine_position(self, native):
        self.position = [lround(f32(f32(native[i]) * self.steps[i])) for i in range(5)]
        self.stepper.set_counts(self.position)

    def _sync_plan_position(self):
        self._set_machine_position(self.cur)

    def _set_current_from_steppers(self):
        counts = self.stepper.realtime_counts(self.now)
        self.cur = [f32(counts[i] * self.mm_per_step[i]) for i in range(5)]

    # ------------------------------------------------------------------ planner front end (planner.cpp)
    def _buffer_line(self, target, fr):
        while self.stepper.full(self.now):  # Marlin: while (moves_free() < count) idle();
            yield from self._idle()
        if self.stepper.cleaning(self.now):
            return False  # dropped for 1 s after a quick-stop
        if self.p.homing_abort and self.homing_aborted:
            return False  # il/safety: the rest of an aborted G28
        block = self._make_block(target, fr)
        if block is not None:
            self.stepper.add(block, self.now)
        return True

    def _make_block(self, target, fr):
        tgt = [lround(f32(f32(target[i]) * self.steps[i])) for i in range(5)]
        delta = [tgt[i] - self.position[i] for i in range(5)]
        n = max(abs(d) for d in delta)
        if n < 1:
            return None
        dmm = [f32(delta[i] * self.mm_per_step[i]) for i in range(5)]
        if not (dmm[X] or dmm[Y] or dmm[A] or dmm[B]):
            dist = abs(dmm[Z])
        else:
            d2 = dmm[X] ** 2 + dmm[Y] ** 2 + dmm[Z] ** 2
            if d2 < 1e-12:  # rotation only: degrees (LinuxCNC rule)
                d2 = dmm[A] ** 2 + dmm[B] ** 2
            dist = f32(math.sqrt(d2))
        fr = max(fr, self.min_travel_feedrate)
        inv_mm = f32(1.0 / dist)
        inv_secs = f32(fr * inv_mm)
        speed_factor = 1.0
        for i in range(5):
            if delta[i]:
                cs = abs(f32(dmm[i] * inv_secs))
                if cs > self.max_feed[i]:
                    speed_factor = min(speed_factor, f32(self.max_feed[i] / cs))
        rate = math.ceil(f32(n * inv_secs))
        nominal_speed = f32(dist * inv_secs)
        if speed_factor < 1.0:
            rate = int(rate * speed_factor)
            nominal_speed = f32(nominal_speed * speed_factor)
        rate = max(rate, 1)
        accel = math.ceil(f32(self.accel_travel * f32(n * inv_mm)))
        for i in range(5):
            if delta[i]:
                max_steps_accel = int(self.max_accel[i] * self.steps[i])
                if max_steps_accel < accel:
                    accel = min(accel, max_steps_accel * n // abs(delta[i]))
        accel = max(accel, 1)
        r0 = max(math.ceil(rate * MINIMUM_PLANNER_SPEED / nominal_speed), MINIMAL_STEP_RATE)
        for i in range(5):
            if delta[i]:
                self.enabled[AXES[i]] = True
        self.position = tgt
        return Block(tgt, delta, Trapezoid(n, rate, accel, r0, r0),
                     extra_delay=self.machine.homing_move_delay_s if self.in_g28 else 0.0)

    def _prepare_line_to(self, dest):
        if self.soft_endstops:
            for i in (X, Y, Z):
                if self.homed[i]:
                    dest[i] = min(max(dest[i], 0.0), TRAVEL_MAX[i])
        yield from self._buffer_line(dest, f32(self.feedrate * self.feedrate_percentage / 100.0))
        self.cur = dest

    # ------------------------------------------------------------------ dispatch
    def _dispatch(self, cmd):
        table = {"G": self.G, "M": self.M}.get(cmd.letter, {})
        handler = table.get(cmd.code)
        if handler is None and self.p.host_watchdog and cmd.letter == "M":
            handler = {990: self.m990, 997: self.m997}.get(cmd.code)
        if handler is None:
            self._emit('echo:Unknown command: "%s"' % cmd.ptr)
            return False
        r = handler(cmd)
        if r is not None and hasattr(r, "send"):
            r = yield from r
        return bool(r)

    @property
    def G(self):
        return {0: self.g0_g1, 1: self.g0_g1, 2: self.g0_g1, 3: self.g0_g1, 4: self.g4, 17: _noop, 18: _noop,
                19: _noop, 21: _noop, 28: self.g28, 90: self.g90, 91: self.g91, 92: self.g92}

    @property
    def M(self):
        return {17: self.m17, 18: self.m18, 84: self.m18, 42: self.m42, 43: self.m43, 92: self.m92, 105: self.m105,
                106: self.m106, 107: self.m107, 108: _noop, 110: _noop, 111: _noop, 112: self.m112,
                113: self.m113, 114: self.m114, 115: self.m115, 119: self.m119, 122: _noop, 150: self.m150,
                201: self.m201, 203: self.m203, 204: self.m204, 205: self.m205, 206: _noop, 211: self.m211,
                220: self.m220, 260: self.m260, 261: self.m261, 400: self.m400, 410: self.m410, 485: self.m485,
                502: self.m502, 503: self.m503, 569: _noop, 906: self.m906, 999: _noop, 3426: self.m3426}

    # ------------------------------------------------------------------ G codes
    def g0_g1(self, cmd):
        dest = list(self.cur)
        for i, a in enumerate(AXES):
            v = cmd.params.get(a)
            if v is not None:
                v = f32(v)
                dest[i] = f32(self.cur[i] + v) if self.relative else f32(v - self.ws[i])
        f = cmd.params.get("F")
        if f is not None and f > 0:
            self.feedrate = f32(f / 60.0)
        yield from self._prepare_line_to(dest)

    def g4(self, cmd):
        ms = 0
        if cmd.params.get("P") is not None:
            ms = int(cmd.params["P"])
        if cmd.params.get("S") is not None:
            ms = int(cmd.params["S"] * 1000)
        yield from self._synchronize()
        yield from self._dwell(ms / 1000.0)

    def g90(self, cmd):
        self.relative = False

    def g91(self, cmd):
        self.relative = True

    def g92(self, cmd):
        for i, a in enumerate(AXES):
            v = cmd.params.get(a)
            if v is not None:
                native = f32(f32(v) - self.ws[i])
                d = f32(native - self.cur[i])
                if abs(d) > 1e-6:
                    self.ws[i] = f32(self.ws[i] + d)
        self._report_current_position()

    def g28(self, cmd):
        il = self.p.homing_abort
        if il:
            self.homing_aborted, self.homing_active = False, True
        yield from self._synchronize()
        self.previous_move = self.now   # reset_stepper_timeout(): homing counts as activity
        home_z = cmd.seen("Z")
        home_x = (home_z and not self.homed[X]) or cmd.seen("X")
        home_y = (home_z and not self.homed[Y]) or cmd.seen("Y")
        home_all = home_x == home_y == home_z == cmd.seen("A") == cmd.seen("B")
        self.stepper.endstops_enabled, self.in_g28 = True, True
        if home_all or home_x:
            yield from self._homeaxis(X)
        if home_all or home_y:
            yield from self._homeaxis(Y)
        if home_all or home_z:
            yield from self._home_z_safely()
            yield from self._move_after_z_homing()
        self._sync_plan_position()
        self.stepper.endstops_enabled, self.in_g28, self.homing_phase = False, False, None
        if il:
            self.homing_active = False
            if self.homing_aborted:
                self.homing_aborted = False
                self.homed = [False, False, False]
                self._emit("echo:G28 aborted by quick-stop: axes not homed")
        self._report_current_position()

    def _aborted(self):
        return self.p.homing_abort and self.homing_aborted

    def _homeaxis(self, axis):
        if self._aborted():
            return
        name, bump = AXES[axis], -HOMING_BUMP[axis]
        self.homing_phase = name + "_APPROACH"
        yield from self._do_homing_move(axis, -1.5 * TRAVEL_MAX[axis])
        self.homing_phase = name + "_BUMP"
        yield from self._do_homing_move(axis, -bump)
        self.homing_phase = name + "_SLOW"
        yield from self._do_homing_move(axis, 2 * bump, HOMING_FEED[axis] / HOMING_BUMP_DIVISOR[axis])
        if self._aborted():
            return
        self.homed[axis] = True          # set_axis_is_at_home()
        self.cur[axis] = 0.0
        self.ws[axis] = 0.0
        self._sync_plan_position()

    def _do_homing_move(self, axis, distance, fr=0.0):
        if self._aborted():
            return
        counts = self.stepper.realtime_counts(self.now)
        pos = [f32(counts[i] * self.mm_per_step[i]) for i in range(5)]
        pos[axis] = 0.0
        self._set_machine_position(pos)
        pos[axis] = f32(distance)
        yield from self._buffer_line(pos, fr or HOMING_FEED[axis])
        yield from self._synchronize()
        if distance < 0 and not self._aborted():   # validate_homing_move()
            if self.stepper.endstop_hit:
                self.stepper.endstop_hit = False
            else:
                self._kill("Homing Failed")

    def _home_z_safely(self):
        need = "".join(AXES[a] for a in (X, Y) if not self.homed[a])
        if need:
            self._emit("echo:Home %s First" % need)
            return
        self._sync_plan_position()
        if self.cur[X] != 0.0 or self.cur[Y] != 0.0:
            self.homing_phase = "Z_SAFE_XY"
            yield from self._do_blocking_move_to(0.0, 0.0, self.cur[Z], XY_PROBE_FEEDRATE)
        yield from self._homeaxis(Z)

    def _move_after_z_homing(self):
        z = min(Z_AFTER_HOMING, TRAVEL_MAX[Z])
        if z == self.cur[Z]:
            return
        self.homing_phase = "Z_AFTER"
        yield from self._do_blocking_move_to(self.cur[X], self.cur[Y], z, HOMING_FEED[Z])

    def _do_blocking_move_to(self, x, y, z, fr_xy, fr_z=HOMING_FEED[Z]):
        if self.cur[Z] < z:
            self.cur[Z] = f32(z)
            yield from self._buffer_line(list(self.cur), fr_z)
        self.cur[X], self.cur[Y] = f32(x), f32(y)
        yield from self._buffer_line(list(self.cur), fr_xy)
        if self.cur[Z] > z:
            self.cur[Z] = f32(z)
            yield from self._buffer_line(list(self.cur), fr_z)
        yield from self._synchronize()

    # ------------------------------------------------------------------ M codes
    def m17(self, cmd):
        for a in [a for a in AXES if cmd.seen(a)] or AXES:
            self.enabled[a] = True

    def m18(self, cmd):
        if cmd.params.get("S") is not None:  # seenval('S'): set the idle timeout; nothing waits, nothing is released
            self.previous_move = self.now
            ms = f32(f32(cmd.params["S"]) * 1000.0)  # SEC_TO_MS: float to millis_t, which saturates at 0 on the STM32
            self.stepper_timeout_ms = 0 if ms <= 0 else min(int(ms), 0xFFFFFFFF)
            return
        axes = [a for a in AXES if cmd.seen(a)]
        yield from self._synchronize()
        for a in axes or AXES:
            self.release(a)

    def m42(self, cmd):
        """M42 P53 T<mode>: PA5's input mode, which sets what it reads with nothing wired (T2 pull-up 1; T3 pull-down
        and T0 floating 0, as read 11-02). Other M42 forms only ack."""
        if cmd.val("P") == 53 and cmd.val("T") in (0, 2, 3):
            self.pa5_pull = 1 if cmd.val("T") == 2 else 0

    def m43(self, cmd):
        """M43 P5: the PA5 pin's line as the board prints it, read where the head is now (M43 waits for no move)."""
        # shortcut: PA5 alone (the switch, 11-04); M43 without P or another pin prints nothing: add a pin's line here
        # when a route reads it
        if cmd.val("P") == 5:
            self._emit(PA5_LINE % self.pa5(self.now))

    def pa5(self, at=None):
        """The PA5 switch with the head where it is at time `at` (default: where the last move ended): the pin's
        level (0 with nothing wired, as the board read 11-02); an overtravel goes to the model's errors."""
        sw = self.machine.pa5
        if sw is None:
            return self.pa5_pull
        x, y, z = (self.machine.physical if at is None else self.stepper.realtime_physical(at))[:3]
        level, overtravel = sw.observe(x, y, z)
        if overtravel:
            self._model_error(None, overtravel)
        return level

    def m92(self, cmd):
        for i, a in enumerate(AXES):
            if cmd.params.get(a) is not None:
                v = f32(cmd.params[a])
                if not v:  # the board takes it (mm_per_step becomes inf); the model would divide by zero
                    self._model_error(cmd.text, "not modelled: zero steps/unit for %s, kept %s" % (a, self.steps[i]))
                    continue
                self.steps[i] = v
                self.mm_per_step[i] = f32(1.0 / v)
        self._sync_plan_position()

    def m105(self, cmd):
        self._emit("ok T:0", "ok")
        return True

    def m106(self, cmd):
        p = int(cmd.val("P", 0))
        if not 0 <= p < 4:
            return
        s = 255
        if cmd.seen("S"):
            s = int(cmd.val("S", 0)) & 0xFFFF
        s = min(s, 255)
        if self.p.onoff_right_outputs and p in (2, 3):
            s = 255 if s else 0
        self._set_fan(p, s, "M106")

    def m107(self, cmd):
        p = int(cmd.val("P", 0))
        if 0 <= p < 4:
            self._set_fan(p, 0, "M107")

    def m112(self, cmd):
        if not self.p.emergency_parser:  # stock acts on read already; with the emergency parser this is a no-op
            self._kill("M112 Shutdown", steppers_off=True)

    def m113(self, cmd):
        if cmd.params.get("S") is not None:  # seenval('S'); value_byte() is 0..255, then NOMORE(..., 60)
            self.keepalive_interval = float(min(max(int(cmd.params["S"]), 0), 255, 60))
        else:
            self._emit("echo:M113 S%d" % int(self.keepalive_interval))

    def m114(self, cmd):
        self._emit(self._report_line(self.position))  # projected: planned end, no wait

    def m115(self, cmd):
        for line in self.p.m115(self.sim_cap):
            self._emit(line)

    def m119(self, cmd):
        phys = self.stepper.realtime_physical(self.now)
        self._emit("Reporting endstop status")
        for i, name in ((X, "x_min"), (Y, "y_min"), (Z, "z_min")):
            self._emit("%s: %s" % (name, "TRIGGERED" if phys[i] - self.machine.switches[i] <= 1e-9 else "open"))

    def m150(self, cmd):
        self.led = {k: int(cmd.val(k, 0)) for k in "PRUBW" if cmd.seen(k)}

    def m201(self, cmd):
        for i, a in enumerate(AXES):
            if cmd.params.get(a) is not None:
                self.max_accel[i] = int(cmd.params[a])

    def m203(self, cmd):
        for i, a in enumerate(AXES):
            if cmd.params.get(a) is not None:
                self.max_feed[i] = f32(cmd.params[a])

    def m204(self, cmd):
        if cmd.params.get("S") is not None:
            self.accel_print = self.accel_travel = f32(cmd.params["S"])
        if cmd.params.get("P") is not None:
            self.accel_print = f32(cmd.params["P"])
        if cmd.params.get("R") is not None:
            self.accel_retract = f32(cmd.params["R"])
        if cmd.params.get("T") is not None:
            self.accel_travel = f32(cmd.params["T"])

    def m205(self, cmd):
        for key in "BD":
            if cmd.params.get(key) is not None:
                self.min_segment_time_us = f32(cmd.params[key])
        if cmd.params.get("S") is not None:
            self.min_feedrate = f32(cmd.params["S"])
        if cmd.params.get("T") is not None:
            self.min_travel_feedrate = f32(cmd.params["T"])
        if cmd.params.get("J") is not None:
            self.junction_deviation = f32(cmd.params["J"])

    def m211(self, cmd):
        if cmd.seen("S"):
            self.soft_endstops = bool(cmd.val("S", 0))
            return
        on = self.soft_endstops
        self._emit("  M211 S%d ; %s" % (int(on), "ON" if on else "OFF"))
        lo = [mfloat(f32(0.0 + self.ws[i])) for i in range(5)]
        hi = [mfloat(f32(TRAVEL_MAX[i] + self.ws[i])) for i in range(5)]
        self._emit("  Min:  X%s Y%s Z%s A%s B%s   Max:  X%s Y%s Z%s A%s B%s" % (*lo, *hi))

    def m220(self, cmd):
        if cmd.seen("S"):
            self.feedrate_percentage = int(cmd.val("S", 100))
        else:
            self._emit("FR:%d%%" % self.feedrate_percentage)

    def m260(self, cmd):
        if cmd.seen("A"):
            self.i2c_addr = min(max(int(cmd.val("A", 0)), 0), 255)
        if cmd.seen("B"):
            self.i2c_buf.append(min(max(int(cmd.val("B", 0)), 0), 255))
        if cmd.seen("S"):
            if self.i2c_addr == 112 and self.i2c_buf:
                self.mux = self.i2c_buf[0]
            elif self.i2c_addr == 109 and self.i2c_buf:
                self.sensor_reg = self.i2c_buf[-1]
            self.i2c_buf = []
        elif cmd.seen("R"):
            self.i2c_buf = []

    def m261(self, cmd):
        if cmd.seen("A"):
            self.i2c_addr = min(max(int(cmd.val("A", 0)), 0), 255)
        count = int(cmd.val("B", 1))
        style = int(cmd.val("S", 0))
        if not (self.i2c_addr and 0 < count <= 32):
            self._emit("Error:Bad i2c request")
            return
        data = self.machine.i2c_read(self, self.i2c_addr, count)
        out = b"echo:i2c-reply: from:%d bytes:%d data:" % (self.i2c_addr, count)
        if style == 1:
            out += " ".join("%02X" % v for v in data).encode()
        elif style == 2:
            if data:
                hi = data[-2] if len(data) >= 2 else 0
                v = (hi << 8) | data[-1]
                out += str(v - 0x10000 if v & 0x8000 else v).encode()
        elif style == 3:
            out += " ".join(str(v) for v in data).encode()
        else:
            out += bytes(data)
        self._emit(out)

    def m400(self, cmd):
        yield from self._synchronize()

    def m410(self, cmd):
        if not self.p.emergency_parser:  # with the emergency parser the queued copy does nothing
            yield from self._quickstop_stepper()

    def m485(self, cmd):
        arg = cmd.string_arg or ""
        if len(arg) % 2:
            self._emit("Error:String must contain an even number of bytes.")
            return
        bad = next((c for c in arg if c not in "0123456789abcdefABCDEF"), None)
        if bad is not None:
            self._emit("Error:Not a hex character: " + bad)
            return
        reply = self.machine.rs485_reply(arg)
        if reply is None:
            yield from self._busy_wait(0.100)
            self._emit("rs485-reply: TIMEOUT")
        else:
            yield from self._busy_wait(0.054)
            self._emit("rs485-reply: " + reply.upper())

    def m502(self, cmd):
        self._load_defaults()
        self._sync_plan_position()
        self._emit("echo:Hardcoded Default Settings Loaded")

    def m503(self, cmd):
        st, mf, ma, c = self.steps, self.max_feed, self.max_accel, self.currents
        for line in [
            "; Linear Units:", "  G21 ; (mm)",
            "; Temperature Units:", "  M149 C ; Units in Celsius",
            "; Steps per unit:", "  M92 X%s Y%s Z%s A%s B%s" % tuple(mfloat(v) for v in st),
            "; Max feedrates (units/s):", "  M203 X%s Y%s Z%s A%s B%s" % tuple(mfloat(v) for v in mf),
            "; Max Acceleration (units/s2):",
            "  M201 X%s Y%s Z%s A%d B%d" % (mfloat(ma[0]), mfloat(ma[1]), mfloat(ma[2]), ma[3], ma[4]),
            "; Acceleration (units/s2) (P<print-accel> R<retract-accel> T<travel-accel>):",
            "  M204 P%s R%s T%s" % (mfloat(self.accel_print), mfloat(self.accel_retract), mfloat(self.accel_travel)),
            "; Advanced (D<min_segment_time_us> S<min_feedrate> T<min_travel_feedrate> J<junc_dev>):",
            "  M205 D%s S%s T%s J%s" % (mfloat(self.min_segment_time_us), mfloat(self.min_feedrate),
                                         mfloat(self.min_travel_feedrate), mfloat(self.junction_deviation)),
            "; Home offset:", "  M206 X0.00 Y0.00 Z0.00 A0.00 B0.00",
            "; Stepper driver current:",
            "  M906 X%d Y%d Z%d A%d B%d" % (c["X"], c["Y"], c["Z"], c["A"], c["B"]),
            "  M906 I1 Y%d" % c["Y2"],
            "; Driver stepping mode:", "  M569 S1 Z A B", "  M569 S1  A", "  M569 S1  B",
        ]:
            self._emit("echo:" + line)

    def m906(self, cmd):
        second = int(cmd.val("I", 0)) == 1
        for a in AXES:
            if cmd.params.get(a) is not None:
                self.currents["Y2" if (second and a == "Y") else a] = int(cmd.params[a])

    def m3426(self, cmd):
        value = self.machine.mcp3426
        if value is None:
            self._emit("Error:MCP342X i2c error")
        else:
            self._emit("V:%d C:%d G:%d I:%d" % (value, int(cmd.val("C", 1)), int(cmd.val("G", 1)),
                                                1 if cmd.seen("I") and cmd.val("I", 1) else 0))

    # il/safety only
    def m990(self, cmd):
        if cmd.seen("S"):
            ms = int(cmd.val("S", 0))
            if ms and not 100 <= ms <= 3600000:
                self._emit(ERROR_M990)
                return
            self.wd_last, self.wd_timeout = self.now, ms
            if ms:
                self.wd_tripped = False
        report = "echo:Host watchdog: " + ("armed S%d" % self.wd_timeout if self.wd_timeout else "off")
        self._emit(report + (" tripped" if self.wd_tripped else ""))

    def m997(self, cmd):
        """il/safety's flashFirmware() (dfu_bootloader.cpp): planner.quick_stop(), every output off, every stepper let
        go, the echo; then it answers nothing more and, DFU_DRAIN_S later (USB CDC sends what was due, the echo with
        it), resets into the STM32 DFU bootloader: the serial port goes away until a power cycle."""
        self.stepper.freeze(self.now)
        self._outputs_off("M997")
        for a in AXES:
            self.release(a)
        self._emit("echo:M997: entering STM32 DFU bootloader (0483:df11)")
        self._dead("DFU")
        raise Halted("DFU")


def _noop(cmd):
    return None
