"""Firmware builds the simulator can be: the board as flashed today (stock) and il/safety d973aef69 (il-safety).

Numbers come from the stock source wt/marlin-stock-7776c73 (Configuration.h / Configuration_adv.h) and the
il/safety diff, as listed in RESEARCH.md section 5.
"""

FIRMWARE_LINE = ("FIRMWARE_NAME:Marlin bugfix-2.1.x (Sep 19 2024 13:39:21) "
                 "SOURCE_CODE_URL:github.com/MarlinFirmware/Marlin PROTOCOL_VERSION:1.0 "
                 "MACHINE_TYPE:LumenPnP EXTRUDER_COUNT:0 AXIS_COUNT:5")

# M115 capability lines in firmware order (serial.log lines 3-38); EMERGENCY_PARSER is filled per build
CAPS = [
    ("QUOTED_STRINGS", 1), ("SERIAL_XON_XOFF", 0), ("BINARY_FILE_TRANSFER", 0), ("EEPROM", 0), ("VOLUMETRIC", 0),
    ("AUTOREPORT_POS", 0), ("AUTOREPORT_TEMP", 0), ("PROGRESS", 0), ("PRINT_JOB", 1), ("AUTOLEVEL", 0),
    ("RUNOUT", 0), ("Z_PROBE", 0), ("LEVELING_DATA", 0), ("BUILD_PERCENT", 0), ("SOFTWARE_POWER", 0),
    ("TOGGLE_LIGHTS", 0), ("CASE_LIGHT_BRIGHTNESS", 0), ("EMERGENCY_PARSER", None), ("HOST_ACTION_COMMANDS", 0),
    ("PROMPT_SUPPORT", 0), ("SDCARD", 0), ("REPEAT", 0), ("SD_WRITE", 0), ("AUTOREPORT_SD_STATUS", 0),
    ("LONG_FILENAME", 0), ("LFN_WRITE", 0), ("CUSTOM_FIRMWARE_UPLOAD", 0), ("EXTENDED_M20", 0),
    ("THERMAL_PROTECTION", 1), ("MOTION_MODES", 0), ("ARCS", 1), ("BABYSTEPPING", 0), ("CHAMBER_TEMPERATURE", 0),
    ("COOLER_TEMPERATURE", 0), ("MEATPACK", 0), ("CONFIG_EXPORT", 0),
]
# Not the board's: lumen-sim's own capability line, sent only behind --sim-cap (set's sim_cap). OpenPnP's SimGuard
# (BR-47) treats a pty whose M115 carries exactly this line as a simulator. Off by default, so M115 stays the board's
# byte for byte and the bug reproductions see the real controller.
SIM_CAP_LINE = "Cap:SIMULATOR:lumen-sim"


class Personality:
    def __init__(self, name, steps, max_accel, b_current, emergency_parser, host_watchdog, onoff_right_outputs,
                 homing_abort, outputs_off_on_kill):
        self.name = name
        self.steps = steps                      # DEFAULT_AXIS_STEPS_PER_UNIT
        self.max_feed = [1500.0, 1500.0, 500.0, 50000.0, 50000.0]  # DEFAULT_MAX_FEEDRATE (units/s)
        self.max_accel = max_accel              # DEFAULT_MAX_ACCELERATION (units/s^2)
        self.accel = (2000.0, 3000.0, 2500.0)   # M204 P R T defaults
        self.currents = {"X": 800, "Y": 1000, "Z": 800, "A": 200, "B": b_current, "Y2": 1000}
        self.emergency_parser = emergency_parser
        self.host_watchdog = host_watchdog
        self.onoff_right_outputs = onoff_right_outputs   # M106 P2/P3: any S>0 is 255
        self.homing_abort = homing_abort                 # a quick-stop ends G28 without kill()
        self.outputs_off_on_kill = outputs_off_on_kill

    def m115(self, sim_cap=False):
        """M115's lines before the ok; sim_cap adds SIM_CAP_LINE last of the Cap lines."""
        lines = [FIRMWARE_LINE]
        for cap, value in CAPS:
            if value is None:
                value = 1 if self.emergency_parser else 0
            lines.append("Cap:%s:%d" % (cap, value))
        if self.host_watchdog:
            lines.append("Cap:HOST_WATCHDOG:1")
        if sim_cap:
            lines.append(SIM_CAP_LINE)
        return lines


STOCK = Personality("stock", steps=[320.0, 320.0, 40.0, 4.44, 4.44],
                    max_accel=[10000, 10000, 10000, 10000, 10000], b_current=200,
                    emergency_parser=False, host_watchdog=False, onoff_right_outputs=False,
                    homing_abort=False, outputs_off_on_kill=False)

IL_SAFETY = Personality("il-safety", steps=[320.0, 320.0, 160.0, 4.44, 4.44],
                        max_accel=[10000, 10000, 10000, 10000, 20000], b_current=540,
                        emergency_parser=True, host_watchdog=True, onoff_right_outputs=True,
                        homing_abort=True, outputs_off_on_kill=True)

PERSONALITIES = {"stock": STOCK, "il-safety": IL_SAFETY}


def get(name):
    try:
        return PERSONALITIES[name]
    except KeyError:
        raise ValueError("unknown personality %r (stock, il-safety)" % name) from None
