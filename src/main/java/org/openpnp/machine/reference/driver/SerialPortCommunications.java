package org.openpnp.machine.reference.driver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.TimeoutException;

import org.openpnp.instancelabs.serial.SerialPortMatch;
import org.openpnp.instancelabs.serial.SerialPortMatcher;
import org.pmw.tinylog.Logger;
import org.simpleframework.xml.Attribute;

import com.fazecast.jSerialComm.SerialPort;

/**
 * A class for SerialPort Communications. Includes functions for connecting,
 * disconnecting, reading and sending lines.
 */
public class SerialPortCommunications extends ReferenceDriverCommunications {
    public enum DataBits {
        Five(5),
        Six(6),
        Seven(7),
        Eight(8);

        public final int mask;

        private DataBits(int mask) {
            this.mask = mask;
        }
    }

    public enum StopBits {
        One(SerialPort.ONE_STOP_BIT),
        OnePointFive(SerialPort.ONE_POINT_FIVE_STOP_BITS),
        Two(SerialPort.TWO_STOP_BITS);

        public final int mask;

        private StopBits(int mask) {
            this.mask = mask;
        }
    }

    public enum FlowControl {
        Off(SerialPort.FLOW_CONTROL_DISABLED),
        RtsCts(SerialPort.FLOW_CONTROL_CTS_ENABLED | SerialPort.FLOW_CONTROL_RTS_ENABLED),
        XonXoff(SerialPort.FLOW_CONTROL_XONXOFF_IN_ENABLED | SerialPort.FLOW_CONTROL_XONXOFF_OUT_ENABLED);

        public final int mask;

        private FlowControl(int mask) {
            this.mask = mask;
        }
    }

    public enum Parity {
        None(SerialPort.NO_PARITY),
        Mark(SerialPort.MARK_PARITY),
        Space(SerialPort.SPACE_PARITY),
        Even(SerialPort.EVEN_PARITY),
        Odd(SerialPort.ODD_PARITY);

        public final int mask;

        private Parity(int mask) {
            this.mask = mask;
        }
    }

    @Attribute(required = false)
    protected String portName = "";

    @Attribute(required = false)
    protected int baud = 115200;

    @Attribute(required = false)
    protected FlowControl flowControl = FlowControl.Off;

    @Attribute(required = false)
    protected DataBits dataBits = DataBits.Eight;

    @Attribute(required = false)
    protected StopBits stopBits = StopBits.One;

    @Attribute(required = false)
    protected Parity parity = Parity.None;

    @Attribute(required = false)
    protected boolean setDtr = false;

    @Attribute(required = false)
    protected boolean setRts = false;

    @Attribute(required = false)
    protected String name = "SerialPortCommunications";

    /**
     * Instance Labs serial auto-detect: optional USB identity, tried before portName. Null when
     * unused so that existing machine.xml files are read and written unchanged.
     * See org.openpnp.instancelabs.serial.SerialPortMatcher.
     */
    @Attribute(required = false)
    protected String usbSerialNumber;

    @Attribute(required = false)
    protected String usbDescription;

    private SerialPortMatcher portMatcher = SerialPortMatcher.createDefault();

    private volatile SerialPortMatch lastMatch;

    /**
     * Instance Labs (BR-93): set as the JVM shuts down, by a hook jSerialComm runs before it closes every port: a read
     * that then fails on a link kept open across a STOP is OpenPnP's exit, not a lost link.
     */
    static volatile boolean jvmShuttingDown;
    private static final java.util.concurrent.atomic.AtomicBoolean shutdownFlagHooked =
            new java.util.concurrent.atomic.AtomicBoolean();
    /** Instance Labs (BR-47): the device the last connect opened, links resolved; null before one. */
    private volatile String openedRealPath;

    private SerialPort serialPort;

    @Override
    public synchronized void connect() throws Exception {
        disconnect();
        if (shutdownFlagHooked.compareAndSet(false, true)) {
            SerialPort.addShutdownHook(new Thread(() -> jvmShuttingDown = true, "serial-exit-flag")); // BR-93
        }
        SerialPortMatch match = resolvePort();
        serialPort = SerialPort.getCommPort(match.getPortDescriptor());
        serialPort.flushIOBuffers();
        if (!serialPort.openPort(0)) {
            throw new IOException("Could not open serial port " + match.getSummary()
                    + " (error code " + serialPort.getLastErrorCode() + ").");
        }
        openedRealPath = realPath(serialPort.getSystemPortPath());
        serialPort.setComPortParameters(baud, dataBits.mask, stopBits.mask, parity.mask);
        serialPort.setFlowControl(flowControl.mask);
        if (setDtr) {
            serialPort.setDTR();
        }
        if (setRts) {
            serialPort.setRTS();
        }
        serialPort.setComPortTimeouts(
                SerialPort.TIMEOUT_READ_SEMI_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING, 0, 0);
    }

