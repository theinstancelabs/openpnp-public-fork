package org.openpnp.instancelabs.dispense;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openpnp.instancelabs.sim.SimPaste;
import org.openpnp.instancelabs.sim.SimVacuum;

/**
 * Minimal Marlin-like TCP server on localhost for tests: records every received line, tracks
 * X/Y/Z/A/B from G0/G1/G92, answers M114 with the tracked position (and, like Marlin, the B step
 * count) and M115 with an optional Cap:HOST_WATCHDOG:1, M261 from an optional simulated vacuum
 * sensor, and "ok" to everything. Never touches any device.
 *
 * <p>B step count as Marlin keeps it: the machine B position is the logical one plus a workspace
 * shift; G92 B sets the logical position and the shift so that the machine position (and the step
 * count) does not change; G0/G1 B moves the machine position; only a power-up (here
 * {@link #setBStepCount}) resets it. M114 reports {@code Count ... B:<round(machine x 4.44)>}.</p>
 *
 * <p>With a {@link #paste} model (whose scene has a paste block) the screw is the model's: the count
 * is its count, reported only when the scene's {@code reportCount} says so, and every new
 * connection is a controller power-up ({@link SimPaste#powerCycle}).</p>
 */
public class RecordingGcodeServer implements AutoCloseable {
    private final ServerSocket server;
    private final List<String> lines = new ArrayList<>();
    private final List<Long> times = new ArrayList<>();
    /** Lines (exact text) that get no reply at all, e.g. to make a send time out. */
    public final java.util.Set<String> mute = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** If true, "G4 P<ms>" waits <ms> before its "ok", as Marlin does. */
    public volatile boolean realDwell = false;
    private final Map<Character, Double> pos = new LinkedHashMap<>();
    public volatile boolean hostWatchdogCapability = true;
    /**
     * If true, M114 reports the B step count after "Count" (Marlin with the B axis); if false the
     * count part has X/Y/Z only, as OpenPnP's simulator (the engine then takes the count as 0).
     */
    public volatile boolean reportBCount = true;
    /** The simulated N1 vacuum sensor, fed every line with the tracked X/Y/Z; null = no sensor (plain "ok"). */
    public volatile SimVacuum vacuum;
    /** The SIM needle paste, fed every line with the tracked X/Y and the needle's Z; null = none. */
    public volatile SimPaste paste;
    /** The needle (N2) Z of a raw Z: the DispenseSimMachine's see-saw (z2 = 63 − z1). */
    public volatile DoubleUnaryOperator pasteNeedleZ = (z) -> 63 - z;
    /** The M115 firmware name; "GcodeServer (RecordingGcodeServer)" makes SimGuard take this for the simulator. */
    public volatile String firmwareName = "Marlin (RecordingGcodeServer)";
    /** Wait before the "ok" of lines starting with a prefix, e.g. "G28" -> 3000 (a slow home). */
    public final Map<String, Long> delayBeforeOkMs = new java.util.concurrent.ConcurrentHashMap<>();
    /** Machine B minus logical B (deg): the G92 workspace shift. */
    private double bShift = 0;
    /** If >= 0, reply "Error: simulated" to the n-th received line (0-based) after mark(). */
    public volatile int failAtLine = -1;
    private volatile int markIndex = 0;
    private final Thread thread;
    private static final Pattern WORD = Pattern.compile("([XYZAB])(-?\\d+(?:\\.\\d+)?)");
    /** Marlin M92 B (steps per degree), for the reported step count. */
    public static final double B_STEPS_PER_DEG = 4.44;

    public RecordingGcodeServer() throws Exception {
        server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        for (char c : "XYZAB".toCharArray()) {
            pos.put(c, 0.0);
        }
        thread = new Thread(this::run, "RecordingGcodeServer");
        thread.setDaemon(true);
        thread.start();
    }

    public int getPort() {
        return server.getLocalPort();
    }

