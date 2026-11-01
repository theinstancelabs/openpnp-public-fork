#!/usr/bin/env python3
"""Build the plan §10 config change set as a modified COPY of machine.xml (never edits the input).

    make_machine_patch.py --in ~/.openpnp2/machine.xml --out machine.xml.patched [--diff machine.xml.diff]
                          [--stock-compatible]

Items (plan docs/plan-2026-10-27-evidence-backed-roadmap.md §10):
  1  serial: port-name = /dev/serial/by-id/... (stable path) + usb-serial-number / usb-description
     (feat/serial-autodetect: match by USB serial, then description, then the port name)
  2  VAC1 / VAC2: disabled-actuation="ActuateOff"
  3  DISABLE_COMMAND: M410, M107 P3, M107 P2, M107 P1, M107 P0, M18 B   (§4.4)
  4  HOME_COMMAND: existing lines + G92 A0 B0 after G28                  (§4.3)
  6  N2: no compatible nozzle tips and no loaded tip (checked; already true in the live file)
  7  CONNECT_COMMAND: + M906 B580 (auger motor current, §4.2 / §10 item 7). The plan proposed 540;
     the bench (tools/disp.py) ran every 20000 deg/s^2 screw test at 580 (about 0.54 A real with the
     x0.93 sense-resistor factor, ~80 % of the 0.67 A rating). An earlier M906 B line of this tool
     (B540) is replaced; any other M906 B stops the tool.
  8  VAC2: valve M106 P3 S180 -> S255 (§4.2, approved), and the OFF parts swapped so that OFF
     sends the valve first (§4.5): {True:M106 P2 S255}{False:M107 P3}; {True:M106 P3 S255}{False:M107 P2}
     ON stays pump -> valve. On Stop OpenPnP fires the actuators' ActuateOff before the driver's
     DISABLE_COMMAND, and the listener order is not fixed (review finding: pump OFF went out first).
 10  N2 head-offsets = (25.291, -75.926, 0, 0): the bench needle offset (tools/disp.py OFF, needle =
     top camera + OFF). Checked: the top camera's head-offsets are 0, 0.
 11  Visual homing: head visual-homing-method="ResetToFiducialLocation" (checked) and the head's
     homing-fiducial-location x/y = (217.081, 200.223), the bench frame (G28, centre the top camera on
     the homing fiducial, G92 X217.081 Y200.223); z and rotation kept.

Edits are textual and anchored: every anchor must match exactly once, otherwise the tool stops
without writing anything. The output keeps the input's formatting so the diff shows only the
changes. Re-running on an already patched file changes nothing. Python 3 standard library only.
"""
import argparse
import difflib
import re
import sys
import xml.etree.ElementTree as ET

BY_ID = "/dev/serial/by-id/usb-STMicroelectronics_MARLIN_OPULO_LUMEN_REV5_CDC_in_FS_Mode_387932683335-if00"
USB_SERIAL = "387932683335"
USB_DESCRIPTION = "MARLIN_OPULO_LUMEN_REV5"
DISABLE_LINES = ["M410", "M107 P3", "M107 P2", "M107 P1", "M107 P0", "M18 B"]
HOME_EXTRA = "G92 A0 B0 ; OpenPnP assumes A=B=0 after homing; Marlin boots at A=B=720 (plan 4.3)"
CONNECT_EXTRA = "M906 B580 ; auger motor (J/B) current, mA, as the bench ran it (plan 10 item 7)"
# Line 1 ON = pump, line 2 ON = valve (plan 4.5 ON order); line 1 OFF = valve, line 2 OFF = pump.
VAC2_LINES = ["<text><![CDATA[{True:M106 P2 S255}{False:M107 P3};]]></text>",
              "<text><![CDATA[{True:M106 P3 S255}{False:M107 P2};]]></text>"]
N2_OFFSET = ("25.291", "-75.926")         # needle = top camera + OFF (tools/disp.py, p18_board1)
HOMING_FIDUCIAL = ("217.081", "200.223")  # bench frame: G92 X217.081 Y200.223 over the homing fiducial


