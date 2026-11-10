package thang.math;

import android.util.Base64;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import javax.crypto.Cipher;

/** Máy khách ADB tối giản (giao thức TCP, có xác thực RSA), không cần thư viện ngoài. */
public class AdbClient {

    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;

    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;

    private static final int VERSION = 0x01000000;
    private static final int MAXDATA = 4096;

    private static class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private final File keyDir;
    private KeyPair keyPair;
    private Socket socket;
    private DataInputStream in;
    private OutputStream out;

    public AdbClient(File keyDir) {
        this.keyDir = keyDir;
    }

    public void connect(String host, int port) throws Exception {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 3000);
        socket.setTcpNoDelay(true);
        // Chờ lâu để người dùng kịp bấm "Cho phép" trên TV ở lần đầu
        socket.setSoTimeout(60000);
        in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        out = socket.getOutputStream();

        loadKey();
        send(A_CNXN, VERSION, MAXDATA, "host::\0".getBytes("UTF-8"));

        boolean signed = false, pubSent = false;
        while (true) {
            Msg m = read();
            if (m.cmd == A_CNXN) break;
            if (m.cmd == A_AUTH && m.arg0 == AUTH_TOKEN) {
                if (!signed) {
                    send(A_AUTH, AUTH_SIGNATURE, 0, sign(m.data));
                    signed = true;
                } else if (!pubSent) {
                    send(A_AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyBytes());
                    pubSent = true;
                } else {
                    throw new IOException("ADB từ chối xác thực");
                }
            } else {
                throw new IOException("Phản hồi ADB không hợp lệ");
            }
        }
        socket.setSoTimeout(8000);
    }

    /** Đổi thời gian chờ mỗi lần đọc (mặc định 8 giây), dùng cho lệnh chạy lâu như cài APK. */
    public void setTimeout(int ms) throws IOException {
        socket.setSoTimeout(ms);
    }

    /** Chạy 1 lệnh shell trên thiết bị, trả về nội dung lệnh in ra (rỗng nếu máy tắt/ngắt kết nối ngay sau khi nhận lệnh). */
    private int localId; // id luồng cục bộ, tăng dần mỗi lệnh (dùng lại 1 kết nối cho nhiều lệnh)

    public String shell(String cmd) throws Exception {
        final int lid = ++localId;
        send(A_OPEN, lid, 0, ("shell:" + cmd + "\0").getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        try {
            while (true) {
                Msg m = read();
                if (m.cmd == A_WRTE) {
                    sb.append(new String(m.data, "UTF-8"));
                    send(A_OKAY, lid, m.arg0, null);
                } else if (m.cmd == A_CLSE) {
                    break;
                }
            }
        } catch (IOException e) {
            // TV đã tắt/ngắt mạng sau khi nhận lệnh: coi như thành công
        }
        return sb.toString();
    }

    public void close() {
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        }
    }

    // ---------- giao thức ----------

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length;
        int sum = 0;
        for (int i = 0; i < len; i++) sum += (data[i] & 0xff);
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(len).putInt(sum).putInt(cmd ^ 0xffffffff);
        out.write(h.array());
        if (len > 0) out.write(data);
        out.flush();
    }

    private Msg read() throws IOException {
        byte[] hb = new byte[24];
        in.readFully(hb);
        ByteBuffer h = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = h.getInt();
        m.arg0 = h.getInt();
        m.arg1 = h.getInt();
        int len = h.getInt();
        if (len < 0 || len > 1024 * 1024) throw new IOException("Gói tin quá lớn");
        m.data = new byte[len];
        if (len > 0) in.readFully(m.data);
        return m;
    }

    // ---------- khóa RSA ----------

    private void loadKey() throws Exception {
        File pf = new File(keyDir, "adbkey.priv");
        File uf = new File(keyDir, "adbkey.pub");
        if (pf.exists() && uf.exists()) {
            try {
                KeyFactory kf = KeyFactory.getInstance("RSA");
                PrivateKey pr = kf.generatePrivate(new PKCS8EncodedKeySpec(readAll(pf)));
                PublicKey pu = kf.generatePublic(new X509EncodedKeySpec(readAll(uf)));
                keyPair = new KeyPair(pu, pr);
                return;
            } catch (Exception ignored) {
            }
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        keyPair = g.generateKeyPair();
        writeAll(uf, keyPair.getPublic().getEncoded());
        writeAll(pf, keyPair.getPrivate().getEncoded());
    }

    private byte[] sign(byte[] token) throws Exception {
        // DigestInfo của SHA-1; token ADB (20 byte) được coi là đã băm sẵn
        byte[] prefix = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
                0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};
        byte[] padded = new byte[256];
        int dataLen = prefix.length + token.length;
        int psLen = 256 - 3 - dataLen;
        padded[0] = 0;
        padded[1] = 1;
        for (int i = 0; i < psLen; i++) padded[2 + i] = (byte) 0xff;
        padded[2 + psLen] = 0;
        System.arraycopy(prefix, 0, padded, 3 + psLen, prefix.length);
        System.arraycopy(token, 0, padded, 3 + psLen + prefix.length, token.length);
        Cipher c = Cipher.getInstance("RSA/ECB/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, keyPair.getPrivate());
        return c.doFinal(padded);
    }

    /** Khóa công khai theo định dạng riêng của Android (RSAPublicKey struct) + base64. */
    private byte[] publicKeyBytes() throws Exception {
        RSAPublicKey pk = (RSAPublicKey) keyPair.getPublic();
        BigInteger n = pk.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        int n0inv = n.mod(r32).modInverse(r32).negate().intValue();
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);

        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64);
        b.putInt(n0inv);
        b.put(le(n, 256));
        b.put(le(rr, 256));
        b.putInt(pk.getPublicExponent().intValue());
        String s = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " thangmath@off\0";
        return s.getBytes("UTF-8");
    }

    private static byte[] le(BigInteger v, int len) {
        byte[] be = v.toByteArray();
        byte[] r = new byte[len];
        for (int i = 0; i < len; i++) {
            int j = be.length - 1 - i;
            r[i] = j >= 0 ? be[j] : 0;
        }
        return r;
    }

    private static byte[] readAll(File f) throws IOException {
        FileInputStream is = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            is.close();
        }
    }

    private static void writeAll(File f, byte[] d) throws IOException {
        FileOutputStream os = new FileOutputStream(f);
        try {
            os.write(d);
        } finally {
            os.close();
        }
    }
}
