package com.yingtu.plus.drama;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Fanqie upstream request signing, ported line-for-line from the Rust
 * implementation (huoyi_juhe rust/src/sign/*) and verified against
 * rust/testdata/vectors.json through the Python mirror
 * (~/workspace/yingtu_drama_work/fqsign.py, 20/20 vectors pass) plus a live
 * plan/v + video_model round trip.
 *
 * What it produces per request: x-ss-req-ticket, x-khronos, x-ladon,
 * x-argus (551 framing) and, for POST bodies, x-ss-stub (MD5 hex).
 *
 * Where protobuf lives here: ONLY inside x-argus. The plan/v and
 * cell/change endpoints themselves are plain JSON GETs; the protobuf writer
 * below encodes the ordered Argus bean (fields 1..25), which is then
 * PKCS7-padded, Simon-encrypted, framed and AES-CBC-encrypted. There is no
 * protobuf on the wire for the feed or playback endpoints.
 *
 * No new dependency: MD5/SHA-512 via java.security, AES via javax.crypto,
 * Base64 via java.util. Pure Java, no Android classes, so it stays unit
 * testable off-device.
 */
public final class FqSign {

    private FqSign() {
    }

    public static final long LICENSE_ID = 1611921764L;
    public static final int DEFAULT_AID = 1967;

    private static final byte[] ARGUS_SIGN_KEY = hex(
            "fc78e0a9657a0c748ce51559903ccf03510e51d3cff232d71343e88a321c5304");
    private static final byte[] ARGUS_AES_KEY = hex(
            "d31e3718288a1027baab59f146a09a9c");
    private static final byte[] ARGUS_AES_IV = hex(
            "ea180a0336ed352fcd24e4d50018ae54");

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String hexEncode(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }

    // ------------------------------------------------------------ protobuf

    /** Encode-only protobuf writer used by x-argus. */
    public static final class ProtoBuf {
        private static final class Field {
            final int idx;
            final int typ;
            final long vUint;
            final int blobIndex;

            Field(int idx, int typ, long vUint, int blobIndex) {
                this.idx = idx;
                this.typ = typ;
                this.vUint = vUint;
                this.blobIndex = blobIndex;
            }
        }

        private final List<Field> fields = new ArrayList<>();
        private final List<byte[]> blobs = new ArrayList<>();

        public void putVarint(int idx, long v) {
            fields.add(new Field(idx, 0, v, 0));
        }

        private void putBlob(int idx, byte[] data) {
            blobs.add(data);
            fields.add(new Field(idx, 2, 0, blobs.size() - 1));
        }

        public void putUtf8(int idx, String s) {
            putBlob(idx, s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8));
        }

        public void putBytes(int idx, byte[] data) {
            putBlob(idx, data == null ? new byte[0] : data);
        }

        public void putProtobuf(int idx, ProtoBuf nested) {
            putBlob(idx, nested.toBuf());
        }

        public byte[] toBuf() {
            ByteArray out = new ByteArray();
            for (Field f : fields) {
                long key = ((long) f.idx << 3) | (f.typ & 7);
                writeVarint(out, key);
                if (f.typ == 0) {
                    writeVarint(out, f.vUint);
                } else if (f.typ == 2) {
                    byte[] blob = blobs.get(f.blobIndex);
                    writeVarint(out, blob.length);
                    out.add(blob);
                } else if (f.typ == 5) {
                    long v = f.vUint & 0xFFFFFFFFL;
                    out.add((byte) v);
                    out.add((byte) (v >> 8));
                    out.add((byte) (v >> 16));
                    out.add((byte) (v >> 24));
                } else if (f.typ == 1) {
                    long v = f.vUint;
                    for (int i = 0; i < 8; i++) out.add((byte) (v >> (i * 8)));
                }
            }
            return out.toArray();
        }

        /** writeVarint masks to 32 bits first, matching the upstream layout. */
        private static void writeVarint(ByteArray out, long v) {
            v &= 0xFFFFFFFFL;
            while (v >= 128) {
                out.add((byte) ((v & 127) | 128));
                v >>= 7;
            }
            out.add((byte) (v & 127));
        }
    }

    private static final class ByteArray {
        private byte[] buf = new byte[64];
        private int size;

        void add(byte b) {
            if (size == buf.length) {
                byte[] next = new byte[buf.length * 2];
                System.arraycopy(buf, 0, next, 0, size);
                buf = next;
            }
            buf[size++] = b;
        }

        void add(byte[] data) {
            for (byte b : data) add(b);
        }

        byte[] toArray() {
            byte[] out = new byte[size];
            System.arraycopy(buf, 0, out, 0, size);
            return out;
        }
    }

    // ------------------------------------------------------------ SM3

    private static final int[] SM3_IV = {
            0x7380166F, 0x4914B2B9, 0x172442D7, 0xDA8A0600,
            0xA96F30BC, 0x163138AA, 0xE38DEE4D, 0xB0FB0E4E};

    private static int sm3Rotl(int a, int k) {
        k %= 32;
        return k == 0 ? a : Integer.rotateLeft(a, k);
    }

    private static int sm3Ff(int x, int y, int z, int j) {
        return j < 16 ? (x ^ y ^ z) : ((x & y) | (x & z) | (y & z));
    }

    private static int sm3Gg(int x, int y, int z, int j) {
        return j < 16 ? (x ^ y ^ z) : ((x & y) | ((~x) & z));
    }

    private static int sm3P0(int x) {
        return x ^ sm3Rotl(x, 9) ^ sm3Rotl(x, 17);
    }

    private static int sm3P1(int x) {
        return x ^ sm3Rotl(x, 15) ^ sm3Rotl(x, 23);
    }

    private static int[] sm3Cf(int[] v, byte[] block) {
        int[] w = new int[68];
        for (int i = 0; i < 16; i++) {
            w[i] = ((block[i * 4] & 0xFF) << 24) | ((block[i * 4 + 1] & 0xFF) << 16)
                    | ((block[i * 4 + 2] & 0xFF) << 8) | (block[i * 4 + 3] & 0xFF);
        }
        for (int j = 16; j < 68; j++) {
            w[j] = sm3P1(w[j - 16] ^ w[j - 9] ^ sm3Rotl(w[j - 3], 15))
                    ^ sm3Rotl(w[j - 13], 7) ^ w[j - 6];
        }
        int[] w1 = new int[64];
        for (int j = 0; j < 64; j++) w1[j] = w[j] ^ w[j + 4];
        int a = v[0], b = v[1], c = v[2], d = v[3];
        int e = v[4], f = v[5], g = v[6], h = v[7];
        for (int j = 0; j < 64; j++) {
            int tj = j < 16 ? 0x79CC4519 : 0x7A879D8A;
            int ss1 = sm3Rotl(Integer.rotateLeft(a, 12) + e + sm3Rotl(tj, j), 7);
            int ss2 = ss1 ^ Integer.rotateLeft(a, 12);
            int tt1 = sm3Ff(a, b, c, j) + d + ss2 + w1[j];
            int tt2 = sm3Gg(e, f, g, j) + h + ss1 + w[j];
            d = c;
            c = Integer.rotateLeft(b, 9);
            b = a;
            a = tt1;
            h = g;
            g = Integer.rotateLeft(f, 19);
            f = e;
            e = sm3P0(tt2);
        }
        return new int[]{a ^ v[0], b ^ v[1], c ^ v[2], d ^ v[3],
                e ^ v[4], f ^ v[5], g ^ v[6], h ^ v[7]};
    }

    /** 32-byte SM3 digest. */
    public static byte[] sm3Hash(byte[] msg) {
        int origLen = msg.length;
        ByteArray padded = new ByteArray();
        padded.add(msg);
        padded.add((byte) 0x80);
        int reserve = (origLen % 64) + 1;
        int rangeEnd = 56;
        if (rangeEnd < reserve) rangeEnd += 64;
        for (int i = 0; i < rangeEnd - reserve; i++) padded.add((byte) 0);
        long bits = (long) origLen * 8;
        for (int i = 7; i >= 0; i--) padded.add((byte) (bits >> (i * 8)));
        byte[] data = padded.toArray();
        int[] v = SM3_IV.clone();
        for (int i = 0; i < data.length / 64; i++) {
            byte[] block = new byte[64];
            System.arraycopy(data, i * 64, block, 0, 64);
            v = sm3Cf(v, block);
        }
        byte[] out = new byte[32];
        for (int i = 0; i < 8; i++) {
            out[i * 4] = (byte) (v[i] >> 24);
            out[i * 4 + 1] = (byte) (v[i] >> 16);
            out[i * 4 + 2] = (byte) (v[i] >> 8);
            out[i * 4 + 3] = (byte) v[i];
        }
        return out;
    }

    // ------------------------------------------------------------ Simon

    private static final long SIMON_Z = 0x3DC94C3A046D678BL;

    private static void simonKeyExpansion(long[] key) {
        for (int i = 4; i < 72; i++) {
            long tmp = Long.rotateRight(key[i - 1], 3);
            tmp ^= key[i - 3];
            tmp ^= Long.rotateRight(tmp, 1);
            long notVal = ~key[i - 4];
            long bitVal = (SIMON_Z >> ((i - 4) % 62)) & 1;
            key[i] = notVal ^ tmp ^ bitVal ^ 3;
        }
    }

    /** Simon 128/64, 72 rounds. pt/k are pairs of u64 words (little-endian). */
    public static long[] simonEnc(long[] pt, long[] k) {
        long[] key = new long[72];
        System.arraycopy(k, 0, key, 0, 4);
        simonKeyExpansion(key);
        long xi = pt[0];
        long xi1 = pt[1];
        for (long roundKey : key) {
            long tmp = xi1;
            long f = Long.rotateLeft(xi1, 1) & Long.rotateLeft(xi1, 8);
            xi1 = xi ^ f ^ Long.rotateLeft(xi1, 2) ^ roundKey;
            xi = tmp;
        }
        return new long[]{xi, xi1};
    }

    // ------------------------------------------------------------ Ladon

    private static byte[] ladonExpandHashTable(String md5Hex) {
        byte[] ht = new byte[288];
        byte[] hexBytes = md5Hex.getBytes(StandardCharsets.US_ASCII);
        int prefixLen = Math.min(32, hexBytes.length);
        System.arraycopy(hexBytes, 0, ht, 0, prefixLen);
        List<Long> temp = new ArrayList<>();
        for (int i = 0; i < 4; i++) temp.add(loadLe64(ht, i * 8));
        long bufB0 = temp.get(0);
        long bufB8 = temp.get(1);
        temp.remove(0);
        temp.remove(0);
        for (int i = 0; i < 34; i++) {
            long x9 = bufB0;
            long x8 = bufB8;
            x8 = Long.rotateRight(x8, 8);
            x8 = x8 + x9;
            x8 ^= i;
            temp.add(x8);
            long rorX9 = Long.rotateLeft(x9, 3);
            x8 ^= rorX9;
            for (int j = 0; j < 8; j++) {
                ht[(i + 1) * 8 + j] = (byte) ((x8 >> (j * 8)) & 255);
            }
            bufB0 = x8;
            bufB8 = temp.remove(0);
        }
        return ht;
    }

    private static long loadLe64(byte[] ht, int off) {
        long v = 0;
        for (int j = 0; j < 8; j++) v |= (long) (ht[off + j] & 0xFF) << (j * 8);
        return v;
    }

    private static byte[] ladonEncryptBlock(byte[] ht, byte[] block) {
        long data0 = 0;
        long data1 = 0;
        for (int i = 0; i < 8; i++) {
            data0 |= (long) (block[i] & 0xFF) << (i * 8);
            data1 |= (long) (block[8 + i] & 0xFF) << (i * 8);
        }
        for (int i = 0; i < 34; i++) {
            long hash = 0;
            for (int j = 0; j < 8; j++) hash |= (long) (ht[i * 8 + j] & 0xFF) << (j * 8);
            long rotated = Long.rotateRight(data1, 8);
            long sum = data0 + rotated;
            data1 = hash ^ sum;
            long rotated0 = Long.rotateLeft(data0, 3);
            data0 = data1 ^ rotated0;
        }
        byte[] out = new byte[16];
        for (int i = 0; i < 8; i++) {
            out[i] = (byte) ((data0 >> (i * 8)) & 255);
            out[8 + i] = (byte) ((data1 >> (i * 8)) & 255);
        }
        return out;
    }

    public static byte[] ladonEncryptRaw(String md5Hex, byte[] data) {
        byte[] ht = ladonExpandHashTable(md5Hex);
        int size = data.length;
        int newSize = size + (16 - size % 16);
        int padVal = newSize - size;
        byte[] input = new byte[newSize];
        for (int i = 0; i < newSize; i++) input[i] = (byte) padVal;
        System.arraycopy(data, 0, input, 0, size);
        ByteArray out = new ByteArray();
        for (int i = 0; i < newSize / 16; i++) {
            byte[] block = new byte[16];
            System.arraycopy(input, i * 16, block, 0, 16);
            out.add(ladonEncryptBlock(ht, block));
        }
        return out.toArray();
    }

    /** x-ladon value from khronos, license id, aid and 4 random bytes. */
    public static String ladonEncrypt(long khronos, long lcId, long aid, byte[] randomBytes) {
        String data = khronos + "-" + lcId + "-" + aid;
        ByteArray keygen = new ByteArray();
        keygen.add(randomBytes);
        keygen.add(Long.toString(aid).getBytes(StandardCharsets.US_ASCII));
        String md5Hex = md5Hex(keygen.toArray());
        byte[] encrypted = ladonEncryptRaw(md5Hex, data.getBytes(StandardCharsets.US_ASCII));
        ByteArray out = new ByteArray();
        out.add(randomBytes);
        out.add(encrypted);
        return Base64.getEncoder().encodeToString(out.toArray());
    }

    // ------------------------------------------------------------ helpers

    private static String md5Hex(byte[] data) {
        try {
            return hexEncode(MessageDigest.getInstance("MD5").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] aesCbcEncryptNoPad(byte[] data, byte[] key, byte[] iv) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(iv));
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] aesCbcDecryptNoPad(byte[] data, byte[] key, byte[] iv) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(iv));
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] pkcs7Align(byte[] data) {
        int padLen = 16 - (data.length % 16);
        byte[] out = new byte[data.length + padLen];
        System.arraycopy(data, 0, out, 0, data.length);
        for (int i = data.length; i < out.length; i++) out[i] = (byte) padLen;
        return out;
    }

    // ------------------------------------------------------------ Argus

    private static byte[] sm3Prefix6(byte[] msg) {
        byte[] h = sm3Hash(msg);
        byte[] out = new byte[6];
        System.arraycopy(h, 0, out, 0, 6);
        return out;
    }

    /** getBodyHash(stub): first 6 bytes of SM3 over the MD5-decoded stub. */
    public static byte[] argusBodyHash(String stubHex) {
        if (stubHex == null || stubHex.isEmpty()) return sm3Prefix6(new byte[16]);
        return sm3Prefix6(hex(stubHex));
    }

    /** getQueryHash(query): first 6 bytes of SM3 over the raw query string. */
    public static byte[] argusQueryHash(String query) {
        if (query == null || query.isEmpty()) return sm3Prefix6(new byte[16]);
        return sm3Prefix6(query.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] argusEncryptEncPb(byte[] data, int length) {
        byte[] buf = new byte[length];
        int n = Math.min(data.length, length);
        System.arraycopy(data, 0, buf, 0, n);
        byte[] xorKey = new byte[8];
        System.arraycopy(data, 0, xorKey, 0, Math.min(8, data.length));
        for (int i = 8; i < length; i++) {
            int src = i < data.length ? (data[i] & 0xFF) : 0;
            buf[i] = (byte) (src ^ (xorKey[i % 8] & 0xFF));
        }
        for (int i = 0; i < buf.length / 2; i++) {
            byte t = buf[i];
            buf[i] = buf[buf.length - 1 - i];
            buf[buf.length - 1 - i] = t;
        }
        return buf;
    }

    private static long[] simonKeyList() {
        long[] out = new long[4];
        for (int i = 0; i < 4; i++) {
            long v = 0;
            for (int j = 0; j < 8; j++) {
                v |= (long) (ARGUS_SIGN_KEY[i * 8 + j] & 0xFF) << (j * 8);
            }
            out[i] = v;
        }
        return out;
    }

    private static byte[] simonEncryptPadded(byte[] protobuf) {
        long[] key = simonKeyList();
        byte[] enc = new byte[protobuf.length];
        for (int i = 0; i < protobuf.length / 16; i++) {
            byte[] block = new byte[16];
            System.arraycopy(protobuf, i * 16, block, 0, 16);
            long a = 0;
            long b = 0;
            for (int j = 0; j < 8; j++) {
                a |= (long) (block[j] & 0xFF) << (j * 8);
                b |= (long) (block[8 + j] & 0xFF) << (j * 8);
            }
            long[] ct = simonEnc(new long[]{a, b}, key);
            for (int j = 0; j < 8; j++) {
                enc[i * 16 + j] = (byte) ((ct[0] >> (j * 8)) & 255);
                enc[i * 16 + 8 + j] = (byte) ((ct[1] >> (j * 8)) & 255);
            }
        }
        return enc;
    }

    private static final byte[] ARGUS_551_PREFIX = {(byte) 0xF2, (byte) 0x81};
    private static final byte[] ARGUS_551_MAGIC8 = {
            (byte) 0xF2, (byte) 0xF7, (byte) 0xFC, (byte) 0xFF,
            (byte) 0xF2, (byte) 0xF7, (byte) 0xFC, (byte) 0xFF};
    private static final byte[] ARGUS_551_TAIL9 = {
            (byte) 0xEA, (byte) 0xFB, (byte) 0x2C, (byte) 0xFE, (byte) 0x85,
            (byte) 0x68, (byte) 0x51, (byte) 0x91, (byte) 0x54};

    /** The x-argus 551 wrapper accepted by the real fanqie upstream. */
    public static String argusEncrypt551(ProtoBuf bean) {
        byte[] protobuf = pkcs7Align(bean.toBuf());
        byte[] encPb = simonEncryptPadded(protobuf);
        ByteArray un = new ByteArray();
        un.add(ARGUS_551_MAGIC8);
        un.add(encPb);
        un.add(ARGUS_551_TAIL9);
        byte[] unBytes = un.toArray();
        byte[] buf = new byte[unBytes.length];
        System.arraycopy(ARGUS_551_MAGIC8, 0, buf, 0, 8);
        for (int i = 8; i < unBytes.length; i++) {
            buf[i] = (byte) ((unBytes[i] & 0xFF) ^ (ARGUS_551_MAGIC8[i % 8] & 0xFF));
        }
        for (int i = 0; i < buf.length / 2; i++) {
            byte t = buf[i];
            buf[i] = buf[buf.length - 1 - i];
            buf[buf.length - 1 - i] = t;
        }
        ByteArray framed = new ByteArray();
        framed.add(buf);
        framed.add((byte) 'a');
        framed.add((byte) 'o');
        byte[] out = aesCbcEncryptNoPad(pkcs7Align(framed.toArray()), ARGUS_AES_KEY, ARGUS_AES_IV);
        ByteArray raw = new ByteArray();
        raw.add(ARGUS_551_PREFIX);
        raw.add(out);
        return Base64.getEncoder().encodeToString(raw.toArray());
    }

    /** Legacy (PHP55) encryption path; kept for parity, not used live. */
    public static String argusEncrypt(ProtoBuf bean) {
        byte[] protobuf = pkcs7Align(bean.toBuf());
        int newLen = protobuf.length;
        byte[] encPb = simonEncryptPadded(protobuf);
        ByteArray bBuffer = new ByteArray();
        bBuffer.add(argusEncryptEncPb(encPb, newLen + 8));
        bBuffer.add((byte) 'a');
        bBuffer.add((byte) 'o');
        byte[] out = aesCbcEncryptNoPad(pkcs7Align(bBuffer.toArray()), ARGUS_AES_KEY, ARGUS_AES_IV);
        return Base64.getEncoder().encodeToString(out);
    }

    /**
     * Build the x-argus bean (551 layout: fields 8-11 plus 551 framing).
     * randField is a per-call nonce; 0 means "pick a fresh random one".
     */
    public static String argusGetSign(String queryString, String stubHex, long timestamp,
                                      long aid, long licenseId, String deviceId,
                                      String installId, int randField) {
        if (aid == 0) aid = DEFAULT_AID;
        if (licenseId == 0) licenseId = LICENSE_ID;
        if (randField == 0) randField = new SecureRandom().nextInt();
        ProtoBuf bean = new ProtoBuf();
        bean.putVarint(1, 1077940818L);
        bean.putVarint(2, 2);
        bean.putVarint(3, randField);
        bean.putUtf8(4, Long.toString(aid));
        bean.putUtf8(5, deviceId == null ? "" : deviceId);
        bean.putUtf8(6, Long.toString(licenseId));
        bean.putUtf8(7, queryParam(queryString, "version_name"));
        bean.putUtf8(8, "v04.04.05-ov-android");
        bean.putVarint(9, 134744640L);
        bean.putBytes(10, new byte[8]);
        bean.putVarint(11, 0);
        bean.putVarint(12, timestamp << 1);
        bean.putBytes(13, argusBodyHash(stubHex));
        bean.putBytes(14, argusQueryHash(queryString));
        ProtoBuf sub15 = new ProtoBuf();
        sub15.putVarint(1, 1);
        sub15.putVarint(2, 1);
        sub15.putVarint(3, 1);
        sub15.putVarint(7, 0xC792ECCCL);
        bean.putProtobuf(15, sub15);
        bean.putUtf8(16, installId == null ? "" : installId);
        bean.putUtf8(20, "none");
        bean.putVarint(21, 738);
        ProtoBuf sub23 = new ProtoBuf();
        sub23.putUtf8(1, "NX551J");
        sub23.putVarint(2, 8196);
        sub23.putVarint(4, 0x80E0D800L);
        bean.putProtobuf(23, sub23);
        bean.putVarint(25, 2);
        return argusEncrypt551(bean);
    }

    /** Go's url.Values.Get on a raw query string. */
    public static String queryParam(String queryString, String name) {
        if (queryString == null || queryString.isEmpty()) return "";
        for (String pair : queryString.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                k = java.net.URLDecoder.decode(k, "UTF-8");
                v = java.net.URLDecoder.decode(v, "UTF-8");
            } catch (Exception ignored) {
            }
            if (k.equals(name)) return v;
        }
        return "";
    }

    /**
     * Full signing header set for one request (mirrors Rust Manager).
     * postData null/empty for GET. Returns insertion-ordered headers.
     */
    public static Map<String, String> generateHeaders(String queryString, byte[] postData) {
        return generateHeadersAt(queryString, postData,
                System.currentTimeMillis() / 1000, null, 0);
    }

    /** Deterministic variant: caller supplies ts, Ladon random bytes, Argus nonce. */
    public static Map<String, String> generateHeadersAt(String queryString, byte[] postData,
                                                         long ts, byte[] ladonRandom, int argusRand) {
        long aid = DEFAULT_AID;
        String aidStr = queryParam(queryString, "aid");
        if (!aidStr.isEmpty()) {
            try {
                aid = Long.parseLong(aidStr);
            } catch (NumberFormatException ignored) {
            }
        }
        String stubHex = "";
        if (postData != null && postData.length > 0) stubHex = md5Hex(postData);
        if (ladonRandom == null) {
            ladonRandom = new byte[4];
            new SecureRandom().nextBytes(ladonRandom);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-ss-req-ticket", Long.toString(ts * 1000));
        headers.put("x-khronos", Long.toString(ts));
        headers.put("x-ladon", ladonEncrypt(ts, LICENSE_ID, aid, ladonRandom));
        headers.put("x-argus", argusGetSign(queryString, stubHex, ts, aid, LICENSE_ID,
                queryParam(queryString, "device_id"), "", argusRand));
        if (!stubHex.isEmpty()) headers.put("x-ss-stub", stubHex);
        return headers;
    }

    // ------------------------------------------------------------ spade URL

    private static final byte[] SPADE_CONSTANTS = {
            77, (byte) 212, (byte) 194, (byte) 230, (byte) 184, 49, 98, 9, 14, 82,
            (byte) 179, (byte) 199, (byte) 166, 115, 59, (byte) 164, 28, (byte) 178, 70, 43,
            (byte) 130, (byte) 154, (byte) 181, (byte) 138, 25, 107, 57, (byte) 219, 87, 23,
            117, 36, (byte) 244, (byte) 155, (byte) 175, 127, 8, (byte) 232, (byte) 214,
            (byte) 141, 38, (byte) 167, 46, 55, (byte) 193, (byte) 169, 90, 47, 31, 5,
            (byte) 165, 24, (byte) 146, (byte) 174, (byte) 242, (byte) 148, (byte) 151, 50,
            (byte) 182, 42, 56, (byte) 170, (byte) 221, 88};

    private static byte[] audioB64Decode(String s) {
        String cleaned = s.trim().replace("\r", "").replace("\n", "");
        int pad = (4 - cleaned.length() % 4) % 4;
        StringBuilder sb = new StringBuilder(cleaned);
        for (int i = 0; i < pad; i++) sb.append('=');
        return Base64.getDecoder().decode(sb.toString());
    }

    private static byte[] sha512(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-512").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] pkcs7Unpad(byte[] data) {
        if (data.length == 0) return data;
        int pad = data[data.length - 1] & 0xFF;
        if (pad < 1 || pad > 16 || pad > data.length) return data;
        byte[] out = new byte[data.length - pad];
        System.arraycopy(data, 0, out, 0, out.length);
        return out;
    }

    /**
     * Decrypt a spade-wrapped video URL (base64 blob starting a8 xx 01 00).
     * Plaintext base64 URLs pass through untouched; non-base64 input is
     * returned as-is. Mirrors Rust video.rs decrypt_spade_url.
     */
    public static String decryptSpadeUrl(String value, byte[] keySeed) {
        if (value == null || value.isEmpty() || keySeed == null) return value == null ? "" : value;
        byte[] raw;
        try {
            raw = audioB64Decode(value);
        } catch (Exception e) {
            return value;
        }
        if (raw.length < 5 || (raw[0] & 0xFF) != 168 || (raw[2] & 0xFF) != 1 || raw[3] != 0) {
            return value;
        }
        int cipherLen = (raw.length - 4) / 16 * 16;
        if (cipherLen <= 0) return value;
        byte[] cipherData = new byte[cipherLen];
        System.arraycopy(raw, 4, cipherData, 0, cipherLen);
        byte[] h1 = sha512(keySeed);
        ByteArray seed = new ByteArray();
        seed.add(h1);
        seed.add(SPADE_CONSTANTS);
        byte[] h2 = sha512(seed.toArray());
        byte[] key = new byte[16];
        byte[] iv = new byte[16];
        System.arraycopy(h2, 0, key, 0, 16);
        System.arraycopy(h2, 16, iv, 0, 16);
        byte[] plain = pkcs7Unpad(aesCbcDecryptNoPad(cipherData, key, iv));
        String out = new String(plain, StandardCharsets.UTF_8);
        int end = out.length();
        while (end > 0 && out.charAt(end - 1) == '\0') end--;
        return out.substring(0, end);
    }

    /**
     * One video_list entry to a playable URL, mirroring Rust
     * decrypt_variant_entry: base64-wrapped plaintext first, spade second,
     * backup_url_1 as fallback.
     */
    public static String resolveVariantUrl(String mainUrl, String backupUrl, byte[] keySeed) {
        String raw = mainUrl == null ? "" : mainUrl;
        if (raw.isEmpty()) raw = backupUrl == null ? "" : backupUrl;
        if (raw.isEmpty()) return "";
        if (!raw.startsWith("http")) {
            try {
                byte[] dec = audioB64Decode(raw);
                String asText = new String(dec, StandardCharsets.UTF_8);
                if (asText.startsWith("http")) raw = asText;
            } catch (Exception ignored) {
            }
        }
        return decryptSpadeUrl(raw, keySeed);
    }
}