    @Override
    public synchronized void disconnect() throws Exception {
        if (serialPort != null && serialPort.isOpen()) {
            serialPort.closePort();
            serialPort = null;
        }
    }


    /**
     * Resolve the port to open from the USB identity / port name, and log the result. Does not
     * open the port.
     */
    public SerialPortMatch resolvePort() throws IOException {
        SerialPortMatch match = portMatcher.resolve(usbSerialNumber, usbDescription, portName);
        for (String warning : match.getWarnings()) {
            Logger.warn("Serial port: " + warning);
        }
        Logger.info("Serial port: connecting to " + match.getSummary());
        lastMatch = match;
        return match;
    }

    /**
     * @return the result of the last connect(), or null if not connected since startup.
     */
    public SerialPortMatch getLastMatch() {
        return lastMatch;
    }

    /**
     * Instance Labs (BR-47): the device the last connect opened, links resolved (jSerialComm may open another name
     * than the configured one); null before a connect.
     */
    public String getOpenedRealPath() {
        return openedRealPath;
    }

    private static String realPath(String path) {
        try {
            return path == null ? null : java.nio.file.Paths.get(path).toRealPath().toString();
        }
        catch (Exception e) {
            return path;
        }
    }

    public SerialPortMatcher getPortMatcher() {
        return portMatcher;
    }

    public void setPortMatcher(SerialPortMatcher portMatcher) {
        this.portMatcher = portMatcher;
    }

    public String getUsbSerialNumber() {
        return usbSerialNumber;
    }

    public void setUsbSerialNumber(String usbSerialNumber) {
        this.usbSerialNumber = blankToNull(usbSerialNumber);
    }

    public String getUsbDescription() {
        return usbDescription;
    }

    public void setUsbDescription(String usbDescription) {
        this.usbDescription = blankToNull(usbDescription);
    }

    private static String blankToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    /**
     * Returns an array of Strings containing the names of serial ports
     * present on the system
     * 
     * @return array of Strings of serial port names
     */
    public static String[] getPortNames() {
        SerialPort[] ports = SerialPort.getCommPorts();
        ArrayList<String> portNames = new ArrayList<>();
        for (SerialPort port : ports) {
            portNames.add(port.getSystemPortName());
        }
        return portNames.toArray(new String[] {});
    }

    @Override
    public int read() throws TimeoutException, IOException {
        byte[] b = new byte[1];
        int l;
        try {
            l = serialPort.readBytes(b, 1);
        }
        catch (NullPointerException e) {
            throw new IOException("Trying to read from a unconnected serial.");
        }
        if (l == -1) {
            throw new IOException("Read error.");
        }
        if (l == 0) {
            throw new TimeoutException("Read timeout.");
        }
        return b[0];
    }

    @Override
    public void writeBytes(byte[] data) throws IOException {
        int l = serialPort.writeBytes(data, data.length);
        if (l == -1) {
            throw new IOException("Write error.");
        }
    }


    @Override
    public String getConnectionName() {
        return (driverName != null ? driverName +":" : "") + portName;
    }

    public String getPortName() {
        return portName;
    }

    public void setPortName(String portName) {
        this.portName = portName;
    }

    public int getBaud() {
        return baud;
    }

    public void setBaud(int baud) {
        this.baud = baud;
    }

    public FlowControl getFlowControl() {
        return flowControl;
    }

    public void setFlowControl(FlowControl flowControl) {
        this.flowControl = flowControl;
    }

    public DataBits getDataBits() {
        return dataBits;
    }

    public void setDataBits(DataBits dataBits) {
        this.dataBits = dataBits;
    }

    public StopBits getStopBits() {
        return stopBits;
    }

    public void setStopBits(StopBits stopBits) {
        this.stopBits = stopBits;
    }

    public Parity getParity() {
        return parity;
    }

    public void setParity(Parity parity) {
        this.parity = parity;
    }

    public boolean isSetDtr() {
        return setDtr;
    }

    public void setSetDtr(boolean setDtr) {
        this.setDtr = setDtr;
    }

    public boolean isSetRts() {
        return setRts;
    }

    public void setSetRts(boolean setRts) {
        this.setRts = setRts;
    }
}