    private void run() {
        while (!server.isClosed()) {
            try (Socket s = server.accept()) {
                SimPaste connected = paste;
                if (connected != null) {
                    connected.powerCycle(); // a reconnect is a controller power-up
                }
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                OutputStream out = s.getOutputStream();
                String line;
                while ((line = in.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    int idx;
                    double x, y, z;
                    synchronized (this) {
                        lines.add(line);
                        times.add(System.nanoTime());
                        idx = lines.size() - 1 - markIndex;
                        track(line);
                        x = pos.get('X');
                        y = pos.get('Y');
                        z = pos.get('Z');
                    }
                    StringBuilder reply = new StringBuilder();
                    if (failAtLine >= 0 && idx == failAtLine) {
                        reply.append("Error: simulated failure\n");
                    }
                    SimPaste p = paste;
                    if (p != null) {
                        p.onLine(line, x, y, pasteNeedleZ.applyAsDouble(z));
                    }
                    SimVacuum v = vacuum;
                    String sensed = v == null ? null : v.onLine(line, x, y, z);
                    if (sensed != null) {
                        reply.append(sensed).append('\n');
                    }
                    if (line.startsWith("M115")) {
                        reply.append("FIRMWARE_NAME:").append(firmwareName).append('\n');
                        if (hostWatchdogCapability) {
                            reply.append("Cap:HOST_WATCHDOG:1\n");
                        }
                    }
                    else if (line.startsWith("M114")) {
                        synchronized (this) {
                            boolean model = p != null && p.isActive();
                            reply.append(String.format(Locale.ROOT, "X:%.4f Y:%.4f Z:%.4f A:%.4f B:%.4f Count X:0 Y:0 Z:0%s\n",
                                    pos.get('X'), pos.get('Y'), pos.get('Z'), pos.get('A'), pos.get('B'),
                                    reportBCount && (!model || p.reportsCount()) ? " B:" + bStepCount() : ""));
                        }
                    }
                    if (mute.contains(line)) {
                        continue;
                    }
                    if (realDwell && line.startsWith("G4 P")) {
                        Thread.sleep(Long.parseLong(line.substring(4).trim()));
                    }
                    for (Map.Entry<String, Long> d : delayBeforeOkMs.entrySet()) {
                        if (line.startsWith(d.getKey())) {
                            Thread.sleep(d.getValue());
                        }
                    }
                    reply.append("ok\n");
                    out.write(reply.toString().getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
            catch (Exception e) {
                // socket closed
            }
        }
    }

    private void track(String line) {
        String code = line.split("[\\s;]")[0];
        if (code.equals("G0") || code.equals("G1") || code.equals("G92")) {
            String body = line.contains(";") ? line.substring(0, line.indexOf(';')) : line;
            Matcher m = WORD.matcher(body.substring(code.length()));
            while (m.find()) {
                char axis = m.group(1).charAt(0);
                double v = Double.parseDouble(m.group(2));
                if (axis == 'B' && code.equals("G92")) {
                    bShift += pos.get('B') - v; // workspace offset: the machine position stays
                }
                pos.put(axis, v);
            }
        }
        else if (code.equals("G28")) {
            // like the LumenPnP: homes X/Y/Z only (Z home 31.5 in the test config is set by OpenPnP)
            pos.put('X', 0.0);
            pos.put('Y', 0.0);
            pos.put('Z', 31.5);
        }
    }

    public synchronized void mark() {
        markIndex = lines.size();
    }

    /** Lines received since the last mark(). */
    public synchronized List<String> sinceMark() {
        return new ArrayList<>(lines.subList(markIndex, lines.size()));
    }

    /** Receive time (System.nanoTime()) of the i-th line since the last mark(). */
    public synchronized long timeSinceMark(int i) {
        return times.get(markIndex + i);
    }

    public synchronized List<String> all() {
        return new ArrayList<>(lines);
    }

    public synchronized double position(char axis) {
        return pos.get(axis);
    }

    /** The Marlin step count of B (machine position x steps/deg), as M114 reports it; the paste model's if any. */
    public synchronized long bStepCount() {
        SimPaste p = paste;
        if (p != null && p.isActive()) {
            return p.count();
        }
        return Math.round((pos.get('B') + bShift) * B_STEPS_PER_DEG);
    }

    /**
     * Sets the B step count (the screw history since the controller's power-up) without changing
     * the logical B position (also the paste model's).
     */
    public synchronized void setBStepCount(long steps) {
        bShift = steps / B_STEPS_PER_DEG - pos.get('B');
        SimPaste p = paste;
        if (p != null) {
            p.setCount(steps);
        }
    }

    @Override
    public void close() throws Exception {
        server.close();
    }
}
