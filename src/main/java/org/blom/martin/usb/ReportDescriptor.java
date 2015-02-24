package org.blom.martin.usb;

import java.io.*;
import java.util.*;

public class ReportDescriptor {
    public Map<Integer, Report> reports = new TreeMap<Integer, Report>();

    public ReportDescriptor(byte[] bytes)
        throws IOException {
        this(new ByteArrayInputStream(bytes));
    }

    public ReportDescriptor(InputStream is)
        throws IOException {
        int collectionCounter = 0;
        Deque<GlobalState> ss = new ArrayDeque<GlobalState>();
        Collection cc = null;
        LocalState ls = new LocalState();
        GlobalState gs = new GlobalState();

        int lastTag = -1;
        for (Item item = Item.read(is); item != null; item = Item.read(is)) {
            boolean repeat = item.tag == lastTag;

            switch (item.type) {
                case MAIN:
                    switch (item.tag) {
                        case  8: addControl(gs.reportID, new Control(cc, gs, ls, Control.Type.INPUT,   item.unsigned())); break;
                        case  9: addControl(gs.reportID, new Control(cc, gs, ls, Control.Type.OUTPUT,  item.unsigned())); break;
                        case 11: addControl(gs.reportID, new Control(cc, gs, ls, Control.Type.FEATURE, item.unsigned())); break;

                        case 10:
                            cc = new Collection(++collectionCounter, cc, gs, ls, item.unsigned());
                            break;

                        case 12:
                            if (cc == null) {
                                throw new IllegalArgumentException(item + " outside Collection");
                            }

                            cc = cc.parent;
                            break;

                        default:
                            throw new IllegalArgumentException("Unsupported global tag in " + item);
                    }

                    ls = new LocalState();
                    break;

                case GLOBAL:
                    switch (item.tag) {
                        case  0: gs.usagePage       = item.unsigned(); break;
                        case  1: gs.logicalMinimum  = item.value;      break;
                        case  2: gs.logicalMaximum  = item.value;      break;
                        case  3: gs.physicalMinimum = item.value;      break;
                        case  4: gs.physicalMaximum = item.value;      break;
                        case  5: gs.unitExponent    = item.value;      break;
                        case  6: gs.unit            = item.value;      break;
                        case  7: gs.reportSize      = item.unsigned(); break;
                        case  8: gs.reportID        = item.unsigned(); break;
                        case  9: gs.reportCount     = item.unsigned(); break;
                        case 10: ss.push(gs.clone());                  break;
                        case 11: gs = ss.pop();                        break;
                        default: throw new IllegalArgumentException("Unsupported global tag in " + item);
                    }
                    break;

                case LOCAL: {
                    int usage = item.length > 2 ? item.value : gs.usagePage << 16 | item.unsigned();

                    switch (item.tag) {
                        case  0: if (repeat) ls.usage.add(usage);                else ls.usage.set(usage);                break;
                        case  3: if (repeat) ls.designator.add(item.unsigned()); else ls.designator.set(item.unsigned()); break;
                        case  7: if (repeat) ls.string.add(item.unsigned());     else ls.string.set(item.unsigned());     break;

                        case  1: ls.usage.min       = usage;           break;
                        case  2: ls.usage.max       = usage;           break;
                        case  4: ls.designator.min  = item.unsigned(); break;
                        case  5: ls.designator.max  = item.unsigned(); break;
                        case  8: ls.string.min      = item.unsigned(); break;
                        case  9: ls.string.max      = item.unsigned(); break;

                        case 10: // Delimiter
                            ls.delimiter(item);
                            break;

                        default:
                            throw new IllegalArgumentException("Unsupported local tag in " + item);
                    }
                    break;
                }

                case RESERVED:
                    throw new IllegalArgumentException(item + " not supported");
            }

            lastTag = item.tag;
        }
    }

    public java.util.Collection<Report> reports() {
        return reports.values();
    }

    public Set<Integer> usageSet(Control.Type type) {
        Set<Integer> result = new TreeSet<Integer>();

        for (Report report : reports()) {
            if (report.types.contains(type)) {
                for (Control control : report.controls) {
                    for (Collection collection = control.parent; collection != null; collection = collection.parent) {
                        for (int usage : collection.usages) {
                            result.add(usage);
                        }
                    }
                }
            }
        }

        return result;
    }

