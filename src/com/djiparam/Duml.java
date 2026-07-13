package com.djiparam;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Lean on-device DUML engine for the DJI RC 2. Read flight-controller params + device
 * identity over the RC's local loopback feeds.
 *
 * Confirmed protocol (see project CLAUDE.md / paramstudio):
 *   inject request on 127.0.0.1:40008, read replies on 127.0.0.1:40007 (busy downstream).
 *   src=0x02 (app) dst=0x03 (FC); cmd_set=0x03; 0xE1 get_info, 0xE2 read, 0xE3 write.
 *   version query = cmd_set 0x00 / cmd_id 0x01. CRC tables from libdrone_hacks_lib.so.
 *
 * A single persistent reader thread on 40007 collects replies; request() injects on 40008
 * and waits for the matching (set,id[,index]) reply. Identity (serial/model codenames) is
 * captured passively from the broadcast stream — no request needed.
 */
public final class Duml {

    public static final int SET = 0x03, APP = 0x02, FC = 0x03;
    public static final int GET_TABLE = 0xE0, GET_INFO = 0xE1, READ_VAL = 0xE2, WRITE_VAL = 0xE3;

    // Collected replies, tagged with a monotonic seq. request() snapshots the current seq high-water
    // mark and later matches only replies with a GREATER seq — so the reader's ready.remove(0) at the
    // 4096 cap can never desync matching the way an absolute list index did.
    private final List<Reply> ready = new ArrayList<>();
    private long seqCounter = 0;                               // guarded by `ready`
    private volatile boolean up = false;

    private static final class Reply {
        final long seq; final int set, id, index; final byte[] pl;
        Reply(long seq, int set, int id, int index, byte[] pl) {
            this.seq = seq; this.set = set; this.id = id; this.index = index; this.pl = pl;
        }
    }
    private volatile boolean running = false;
    private volatile long lastRx = 0;
    private volatile Socket rsock;

    // Smoothed round-trip time of successful requests. Drives adaptiveTimeout() so waits grow when
    // the 40007 downstream is congested (e.g. DJI Fly mirroring FPV) and shrink when it's calm.
    private volatile long rttEwmaMs = 120;

    // passively-captured device identity (from the 40007 stream)
    private volatile String acSerial = "";   // aircraft serial number
    private volatile String rcModel = "";    // RC model code (e.g. rc331)
    private volatile String acModel = "";    // aircraft model codename (e.g. wa151)
    private final HashMap<String, Integer> codeSeen = new HashMap<>();

    public boolean isUp() { return running && up && (System.currentTimeMillis() - lastRx) < 4000; }
    public boolean running() { return running; }
    public String acSerial() { return acSerial; }
    public String rcModel() { return rcModel; }
    public String acModel() { return acModel; }