class PatchError(Exception):
    pass


def attr(el, name):
    m = re.search(r'\s%s="([^"]*)"' % re.escape(name), el)
    if not m:
        raise PatchError("attribute %s missing in %s" % (name, el[:80]))
    return m.group(1)


def set_attr(el, name, value):
    attr(el, name)
    return re.sub(r'(\s%s=")[^"]*(")' % re.escape(name), lambda m: m.group(1) + value + m.group(2), el, count=1)


def sub_once(text, pattern, repl, what, flags=0):
    n = len(re.findall(pattern, text, flags))
    if n != 1:
        raise PatchError("%s: expected exactly 1 match, found %d (pattern %r)" % (what, n, pattern))
    return re.sub(pattern, repl, text, count=1, flags=flags)


def patch(src, stock_compatible=False):
    out = src
    notes = []

    # 1. serial port: stable by-id path + USB identity for auto-detect. Stock OpenPnP (2.6 in
    #    /opt/openpnp, 2.7 without feat/serial-autodetect) refuses unknown attributes, so the
    #    stock-compatible variant keeps only the by-id path.
    def serial_repl(m):
        el = m.group(0)
        el = re.sub(r'port-name="[^"]*"', 'port-name="%s"' % BY_ID, el, count=1)
        if stock_compatible:
            return re.sub(r' usb-(serial-number|description)="[^"]*"', "", el)
        for attr, val in (("usb-serial-number", USB_SERIAL), ("usb-description", USB_DESCRIPTION)):
            if re.search(r'\b%s="' % attr, el):
                el = re.sub(r'\b%s="[^"]*"' % attr, '%s="%s"' % (attr, val), el, count=1)
            else:
                el = el.replace(' name="SerialPortCommunications"',
                                ' %s="%s" name="SerialPortCommunications"' % (attr, val), 1)
        return el
    out = sub_once(out, r'<serial [^>]*/>', serial_repl, "1 serial element")
    if stock_compatible:
        notes.append("1 serial: port-name=%s (stock-compatible: no usb-* auto-detect attributes)" % BY_ID)
    else:
        notes.append("1 serial: port-name=%s usb-serial-number=%s usb-description=%s" % (BY_ID, USB_SERIAL, USB_DESCRIPTION))

    # 2. VAC1 / VAC2 disabled-actuation.
    for name in ("VAC1", "VAC2"):
        pat = r'(<actuator [^>]*\bname="%s"[^>]*\bdisabled-actuation=")[A-Za-z]+(")' % name
        out = sub_once(out, pat, r"\g<1>ActuateOff\g<2>", "2 %s disabled-actuation" % name)
    notes.append("2 VAC1, VAC2: disabled-actuation=ActuateOff")

    # 3. DISABLE_COMMAND (inserted after CONNECT_COMMAND; replaced if one exists).
    texts = "".join("               <text><![CDATA[%s]]></text>\n" % l for l in DISABLE_LINES)
    block = '            <command type="DISABLE_COMMAND">\n%s            </command>\n' % texts
    if re.search(r'<command type="DISABLE_COMMAND">', out):
        out = sub_once(out, r'            <command type="DISABLE_COMMAND">\n.*?            </command>\n',
                       lambda m: block, "3 DISABLE_COMMAND", re.S)
    else:
        out = sub_once(out, r'(            <command type="CONNECT_COMMAND">\n.*?            </command>\n)',
                       lambda m: m.group(1) + block, "3 CONNECT_COMMAND anchor", re.S)
    notes.append("3 DISABLE_COMMAND: " + ", ".join(DISABLE_LINES))

    # 4. HOME_COMMAND: G92 A0 B0 right after the G28 line.
    def home_repl(m):
        body = m.group(0)
        if "G92 A0 B0" in body:
            return body
        g28 = re.search(r'( *)<text><!\[CDATA\[G28[^\]]*\]\]></text>\n', body)
        if not g28:
            raise PatchError("4 HOME_COMMAND has no G28 line")
        ins = '%s<text><![CDATA[%s]]></text>\n' % (g28.group(1), HOME_EXTRA)
        return body[:g28.end()] + ins + body[g28.end():]
    out = sub_once(out, r'<command type="HOME_COMMAND">\n.*?</command>\n', home_repl, "4 HOME_COMMAND", re.S)
    notes.append("4 HOME_COMMAND: + " + HOME_EXTRA)

    # 6. N2: no compatible nozzle tips, no loaded tip (check; fix if needed).
    m = re.search(r'(<nozzle [^>]*\bname="N2"[^>]*>)\n(\s*)<compatible-nozzle-tip-ids class="java.util.ArrayList"(/>|>.*?</compatible-nozzle-tip-ids>)',
                  out, re.S)
    if not m:
        raise PatchError("6 N2 nozzle / compatible-nozzle-tip-ids not found")
    n2_open = m.group(1)
    if m.group(3) != "/>" or "current-nozzle-tip-id=" in n2_open:
        new_open = re.sub(r' current-nozzle-tip-id="[^"]*"', "", n2_open)
        out = out.replace(m.group(0), '%s\n%s<compatible-nozzle-tip-ids class="java.util.ArrayList"/>'
                          % (new_open, m.group(2)), 1)
        notes.append("6 N2: compatible nozzle tips and loaded tip removed")
    else:
        notes.append("6 N2: already has no compatible nozzle tips and no loaded tip (no change)")

    # 7. CONNECT_COMMAND: M906 B580 as the last line (replaces an earlier M906 B line of this tool).
    line = '<text><![CDATA[%s]]></text>' % CONNECT_EXTRA
    had = []

    def connect_repl(m):
        body = m.group(0)
        own = re.findall(r'<text><!\[CDATA\[(M906 B\d+) ; auger motor \(J/B\) current[^\]]*\]\]></text>', body)
        if len(own) == 1:
            had.append(own[0])
            return re.sub(r'<text><!\[CDATA\[M906 B\d+ ; auger motor \(J/B\) current[^\]]*\]\]></text>',
                          lambda _m: line, body, count=1)
        if "M906 B" in body:
            raise PatchError("7 CONNECT_COMMAND already sets M906 B; check by hand")
        last = body.rfind("            </command>")
        indent = re.findall(r'\n( *)<text>', body)[-1]
        return body[:last] + '%s%s\n' % (indent, line) + body[last:]
    already = line in out
    out = sub_once(out, r'<command type="CONNECT_COMMAND">\n.*?            </command>', connect_repl,
                   "7 CONNECT_COMMAND", re.S)
    notes.append("7 CONNECT_COMMAND: + " + CONNECT_EXTRA
                 + (" (already there, no change)" if already else " (replaces %s)" % had[0] if had else ""))

    # 8. VAC2: valve S180 -> S255, and OFF = valve first (both lines of the boolean command, exact).
    vac2 = re.search(r'<actuator [^>]*\bid="([^"]+)"[^>]*\bname="VAC2"', out)
    if not vac2:
        raise PatchError("8 VAC2 actuator id not found")
    cmd = r'<command head-mountable-id="%s" type="ACTUATE_BOOLEAN_COMMAND">\n' % re.escape(vac2.group(1))
    if re.search(cmd + r' *' + re.escape(VAC2_LINES[0]) + r'\n *' + re.escape(VAC2_LINES[1]) + r'\n', out):
        notes.append("8 VAC2 (%s): already valve S255, OFF valve first (no change)" % vac2.group(1))
    else:
        pat = ('(' + cmd + r'( *))'
               r'<text><!\[CDATA\[\{True:M106 P2 S255\}\{False:M107 P2\};\]\]></text>\n *'
               r'<text><!\[CDATA\[\{True:M106 P3 S180\}\{False:M107 P3\};\]\]></text>\n')
        out = sub_once(out, pat, lambda m: m.group(1) + VAC2_LINES[0] + "\n" + m.group(2) + VAC2_LINES[1] + "\n",
                       "8 VAC2 boolean command")
        notes.append("8 VAC2 (%s): valve M106 P3 S180 -> S255; OFF order valve (M107 P3) then pump (M107 P2)"
                     % vac2.group(1))

    # 10. N2 head-offsets = the bench needle offset, which is relative to the top camera: check the
    #     camera sits at the head origin.
    cam = re.findall(r'<camera [^>]*\bname="Top"[^>]*>.*?</camera>', out, re.S)
    offs = re.findall(r'<head-offsets [^>]*/>', cam[0]) if len(cam) == 1 else []
    if len(offs) != 1:
        raise PatchError("10 top camera head-offsets not found exactly once")
    if any(float(attr(offs[0], k)) != 0.0 for k in ("x", "y")):
        raise PatchError("10 top camera head-offsets are (%s, %s), not 0, 0; the needle offset is camera-relative"
                         % (attr(offs[0], "x"), attr(offs[0], "y")))
    old_n2 = []

    def n2_repl(m):
        el = m.group(2)
        old_n2.append("(%s, %s, %s, %s)" % tuple(attr(el, k) for k in ("x", "y", "z", "rotation")))
        for k, v in (("x", N2_OFFSET[0]), ("y", N2_OFFSET[1]), ("z", "0.0"), ("rotation", "0.0")):
            el = set_attr(el, k, v)
        return m.group(1) + el
    out = sub_once(out, r'(<nozzle [^>]*\bname="N2"[^>]*>(?:(?!</nozzle>).)*?)(<head-offsets [^>]*/>)', n2_repl,
                   "10 N2 head-offsets", re.S)
    notes.append("10 N2 head-offsets %s -> (%s, %s, 0.0, 0.0); top camera head-offsets 0, 0"
                 % ((old_n2[0],) + N2_OFFSET))

    # 11. Visual homing to the bench frame's homing fiducial (the head's element, not the driver's).
    head = re.findall(r'<head [^>]*>', out)
    if len(head) != 1 or attr(head[0], "visual-homing-method") != "ResetToFiducialLocation":
        raise PatchError("11 expected one head with visual-homing-method=\"ResetToFiducialLocation\", found %s"
                         % [attr(h, "visual-homing-method") for h in head])
    old_fid = []

    def fid_repl(m):
        el = m.group(2)
        old_fid.append("(%s, %s)" % (attr(el, "x"), attr(el, "y")))
        return m.group(1) + set_attr(set_attr(el, "x", HOMING_FIDUCIAL[0]), "y", HOMING_FIDUCIAL[1])
    out = sub_once(out, r'(<head [^>]*>(?:(?!</head>).)*?)(<homing-fiducial-location [^>]*/>)', fid_repl,
                   "11 head homing-fiducial-location", re.S)
    notes.append("11 head visual homing ResetToFiducialLocation; homing-fiducial-location %s -> (%s, %s)"
                 % ((old_fid[0],) + HOMING_FIDUCIAL))

    ET.fromstring(out.encode("utf-8"))  # still well-formed XML
    return out, notes


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--in", dest="src", required=True, help="machine.xml to read (never written)")
    ap.add_argument("--out", required=True, help="patched copy to write")
    ap.add_argument("--diff", help="write a unified diff here")
    ap.add_argument("--stock-compatible", action="store_true",
                    help="omit the usb-serial-number / usb-description attributes (only the fork reads them)")
    a = ap.parse_args(argv)
    if a.out == a.src:
        raise SystemExit("refusing to overwrite the input")
    with open(a.src, encoding="utf-8") as f:
        src = f.read()
    try:
        out, notes = patch(src, a.stock_compatible)
    except PatchError as e:
        raise SystemExit("not patched: %s" % e)
    with open(a.out, "w", encoding="utf-8") as f:
        f.write(out)
    if a.diff:
        d = difflib.unified_diff(src.splitlines(True), out.splitlines(True), fromfile="a/machine.xml",
                                 tofile="b/machine.xml")
        with open(a.diff, "w", encoding="utf-8") as f:
            f.writelines(d)
    for n in notes:
        print(n)


if __name__ == "__main__":
    sys.exit(main())