    public ReportDescriptor evaluate(int report, Control.Type type, Evaluator cb) {
        if (!reports.containsKey(report)) {
            throw new NoSuchElementException("No report with ID " + report);
        }

        Map<Collection, Boolean> knownCollections = new IdentityHashMap<Collection, Boolean>();
        int offset = 0;

        for (Control control : reports.get(report).controls) {
            LocalState ls = control.ls;

            if (control.type == type) {
                if (knownCollection(control.parent, cb, knownCollections) && cb.control(control)) {
                    for (int i = 0; i < control.reportCount; ++i) {
                        if (control.flags.contains(Control.Flag.CONSTANT)) {
                            cb.constant(control, offset + i * control.reportSize);
                        }
                        else if (control.flags.contains(Control.Flag.VARIABLE)) {
                            cb.variable(control, ls.usagesForIndex(i), offset + i * control.reportSize);
                        }
                        else if (control.flags.contains(Control.Flag.ARRAY)) {
                            assert control.ls.usages.isEmpty();

                            cb.array(control, ls.usage.min, ls.usage.max, offset + i * control.reportSize);
                        }
                    }
                }

                offset += control.reportSize * control.reportCount;
            }
        }

        return this;
    }

    public static int peek(byte[] buffer, int offset, int length, boolean signed) {
        if (length > 32) {
            throw new IllegalArgumentException("Invalid length: " + length);
        }

        int res = 0;

        for (int i = 0; i < length; ++i) {
            if ((buffer[(offset + i) / 8] & (1 << (i & 7))) != 0) {
                res |= 1 << i;
            }
        }

        if (signed) {
            res = res << (32 - length) >> (32 - length);
        }

        return res;
    }

    public static void poke(byte[] buffer, int offset, int length, int value) {
        if (length > 32) {
            throw new IllegalArgumentException("Invalid length: " + length);
        }

        for (int i = 0; i < length; ++i) {
            if ((value & (1 << i)) != 0) {
                buffer[(offset + length) / 8] |= (1 << (i & 7));
            }
            else {
                buffer[(offset + length) / 8] &= ~(1 << (i & 7));
            }
        }
    }

    public static String toHexString(byte[] data) {
        String hex = "0123456789ABCDEF";
        char[] res = new char[data.length * 2];

        for (int i = 0; i < data.length; ++i) {
            res[i * 2 + 0] = hex.charAt(data[i] >> 4 & 0x0f);
            res[i * 2 + 1] = hex.charAt(data[i] >> 0 & 0xf);
        }

        return new String(res);
    }

    public static byte[] fromHexString(String hex) {
        byte[] data = new byte[hex.length() / 2];

        for (int i = 0; i < data.length * 2; ++i) {
            char c = hex.charAt(i);
            data[i / 2] |= (c <= '9' ? c - '0' : c <= 'F' ? c + 10 - 'A' : c + 10 - 'a') << (4 - 4 * (i % 2));
        }

        return data;
    }

    public static String toHexString(int[] values) {
        StringBuilder sb = new StringBuilder().append('[');

        for (int value : values) {
            sb.append(String.format("%s%08x", sb.length() == 1 ? "" : ", ", value));
        }

        return sb.append(']').toString();
    }

    private void addControl(int reportID, Control control) {
        Report report = reports.get(reportID);

        if (report == null) {
            report = new Report(reportID);
            reports.put(reportID, report);
        }

        report.types.add(control.type);
        report.controls.add(control);
    }

    private static boolean knownCollection(Collection collection, Evaluator cb, Map<Collection, Boolean> knownCollections) {
        Boolean known = collection.parent == null ? true : knownCollection(collection.parent, cb, knownCollections);

        if (known) {
            known = knownCollections.get(collection);

            if (known == null) {
                known = cb.collection(collection);
                knownCollections.put(collection, known);
            }
        }

        return known;
    }

    public interface Evaluator {
        public boolean collection(Collection collection);
        public boolean control(Control control);