    public void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(this::readerLoop, "duml-reader");
        t.setDaemon(true);
        t.start();
        Logger.i("[duml] reader started");
    }

    /** Release everything — never overlap DJI Fly. */
    public void stop() {
        running = false; up = false;
        try { if (rsock != null) rsock.close(); } catch (Throwable t) {}
        synchronized (ready) { ready.clear(); }   // drop stale replies so a new session starts clean
        Logger.i("[duml] stopped");
    }

    private void readerLoop() {
        while (running) {
            Socket s = new Socket();
            rsock = s;
            try {
                s.setTcpNoDelay(true);
                s.connect(new InetSocketAddress("127.0.0.1", 40007), 2000);
                s.setSoTimeout(4000);
                up = true;
                InputStream in = s.getInputStream();
                byte[] buf = new byte[32768];
                int have = 0;
                while (running) {
                    int r;
                    try { r = in.read(buf, have, buf.length - have); }
                    catch (java.net.SocketTimeoutException te) { continue; }
                    if (r < 0) break;
                    have += r; lastRx = System.currentTimeMillis();
                    int i = 0;
                    while (i + 13 <= have) {
                        if ((buf[i] & 0xFF) != 0x55) { i++; continue; }
                        int len = (buf[i + 1] & 0xFF) | ((buf[i + 2] & 0x03) << 8);
                        if (len < 13 || i + len > have) { if (len >= 13 && i + len > have) break; i++; continue; }
                        if (crc8(buf, i, 3) != (buf[i + 3] & 0xFF)) { i++; continue; }
                        int exp = (buf[i + len - 2] & 0xFF) | ((buf[i + len - 1] & 0xFF) << 8);
                        if (crc16(buf, i, len - 2) != exp) { i++; continue; }
                        int src = buf[i + 4] & 0xFF, typ = buf[i + 8] & 0xFF,
                            set = buf[i + 9] & 0xFF, id = buf[i + 10] & 0xFF;
                        byte[] pl = new byte[len - 13];
                        System.arraycopy(buf, i + 11, pl, 0, pl.length);
                        capture(src, set, id, pl);
                        if ((typ & 0x80) != 0) {   // reply-flagged frame
                            int idx = pl.length >= 6 ? (pl[4] & 0xFF) | ((pl[5] & 0xFF) << 8) : -1;
                            synchronized (ready) {
                                ready.add(new Reply(++seqCounter, set, id, idx, pl));
                                if (ready.size() > 4096) ready.remove(0);
                            }
                        }
                        i += len;
                    }
                    if (i > 0 && i <= have) { System.arraycopy(buf, i, buf, 0, have - i); have -= i; }
                    if (have == buf.length) have = 0;
                }
            } catch (Throwable e) {
                up = false;
            } finally { try { s.close(); } catch (Throwable t) {} }
            if (running) sleep(300);
        }
    }

    /**
     * Read-only request: ONE request per param (fast path), tested with no other DUML app in the
     * background. The timeout still adapts to recent channel latency (adaptiveTimeout) — on a calm
     * channel that equals the caller's hint, so this behaves like the original single-shot read.
     * (If contention ever forces retries back, this is the single place to add the loop.)
     */
    public byte[] requestRead(int cmdSet, int cmdId, byte[] payload, int wantIndex, int timeoutMs) {
        byte[] pl = request(cmdSet, cmdId, payload, wantIndex, adaptiveTimeout(timeoutMs));
        if (pl == null && running) {   // one retry: a single lost reply on the busy 40007 is common
            sleep(180);                // let the channel rest before re-firing (fast re-fire just re-drops)
            pl = request(cmdSet, cmdId, payload, wantIndex, adaptiveTimeout(timeoutMs));
        }
        return pl;
    }

    /**
     * Per-attempt timeout that adapts to recent channel latency: at least the caller's hint, but
     * widened to ~4× the smoothed round-trip time when the downstream is slow. Never below the hint,
     * capped so a stalled channel can't hang a read for too long.
     */
    public int adaptiveTimeout(int hintMs) {
        long need = rttEwmaMs * 4;
        long t = Math.min(Math.max(hintMs, need), 2500);
        return (int) Math.max(t, hintMs);
    }

    /** Feed a successful round-trip time into the EWMA (alpha≈0.25), clamped to a sane band. */
    private void noteRtt(long ms) {
        long e = (rttEwmaMs * 3 + ms) / 4;
        rttEwmaMs = Math.max(40, Math.min(e, 2000));
    }

    // Send a request on 40008, wait for the reply matching (cmdSet, cmdId, index). Returns payload or null.
    // Single-shot: writes rely on this NOT retrying. Reads go through requestRead() for retries.
    public byte[] request(int cmdSet, int cmdId, byte[] payload, int wantIndex, int timeoutMs) {
        if (!running) return null;
        long mark;
        synchronized (ready) { mark = seqCounter; }   // only replies newer than this can be ours
        byte[] pkt = build(cmdSet, cmdId, payload);
        Socket s = new Socket();
        try {
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress("127.0.0.1", 40008), 1500);
            OutputStream out = s.getOutputStream();
            out.write(pkt); out.flush();
        } catch (Throwable e) {
            Logger.w("[duml] send err set=" + cmdSet + " id=" + cmdId + ": " + e);
            try { s.close(); } catch (Throwable t) {}
            return null;
        }
        long t0 = System.currentTimeMillis();
        long end = t0 + timeoutMs;
        try {
            while (System.currentTimeMillis() < end) {
                synchronized (ready) {
                    // scan newest→oldest; stop once we pass our seq mark (list is seq-ordered)
                    for (int k = ready.size() - 1; k >= 0; k--) {
                        Reply m = ready.get(k);
                        if (m.seq <= mark) break;
                        if (m.set == cmdSet && m.id == cmdId && (wantIndex < 0 || m.index == wantIndex)) {
                            noteRtt(System.currentTimeMillis() - t0);
                            return m.pl;
                        }
                    }
                }
                sleep(30);
            }
        } finally { try { s.close(); } catch (Throwable t) {} }
        return null;
    }

    // ---- fcc-style full name scan (one persistent inject socket) ----

    public interface ScanCb { void progress(int idx, int count, int named); boolean cancelled(); }

    /**
     * fcc-style live name map: get_info (0xE1) over every index 0..count to read the real parameter
     * name straight from the board, building index→name. Mirrors fcc's C0046i loop but reuses our
     * proven transport — ONE persistent 40008 inject socket for all requests (no per-request socket
     * churn, the slow part before), replies collected by the running 40007 reader. So start() must be
     * up and DJI Fly CLOSED (the scan reads on 40007). Retries up to 3× per index like fcc.
     */
    public java.util.LinkedHashMap<Integer, String> scanNames(int table, int count, ScanCb cb) {
        java.util.LinkedHashMap<Integer, String> map = new java.util.LinkedHashMap<>();
        if (!running || count <= 0) return map;
        Socket s = new Socket();
        try {
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress("127.0.0.1", 40008), 1500);
            OutputStream out = s.getOutputStream();
            final int BATCH = 128;
            for (int start = 0; start < count && running; start += BATCH) {
                if (cb != null && cb.cancelled()) break;
                int endIdx = Math.min(count, start + BATCH);
                // indices we still need a reply for in this batch (empty slots simply never resolve)
                java.util.HashSet<Integer> missing = new java.util.HashSet<>();
                for (int i = start; i < endIdx; i++) missing.add(i);
                // up to 2 pipelined passes: fire ALL missing back-to-back, then harvest by index
                for (int pass = 0; pass < 2 && !missing.isEmpty() && running; pass++) {
                    long mark; synchronized (ready) { mark = seqCounter; }
                    for (int idx : new java.util.ArrayList<>(missing)) {
                        byte[] p = { (byte) table, (byte) (table >> 8), (byte) idx, (byte) (idx >> 8) };
                        try { out.write(build(SET, GET_INFO, p)); out.flush(); }
                        catch (Throwable e) { Logger.w("[scan] write err: " + e); return map; }
                        sleep(2);                                   // gentle pace so the router keeps up
                    }
                    // harvest: drain replies until the stream dries up (no new match for 450ms) or hard cap
                    long lastGot = System.currentTimeMillis();
                    long hardEnd = lastGot + missing.size() * 8L + 900;
                    while (!missing.isEmpty() && System.currentTimeMillis() < hardEnd && running) {
                        boolean got = false;
                        synchronized (ready) {
                            for (int k = ready.size() - 1; k >= 0; k--) {
                                Reply m = ready.get(k);
                                if (m.seq <= mark) break;
                                if (m.set == SET && m.id == GET_INFO && missing.remove(m.index)) {
                                    String nm = nameFromInfo(m.pl);
                                    if (nm != null && !nm.isEmpty()) map.put(m.index, nm);
                                    got = true;
                                }
                            }
                        }
                        if (got) lastGot = System.currentTimeMillis();
                        else { if (System.currentTimeMillis() - lastGot > 450) break; sleep(8); }
                    }
                }
                if (cb != null) cb.progress(endIdx, count, map.size());
            }
        } catch (Throwable e) {
            Logger.w("[scan] socket err: " + e);
        } finally { try { s.close(); } catch (Throwable t) {} }
        return map;
    }

    /** Name = NUL-terminated ASCII at offset 22 of a get_info reply payload (fcc/2017 layout). */
    static String nameFromInfo(byte[] pl) {
        if (pl == null || pl.length <= 22) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 22; i < pl.length; i++) {
            int c = pl[i] & 0xFF;
            if (c == 0) break;
            if (c >= 32 && c < 127) sb.append((char) c);
        }
        return sb.toString().trim();
    }

    // ---- quick-param read/write (cmd_set 0x03 by-index) ----
    // Reply payload layout: <status:u32=0 OK><index:u16><value LE by type>. We work in `long`
    // (all quick params are integers); always verify the on-board name via get_info before a write
    // so a wrong index on a different firmware/model can't clobber an unrelated parameter.

    /**
     * get_info (0xE1): the real parameter name the board reports at this index. Returns "" if the
     * board answered but with a non-zero status, or null if there was no reply at all.
     */
    public String getInfoName(int table, int index, int timeoutMs) {
        // single-shot: get_info is frequently silent on this FC, so retrying just burns the timeout;
        // name verification only ever BLOCKS a write on a positive mismatch, never on a null.
        byte[] pl = request(SET, GET_INFO, reqPayload(table, index, null), index, timeoutMs);
        if (pl == null) return null;
        long status = u32(pl, 0);
        if (status != 0) { Logger.w("[duml] get_info idx=" + index + " status=" + status); return ""; }
        // name = trailing NUL-terminated ASCII run (after <index><attr><type><default><min><max>)
        int end = pl.length;
        while (end > 0 && pl[end - 1] == 0) end--;
        int start = end;
        while (start > 0 && (pl[start - 1] & 0xFF) >= 0x20 && (pl[start - 1] & 0xFF) < 0x7f) start--;
        String name = end > start ? new String(pl, start, end - start) : "";
        Logger.i("[duml] get_info idx=" + index + " name='" + name + "' raw=" + hex(pl));
        return name;
    }

    /**
     * get_info (0xE1) raw reply, or null. Uses the CORRECT 4-byte payload &lt;table:u16&gt;&lt;index:u16&gt;
     * (NO unknown1 — the old getInfoName used a 6-byte payload, which this FC silently ignores).
     * Reply layout (2017): status:u16, table:u16, index:u16, type_id:u16, size:u16, def[10:14],
     * min[14:18], max[18:22], name\0. def/min/max are 4-byte fields (decode by type).
     */
    public byte[] getInfoRaw(int table, int index, int timeoutMs) {
        byte[] pl = new byte[]{ (byte) table, (byte) (table >> 8), (byte) index, (byte) (index >> 8) };
        return request(SET, GET_INFO, pl, index, timeoutMs);   // single-shot (best-effort default)
    }

    /**
     * get_table_attributes (0xE0): firmware param-table identity for `table`. Returns {crc, count}
     * (u32 each) or null. CRC identifies the model reliably (see model_tables/README); count breaks
     * the wa150/wa151 CRC tie. Request payload is just &lt;table:u16&gt; (no unknown1); reply is
     * &lt;status:u16&gt;&lt;table:u16&gt;&lt;crc:u32&gt;&lt;count:u32&gt;.
     */
    public long[] tableInfo(int table, int timeoutMs) {
        byte[] pl = requestRead(SET, GET_TABLE, new byte[]{ (byte) table, (byte) (table >> 8) }, -1, timeoutMs);
        if (pl == null || pl.length < 12) return null;
        long status = (pl[0] & 0xFFL) | ((pl[1] & 0xFFL) << 8);
        if (status != 0) { Logger.w("[duml] 0xE0 status=" + status); return null; }
        return new long[]{ u32(pl, 4), u32(pl, 8) };
    }

    /** read_value (0xE2): current integer value at index, decoded by type. null if no reply / status!=0. */
    public Long readParam(int table, int index, String type, int timeoutMs) {
        byte[] pl = requestRead(SET, READ_VAL, reqPayload(table, index, null), index, timeoutMs);
        if (pl == null) return null;
        long status = u32(pl, 0);
        if (status != 0) { Logger.w("[duml] read idx=" + index + " status=" + status); return null; }
        if (pl.length < 6 + width(type)) { Logger.w("[duml] read idx=" + index + " short reply"); return null; }
        return decodeInt(pl, 6, type);
    }

    /** write_value (0xE3): write an integer value. Returns DUML status (0 = OK), or -1 on no reply. */
    public long writeParam(int table, int index, String type, long value, int timeoutMs) {
        byte[] vb = encodeInt(type, value);
        byte[] pl = request(SET, WRITE_VAL, reqPayload(table, index, vb), index, timeoutMs);
        if (pl == null) { Logger.w("[duml] write idx=" + index + " no reply"); return -1; }
        long status = u32(pl, 0);
        Logger.i("[duml] write idx=" + index + " val=" + value + " status=" + status);
        return status;
    }

    /** write_value (0xE3) with pre-encoded value bytes (used for floats). Status 0 = OK, -1 = no reply. */
    public long writeValue(int table, int index, byte[] valueBytes, int timeoutMs) {
        byte[] pl = request(SET, WRITE_VAL, reqPayload(table, index, valueBytes), index, timeoutMs);
        if (pl == null) { Logger.w("[duml] write idx=" + index + " no reply"); return -1; }
        long status = u32(pl, 0);
        Logger.i("[duml] write idx=" + index + " (" + valueBytes.length + "b) status=" + status);
        return status;
    }

    /** Encode a little-endian float (F32/F64). */
    static byte[] encodeFloat(String type, double value) {
        if ("F64".equals(type)) {
            long bits = Double.doubleToLongBits(value);
            byte[] b = new byte[8];
            for (int i = 0; i < 8; i++) b[i] = (byte) (bits >> (8 * i));
            return b;
        }
        int bits = Float.floatToIntBits((float) value);
        byte[] b = new byte[4];
        for (int i = 0; i < 4; i++) b[i] = (byte) (bits >> (8 * i));
        return b;
    }

    static long u32(byte[] p, int off) {
        return (p[off] & 0xFFL) | ((p[off + 1] & 0xFFL) << 8)
                | ((p[off + 2] & 0xFFL) << 16) | ((p[off + 3] & 0xFFL) << 24);
    }

    static int width(String type) {
        switch (type) {
            case "U8": case "I8": return 1;
            case "U16": case "I16": return 2;
            case "U32": case "I32": return 4;
            case "U64": case "I64": return 8;
            default: return 2;
        }
    }

    /** Decode a little-endian integer of the given type; sign-extends the signed types. */
    static long decodeInt(byte[] p, int off, String type) {
        int w = width(type);
        long v = 0;
        for (int i = 0; i < w; i++) v |= (p[off + i] & 0xFFL) << (8 * i);
        switch (type) {
            case "I8":  return (byte) v;
            case "I16": return (short) v;
            case "I32": return (int) v;
            default:    return v;   // U8/U16/U32/U64
        }
    }

    static byte[] encodeInt(String type, long value) {
        int w = width(type);
        byte[] b = new byte[w];
        for (int i = 0; i < w; i++) b[i] = (byte) (value >> (8 * i));
        return b;
    }

    // passively capture device identity from the stream
    private void capture(int src, int set, int id, byte[] pl) {
        // aircraft serial: FC get-version reply or a 0x51 broadcast — longest alnum ASCII run
        if ((src == FC && set == 0x00 && id == 0x01) || set == 0x51) {
            String s = asciiRun(pl, 8);
            if (looksSerial(s) && s.length() > acSerial.length()) acSerial = s;
        }
        // model codenames (rc331, wa151, ...) appear in version broadcasts (cmd_set 0x00)
        if (set == 0x00) {
            for (String c : runs(pl, 4)) {
                if (!codename(c)) continue;
                String cl = c.toLowerCase();
                synchronized (codeSeen) {
                    if (codeSeen.put(cl, 1) == null)
                        Logger.i(String.format("[duml] codename %s (src=%02x set=%02x id=%02x)", cl, src, set, id));
                }
                if (cl.startsWith("rc")) rcModel = cl; else acModel = cl;
            }
        }
    }

    static boolean codename(String s) {
        if (s.length() < 4 || s.length() > 7) return false;
        if (!Character.isLetter(s.charAt(0)) || !Character.isLetter(s.charAt(1))) return false;
        int digits = 0;
        for (int i = 2; i < s.length(); i++) { if (!Character.isDigit(s.charAt(i))) return false; digits++; }
        return digits >= 2;
    }

    static boolean looksSerial(String s) {
        if (s.length() < 10) return false;
        for (int i = 0; i < s.length(); i++) if (!Character.isLetterOrDigit(s.charAt(i))) return false;
        return true;
    }

    static String asciiRun(byte[] p, int minLen) {
        String best = "";
        for (String r : runs(p, minLen)) if (r.length() > best.length()) best = r;
        return best;
    }

    static List<String> runs(byte[] p, int minLen) {
        ArrayList<String> out = new ArrayList<>();
        int st = -1;
        for (int i = 0; i <= p.length; i++) {
            boolean pr = i < p.length && (p[i] & 0xFF) > 0x20 && (p[i] & 0xFF) < 0x7f;
            if (pr) { if (st < 0) st = i; }
            else { if (st >= 0) { if (i - st >= minLen) out.add(new String(p, st, i - st)); st = -1; } }
        }
        return out;
    }

    static String hex(byte[] p) {
        StringBuilder sb = new StringBuilder();
        for (byte b : p) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }

    /**
     * Read one integer/float param's raw reply payload (<status:u32><index:u16><value>), or null.
     * Used by the all-params lazy loader — deliberately ONE request per fresh socket, because on
     * this RC hammering the DUML channel (socket churn or many requests per connection) storms the
     * router's FPV mirror (DUSS5A fpv_sock Broken pipe / DUSS41 -1002). Keep reads gentle & paced.
     */
    public byte[] readRaw(int table, int index, int timeoutMs) {
        return requestRead(SET, READ_VAL, reqPayload(table, index, null), index, timeoutMs);
    }

    /** Decode a little-endian float (F32/F64) at offset; NaN if the payload is too short. */
    static double decodeFloat(byte[] p, int off, String type) {
        if ("F64".equals(type)) {
            if (p.length < off + 8) return Double.NaN;
            long lv = 0; for (int i = 0; i < 8; i++) lv |= (p[off + i] & 0xFFL) << (8 * i);
            return Double.longBitsToDouble(lv);
        }
        if (p.length < off + 4) return Double.NaN;
        int iv = 0; for (int i = 0; i < 4; i++) iv |= (p[off + i] & 0xFF) << (8 * i);
        return Float.intBitsToFloat(iv);
    }

    static byte[] reqPayload(int table, int index, byte[] value) {
        int vlen = value == null ? 0 : value.length;
        byte[] p = new byte[6 + vlen];
        p[0] = (byte) table; p[1] = (byte) (table >> 8);
        p[2] = 1; p[3] = 0;                       // unknown1 = 1 (u16)
        p[4] = (byte) index; p[5] = (byte) (index >> 8);
        if (vlen > 0) System.arraycopy(value, 0, p, 6, vlen);
        return p;
    }

    private static byte[] build(int cmdSet, int cmdId, byte[] payload) { return frame(cmdSet, cmdId, payload, FC, APP, 0); }
    // Full DUML v1 frame with explicit dst/src/seq. src=0x02 for the normal 40008 path, 0x0A for the
    // 40009 inject path (fcc uses 0x0A there). CRC8 init 0x77 + CRC16 init 0x3692 (see tables below).
    private static byte[] frame(int cmdSet, int cmdId, byte[] payload, int dst, int src, int seq) {
        int len = 13 + payload.length;
        byte[] b = new byte[len];
        b[0] = 0x55; b[1] = (byte) len; b[2] = (byte) ((1 << 2) | ((len >> 8) & 3));
        b[3] = (byte) crc8(b, 0, 3);
        b[4] = (byte) src; b[5] = (byte) dst; b[6] = (byte) seq; b[7] = (byte) (seq >> 8);
        b[8] = 0x40; b[9] = (byte) cmdSet; b[10] = (byte) cmdId;
        System.arraycopy(payload, 0, b, 11, payload.length);
        int c = crc16(b, 0, len - 2);
        b[len - 2] = (byte) c; b[len - 1] = (byte) (c >> 8);
        return b;
    }

    // rolling seq for coexist injects
    private int seqCoexist = 0x0100;

    /**
     * "Coexist with DJI Fly" write: fire-and-forget write_value (0xE3) on the 40008 inject socket.
     * One-shot connect→write→close, NO reader, NO readback. This is the write half of our normal path
     * (40008 upstream inject, src=0x02) but WITHOUT opening the persistent 40007 reader — and it's the
     * 40007 reader that churns DJI Fly's video mirror (§4), not the inject. So a brief write here should
     * not disturb Fly. Uses 40008, NOT 40009: fcc writes on 40009, but 40009 only routes injects from a
     * privileged uid — from our untrusted app the identical frame on 40009 is silently dropped, while
     * 40008 routes fine (confirmed on LitoX1: forearm_led_ctrl idx 23 toggles via 40008).
     *
     * No confirmation is possible without the reader — returns true only if the frame was written to the
     * socket. Does NOT require start() to be up. Hold the socket ~600ms before close so the router has
     * time to forward the inject (a 150ms close was too short and dropped the frame).
     */
    public boolean writeOnceCoexist(int table, int index, String type, long value) {
        byte[] pkt = frame(SET, WRITE_VAL, reqPayload(table, index, encodeInt(type, value)), FC, APP, seqCoexist++ & 0xFFFF);
        Socket s = new Socket();
        try {
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress("127.0.0.1", 40008), 1500);
            OutputStream out = s.getOutputStream();
            out.write(pkt); out.flush();
            Logger.i("[duml] coexist write (40008) idx=" + index + " val=" + value + " " + hex(pkt));
            try { Thread.sleep(600); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            return true;
        } catch (Throwable e) {
            Logger.w("[duml] coexist write idx=" + index + " send err: " + e);
            return false;
        } finally {
            try { s.close(); } catch (Throwable t) {}
        }
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) {} }

    // ---- CRC (extracted from libdrone_hacks_lib.so; verified 55 0E 04 -> 0x66) ----
    static int crc8(byte[] d, int off, int len) {
        int c = 0x77;
        for (int i = 0; i < len; i++) c = CRC8[(c ^ (d[off + i] & 0xFF)) & 0xFF] & 0xFF;
        return c & 0xFF;
    }
    static int crc16(byte[] d, int off, int len) {
        int c = 0x3692;
        for (int i = 0; i < len; i++) c = (CRC16[(c ^ (d[off + i] & 0xFF)) & 0xFF] ^ (c >> 8)) & 0xFFFF;
        return c & 0xFFFF;
    }
    private static final byte[] CRC8 = {
        (byte)0x00,(byte)0x5E,(byte)0xBC,(byte)0xE2,(byte)0x61,(byte)0x3F,(byte)0xDD,(byte)0x83,(byte)0xC2,(byte)0x9C,(byte)0x7E,(byte)0x20,(byte)0xA3,(byte)0xFD,(byte)0x1F,(byte)0x41,
        (byte)0x9D,(byte)0xC3,(byte)0x21,(byte)0x7F,(byte)0xFC,(byte)0xA2,(byte)0x40,(byte)0x1E,(byte)0x5F,(byte)0x01,(byte)0xE3,(byte)0xBD,(byte)0x3E,(byte)0x60,(byte)0x82,(byte)0xDC,
        (byte)0x23,(byte)0x7D,(byte)0x9F,(byte)0xC1,(byte)0x42,(byte)0x1C,(byte)0xFE,(byte)0xA0,(byte)0xE1,(byte)0xBF,(byte)0x5D,(byte)0x03,(byte)0x80,(byte)0xDE,(byte)0x3C,(byte)0x62,
        (byte)0xBE,(byte)0xE0,(byte)0x02,(byte)0x5C,(byte)0xDF,(byte)0x81,(byte)0x63,(byte)0x3D,(byte)0x7C,(byte)0x22,(byte)0xC0,(byte)0x9E,(byte)0x1D,(byte)0x43,(byte)0xA1,(byte)0xFF,
        (byte)0x46,(byte)0x18,(byte)0xFA,(byte)0xA4,(byte)0x27,(byte)0x79,(byte)0x9B,(byte)0xC5,(byte)0x84,(byte)0xDA,(byte)0x38,(byte)0x66,(byte)0xE5,(byte)0xBB,(byte)0x59,(byte)0x07,
        (byte)0xDB,(byte)0x85,(byte)0x67,(byte)0x39,(byte)0xBA,(byte)0xE4,(byte)0x06,(byte)0x58,(byte)0x19,(byte)0x47,(byte)0xA5,(byte)0xFB,(byte)0x78,(byte)0x26,(byte)0xC4,(byte)0x9A,
        (byte)0x65,(byte)0x3B,(byte)0xD9,(byte)0x87,(byte)0x04,(byte)0x5A,(byte)0xB8,(byte)0xE6,(byte)0xA7,(byte)0xF9,(byte)0x1B,(byte)0x45,(byte)0xC6,(byte)0x98,(byte)0x7A,(byte)0x24,
        (byte)0xF8,(byte)0xA6,(byte)0x44,(byte)0x1A,(byte)0x99,(byte)0xC7,(byte)0x25,(byte)0x7B,(byte)0x3A,(byte)0x64,(byte)0x86,(byte)0xD8,(byte)0x5B,(byte)0x05,(byte)0xE7,(byte)0xB9,
        (byte)0x8C,(byte)0xD2,(byte)0x30,(byte)0x6E,(byte)0xED,(byte)0xB3,(byte)0x51,(byte)0x0F,(byte)0x4E,(byte)0x10,(byte)0xF2,(byte)0xAC,(byte)0x2F,(byte)0x71,(byte)0x93,(byte)0xCD,
        (byte)0x11,(byte)0x4F,(byte)0xAD,(byte)0xF3,(byte)0x70,(byte)0x2E,(byte)0xCC,(byte)0x92,(byte)0xD3,(byte)0x8D,(byte)0x6F,(byte)0x31,(byte)0xB2,(byte)0xEC,(byte)0x0E,(byte)0x50,
        (byte)0xAF,(byte)0xF1,(byte)0x13,(byte)0x4D,(byte)0xCE,(byte)0x90,(byte)0x72,(byte)0x2C,(byte)0x6D,(byte)0x33,(byte)0xD1,(byte)0x8F,(byte)0x0C,(byte)0x52,(byte)0xB0,(byte)0xEE,
        (byte)0x32,(byte)0x6C,(byte)0x8E,(byte)0xD0,(byte)0x53,(byte)0x0D,(byte)0xEF,(byte)0xB1,(byte)0xF0,(byte)0xAE,(byte)0x4C,(byte)0x12,(byte)0x91,(byte)0xCF,(byte)0x2D,(byte)0x73,
        (byte)0xCA,(byte)0x94,(byte)0x76,(byte)0x28,(byte)0xAB,(byte)0xF5,(byte)0x17,(byte)0x49,(byte)0x08,(byte)0x56,(byte)0xB4,(byte)0xEA,(byte)0x69,(byte)0x37,(byte)0xD5,(byte)0x8B,
        (byte)0x57,(byte)0x09,(byte)0xEB,(byte)0xB5,(byte)0x36,(byte)0x68,(byte)0x8A,(byte)0xD4,(byte)0x95,(byte)0xCB,(byte)0x29,(byte)0x77,(byte)0xF4,(byte)0xAA,(byte)0x48,(byte)0x16,
        (byte)0xE9,(byte)0xB7,(byte)0x55,(byte)0x0B,(byte)0x88,(byte)0xD6,(byte)0x34,(byte)0x6A,(byte)0x2B,(byte)0x75,(byte)0x97,(byte)0xC9,(byte)0x4A,(byte)0x14,(byte)0xF6,(byte)0xA8,
        (byte)0x74,(byte)0x2A,(byte)0xC8,(byte)0x96,(byte)0x15,(byte)0x4B,(byte)0xA9,(byte)0xF7,(byte)0xB6,(byte)0xE8,(byte)0x0A,(byte)0x54,(byte)0xD7,(byte)0x89,(byte)0x6B,(byte)0x35,
    };
    private static final int[] CRC16 = {
        0x0000,0x1189,0x2312,0x329B,0x4624,0x57AD,0x6536,0x74BF,0x8C48,0x9DC1,0xAF5A,0xBED3,0xCA6C,0xDBE5,0xE97E,0xF8F7,
        0x1081,0x0108,0x3393,0x221A,0x56A5,0x472C,0x75B7,0x643E,0x9CC9,0x8D40,0xBFDB,0xAE52,0xDAED,0xCB64,0xF9FF,0xE876,
        0x2102,0x308B,0x0210,0x1399,0x6726,0x76AF,0x4434,0x55BD,0xAD4A,0xBCC3,0x8E58,0x9FD1,0xEB6E,0xFAE7,0xC87C,0xD9F5,
        0x3183,0x200A,0x1291,0x0318,0x77A7,0x662E,0x54B5,0x453C,0xBDCB,0xAC42,0x9ED9,0x8F50,0xFBEF,0xEA66,0xD8FD,0xC974,
        0x4204,0x538D,0x6116,0x709F,0x0420,0x15A9,0x2732,0x36BB,0xCE4C,0xDFC5,0xED5E,0xFCD7,0x8868,0x99E1,0xAB7A,0xBAF3,
        0x5285,0x430C,0x7197,0x601E,0x14A1,0x0528,0x37B3,0x263A,0xDECD,0xCF44,0xFDDF,0xEC56,0x98E9,0x8960,0xBBFB,0xAA72,
        0x6306,0x728F,0x4014,0x519D,0x2522,0x34AB,0x0630,0x17B9,0xEF4E,0xFEC7,0xCC5C,0xDDD5,0xA96A,0xB8E3,0x8A78,0x9BF1,
        0x7387,0x620E,0x5095,0x411C,0x35A3,0x242A,0x16B1,0x0738,0xFFCF,0xEE46,0xDCDD,0xCD54,0xB9EB,0xA862,0x9AF9,0x8B70,
        0x8408,0x9581,0xA71A,0xB693,0xC22C,0xD3A5,0xE13E,0xF0B7,0x0840,0x19C9,0x2B52,0x3ADB,0x4E64,0x5FED,0x6D76,0x7CFF,
        0x9489,0x8500,0xB79B,0xA612,0xD2AD,0xC324,0xF1BF,0xE036,0x18C1,0x0948,0x3BD3,0x2A5A,0x5EE5,0x4F6C,0x7DF7,0x6C7E,
        0xA50A,0xB483,0x8618,0x9791,0xE32E,0xF2A7,0xC03C,0xD1B5,0x2942,0x38CB,0x0A50,0x1BD9,0x6F66,0x7EEF,0x4C74,0x5DFD,
        0xB58B,0xA402,0x9699,0x8710,0xF3AF,0xE226,0xD0BD,0xC134,0x39C3,0x284A,0x1AD1,0x0B58,0x7FE7,0x6E6E,0x5CF5,0x4D7C,
        0xC60C,0xD785,0xE51E,0xF497,0x8028,0x91A1,0xA33A,0xB2B3,0x4A44,0x5BCD,0x6956,0x78DF,0x0C60,0x1DE9,0x2F72,0x3EFB,
        0xD68D,0xC704,0xF59F,0xE416,0x90A9,0x8120,0xB3BB,0xA232,0x5AC5,0x4B4C,0x79D7,0x685E,0x1CE1,0x0D68,0x3FF3,0x2E7A,
        0xE70E,0xF687,0xC41C,0xD595,0xA12A,0xB0A3,0x8238,0x93B1,0x6B46,0x7ACF,0x4854,0x59DD,0x2D62,0x3CEB,0x0E70,0x1FF9,
        0xF78F,0xE606,0xD49D,0xC514,0xB1AB,0xA022,0x92B9,0x8330,0x7BC7,0x6A4E,0x58D5,0x495C,0x3DE3,0x2C6A,0x1EF1,0x0F78,
    };
}