        public void constant(Control control, int offset);
        public void array(Control control, int usageMinimum, int usageMaximum, int offset);
        public void variable(Control control, int[] usages, int offset);
    }

    public static class Item {
        public enum Type { MAIN, GLOBAL, LOCAL, RESERVED };

        public Type    type;
        public int     tag;
        public int     length;
        public int     value;
        public byte[]  longValue;

        public int unsigned() {
            return length == 1 ? value & 0xff : length == 2 ? value & 0xffff : value;
        }

        @Override public String toString() {
            return String.format("[Item type=%-8s %02x=%s length=%d]", type, tag,
                                 longValue != null ? toHexString(longValue) : String.format("%08x", value),
                                 length);
        }

        public static Item read(InputStream is)
            throws IOException {
            int b = is.read();

            if (b < 0) {
                return null;
            }

            Item item = new Item();

            switch (b & 0x0c) {
                case 0x00: item.type = Type.MAIN;     break;
                case 0x04: item.type = Type.GLOBAL;   break;
                case 0x08: item.type = Type.LOCAL;    break;
                case 0x0c: item.type = Type.RESERVED; break;
            }

            item.tag = (b >> 4) & 0x0f;

            if (item.tag == 0x0f && item.type == Type.RESERVED) {
                item.length = readByte(is);
                item.tag = readByte(is);
                item.longValue = new byte[item.length];

                for (int i = 0; i < item.longValue.length; ++i) {
                    item.longValue[i] = (byte) readByte(is);
                }
            }
            else {
                switch (b & 0x03) {
                    case 0x00: item.length = 0; break;
                    case 0x01: item.length = 1; item.value = (int) (byte) readByte(is); break;
                    case 0x02: item.length = 2; item.value = (int) (short) (readByte(is) | (readByte(is) << 8)); break;
                    case 0x03: item.length = 4; item.value = readByte(is) | (readByte(is) << 8) | (readByte(is) << 16) | (readByte(is) << 24); break;
                }
            }

            return item;
        }

        private static int readByte(InputStream is)
            throws IOException {
            int b = is.read();

            if (b < 0) {
                throw new EOFException("Unexpected end of stream");
            }

            return b;
        }
    }

    public static class GlobalState
        implements Cloneable {
        public int usagePage       = 0;
        public int logicalMinimum  = 0;
        public int logicalMaximum  = 0;
        public int physicalMinimum = 0;
        public int physicalMaximum = 0;
        public int unit            = 0;
        public int unitExponent    = 0;
        public int reportID        = 0;
        public int reportSize      = 0;
        public int reportCount     = 0;

        private GlobalState() {
        }

        public GlobalState assignFrom(GlobalState gs) {
            usagePage       = gs.usagePage;
            logicalMinimum  = gs.logicalMinimum;
            logicalMaximum  = gs.logicalMaximum;
            physicalMinimum = gs.physicalMinimum;
            physicalMaximum = gs.physicalMaximum;
            unit            = gs.unit;
            unitExponent    = gs.unitExponent;
            reportID        = gs.reportID;
            reportSize      = gs.reportSize;
            reportCount     = gs.reportCount;

            return this;
        }

        @Override public GlobalState clone() {
            return new GlobalState().assignFrom(this);
        }

        @Override public String toString() {
            return String.format("[GlobalState: usagePage=%04x logicalMinimum=%d logicalMaximum=%d physicalMinimum=%d physicalMaximum=%d " +
                                 "unit=%08x unitExponent=%d reportID=%d reportSize=%d reportCount=%d]",
                                 usagePage, logicalMinimum, logicalMaximum, physicalMinimum, physicalMaximum,
                                 unit, unitExponent, reportID, reportSize, reportCount);
        }
    };

    private static class LocalState {
        public List<Range> usages = new ArrayList<Range>();
        public Range   designator = new Range();
        public Range       string = new Range();

        private Range usage = new Range();

        private LocalState() {
        }

        private void delimiter(Item item) {
            if (item.value != 0 && item.value != 1) {
                throw new IllegalArgumentException("Unsupported value in " + item);
            }

            if (item.value == 1) {
                if (usage.values != null || usage.min != null || usage.max != null) {
                    throw new IllegalArgumentException(item + " after usage items not allowed");
                }

                usages.add(usage);
            }
            else {
                usage = new Range();
            }
        }

        private int[] usagesForIndex(int index) {
            if (usages.isEmpty()) {
                Integer usage = this.usage.forIndex(index);

                return usage == null ? new int[0] : new int[] { usage };
            }
            else {
                int[] usage = new int[usages.size()];

                for (int i = 0; i < usage.length; ++i) {
                    usage[i] = usages.get(i).forIndex(index);
                }

                return usage;
            }
        }

        @Override public String toString() {
            return String.format("[LocalState: usages=%s designator=%s string=%s]",
                                 usages.isEmpty() ? Arrays.asList(usage) : usages, designator, string);
        }
    }

    public static class MainItem
        extends GlobalState {
        public Collection parent;

        private MainItem(Collection parent, GlobalState gs) {
            assignFrom(gs);
            this.parent = parent;
        }

        @Override public String toString() {
            return String.format("[MainItem: parent=%s super=%s]",
                                 parent == null ? "[]" : parent.toShortString(), super.toString());
        }
    }

    private static class Range {
        public int[]      values;
        public Integer       min;
        public Integer       max;

        private Range() {
        }

        private void add(int value) {
            if (values == null) {
                set(value);
            }
            else {
                values = Arrays.copyOfRange(values, 0, values.length + 1);
                values[values.length - 1] = value;
            }
        }

        private void set(int value) {
            values = new int[] { value };
        }

        public Integer forIndex(int index) {
            if (values != null) {
                return values[Math.min(index, values.length - 1)];
            }
            else if (min != null) {
                assert min != null && max != null;
                assert index <= (max - min);

                return min + index;
            }
            else {
                return null;
            }
        }

        @Override public String toString() {
            return String.format("[Range: values=%s min=%08x max=%08x]", values == null ? null : toHexString(values), min, max);
        }
    }

    public static class Report {
        public int reportID;
        public EnumSet<Control.Type> types;
        public List<Control> controls;

        public Report(int id) {
            reportID = id;
            types    = EnumSet.noneOf(Control.Type.class);
            controls = new ArrayList<Control>();
        }
    }

    public static class Collection
        extends MainItem {
        public static final int PHYSICAL       = 0;
        public static final int APPLICATION    = 1;
        public static final int LOGICAL        = 2;
        public static final int REPORT         = 3;
        public static final int NAMED_ARRAY    = 4;
        public static final int USAGE_SWITCH   = 5;
        public static final int USAGE_MODIFIER = 6;

        public int id;
        public int type;
        public int[] usages;
        public Integer designator;
        public Integer string;

        private Collection(int id, Collection parent, GlobalState gs, LocalState ls, int type) {
            super(parent, gs);

            this.id    = id;
            this.type  = type;
            usages     = ls.usagesForIndex(0);
            designator = ls.designator.forIndex(0);
            string     = ls.string.forIndex(0);
        }

        public String toShortString() {
            return String.format("[Collection #%d]", id);
        }

        @Override public String toString() {
            return String.format("[Collection #%d type=%s usages=%s designator=%s string=%s super=%s]",
                                 id, type, toHexString(usages), designator, string, super.toString());
        }
    }

    public static class Control
        extends MainItem {
        public enum Type { INPUT, OUTPUT, FEATURE };
        public enum Flag {
            DATA,             CONSTANT,
            ARRAY,            VARIABLE,
            ABSOLUTE,         RELATIVE,
            NO_WRAP,          WRAP,
            LINEAR,           NONLINEAR,
            PREFERRED_STATE,  NO_PREFERED,
            NO_NULL_POSITION, NULL_STATE,
            NONVOLATILE,      VOLATILE,
            BIT_FIELD,        BUFFERED_BYTES,
        };

        public Type type;
        public EnumSet<Flag> flags;

        private LocalState ls;

        private Control(Collection parent, GlobalState gs, LocalState ls, Type type, int flags) {
            super(parent, gs);
            this.type = type;
            this.ls   = ls;
            this.flags = EnumSet.of((flags & 0x001) == 0 ? Flag.DATA             : Flag.CONSTANT,
                                    (flags & 0x002) == 0 ? Flag.ARRAY            : Flag.VARIABLE,
                                    (flags & 0x004) == 0 ? Flag.ABSOLUTE         : Flag.RELATIVE,
                                    (flags & 0x008) == 0 ? Flag.NO_WRAP          : Flag.WRAP,
                                    (flags & 0x010) == 0 ? Flag.LINEAR           : Flag.NONLINEAR,
                                    (flags & 0x020) == 0 ? Flag.PREFERRED_STATE  : Flag.NO_PREFERED,
                                    (flags & 0x040) == 0 ? Flag.NO_NULL_POSITION : Flag.NULL_STATE,
                                    (flags & 0x080) == 0 ? Flag.NONVOLATILE      : Flag.VOLATILE,
                                    (flags & 0x100) == 0 ? Flag.BIT_FIELD        : Flag.BUFFERED_BYTES);
        }

        @Override public String toString() {
            return String.format("[Control: type=%s flags=%s ls=%s super=%s]", type, flags, ls, super.toString());
        }
    }

    public static void main(String[] args)
        throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: " + ReportDescriptor.class.getSimpleName() + " <report-descriptor-as-hex>");
            System.err.println("Example arguments:");
            System.err.println("  Barcode scanner: 058C0902A1010912A1028502150026FF00750895010501093B81029503058C09FB09FC09FD8102953809FE8202020666FF9502090009008102058C25017501950809FF8102C00914A10285041500250175019508095F0960098509869186C006FFFF0901A10285F0150026FF007508953F0902820202953F0903920202C0C0");
            System.err.println("   PS3 controller: 05010904A101A102850175089501150026FF00810375019513150025013500450105091901291381027501950D0600FF8103150026FF0005010901A10075089504350046FF0009300931093209358102C0050175089527090181027508953009019102750895300901B102C0A1028502750895300901B102C0A10285EE750895300901B102C0A10285EF750895300901B102C0C0");
            System.err.println("    Media control: 050C0901A101050C150025017501950709B509B609B709CD09E209E909EA810295018101C0");
            System.err.println("         Keyboard: 05010906A101050719E029E71500250175019508810295017508810195057501050819012905910295017503910195067508150025650507190029658100C0");
            System.err.println("            Mouse: 05010902A1010901A100050919012903150025019503750181029501750581010501093009311581257F750895028106C0C0");

            System.exit(64);
        }

        byte[] raw = fromHexString(args[0]);

        // Dump descriptor tag items
        System.out.println("Descriptor:");
        InputStream is = new ByteArrayInputStream(raw);
        for (Item item = Item.read(is); item != null; item = Item.read(is)) {
            System.out.println(item);
        }

        // Evaluate and dump all reports
        ReportDescriptor hrd = new ReportDescriptor(raw);

        System.out.println();
        System.out.println("Usages associated with collection that contain INPUT controls:");
        for (int usage : hrd.usageSet(Control.Type.INPUT)) {
            System.out.println(String.format("  %08x", usage));
        }
        
        for (Report report : hrd.reports()) {
            for (Control.Type type : report.types) {
                System.out.println();
                System.out.println(String.format("%s report #%d", type, report.reportID));
                hrd.evaluate(report.reportID, type, new Evaluator() {
                        @Override public boolean collection(Collection c) {
                            System.out.println(c);
                            return true;
                        }

                        @Override public boolean control(Control c) {
                            System.out.println(c);
                            return true;
                        }

                        @Override public void constant(Control c, int offset) {
                            System.out.println(String.format("Constant @%d:%d", offset, c.reportSize));
                        }

                        @Override public void array(Control c, int usageMinimum, int usageMaximum, int offset) {
                            System.out.println(String.format("Array %08x-%08x @%d:%d", usageMinimum, usageMaximum, offset, c.reportSize));
                        }

                        @Override public void variable(Control c, int[] usages, int offset) {
                            System.out.println(String.format("Data %s @%d:%d", toHexString(usages), offset, c.reportSize));
                        }
                    });
            }
        }
    }
}
