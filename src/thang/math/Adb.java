package thang.math;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Kết nối ADB tới chính TV (cổng 5555).
 * - Mỗi lần mở launcher / dọn RAM: nếu chưa kết nối được thì thử lại (cách nhau 30s); thành công thì ghi nhớ.
 * - Khi đã ghi nhớ thành công: menu giữ nút "Gỡ lỗi" (Tắt nguồn) sẽ tắt hẳn nguồn TV.
 */
public class Adb {
    static final int PORT = 5555;
    // Đưa TV vào chế độ chờ trước (TV gửi lệnh "chờ" HDMI-CEC cho thiết bị đang cắm để chúng không bật TV lại), rồi tắt hẳn
    static final String SHUTDOWN = "input keyevent 223; sleep 2; setprop sys.powerctl shutdown";
    static final String REBOOT = "setprop sys.powerctl reboot";
    // Đóng mọi tiến trình nền/cache (không đụng app đang hiện)
    static final String CLEAN = "am kill-all";
    static volatile boolean busy; // chống chạy lệnh tắt 2 lần khi giữ phím

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("adb", Context.MODE_PRIVATE);
    }

    /** Đã kết nối ADB thành công ở lần khởi động đầu tiên. */
    static boolean enabled(Context c) {
        return prefs(c).getBoolean("ok", false);
    }

    static volatile long lastTry; // lần thử kết nối gần nhất (uptime), để không thử dồn dập

    /**
     * Thử kết nối ADB và ghi nhớ nếu thành công. Chạy trên luồng nền; chưa kết nối được thì lần sau thử lại
     * (cách nhau ít nhất 30 giây), nên mở cổng 5555 muộn hơn (vd. bằng adb-auto-enable) vẫn dùng được.
     */
    static boolean ready(Context c) {
        if (enabled(c)) return true;
        long now = android.os.SystemClock.uptimeMillis();
        if (lastTry != 0 && now - lastTry < 30000) return false;
        lastTry = now;
        Context app = c.getApplicationContext();
        try {
            AdbClient a = open(app); // lần đầu TV hỏi cấp quyền gỡ lỗi: chọn "Luôn cho phép"
            a.close();
            prefs(app).edit().putBoolean("ok", true).apply();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Gọi mỗi lần mở launcher: nếu chưa kết nối ADB được thì thử lại trên luồng nền. */
    static void firstRunProbe(Context c) {
        if (enabled(c)) return;
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            public void run() { ready(app); }
        }).start();
    }

    /** Tắt nguồn TV. Gọi trên luồng nền. */
    /** Tắt hẳn nguồn (reboot=false) hoặc khởi động lại (reboot=true) qua ADB. */
    static void power(Context c, boolean reboot) throws Exception {
        if (busy) return;
        busy = true;
        try {
            AdbClient a = open(c.getApplicationContext());
            a.shell(reboot ? REBOOT : SHUTDOWN);
            a.close();
        } finally {
            busy = false;
        }
    }

    /** Dọn RAM bằng ADB nếu đã kết nối được (không thì bỏ qua). Gọi trên luồng nền. */
    static void clean(Context c) {
        if (!ready(c)) return;
        try {
            AdbClient a = open(c.getApplicationContext());
            a.shell(CLEAN);
            a.close();
        } catch (Throwable ignored) {
        }
    }

    /** Lấy tên gói từ kết quả "ps" (cột cuối mỗi dòng, bỏ phần ":tiến trình phụ"). */
    static java.util.Set<String> parsePs(String out) {
        java.util.Set<String> s = new java.util.HashSet<String>();
        if (out == null) return s;
        for (String line : out.split("\n")) {
            line = line.trim();
            int sp = Math.max(line.lastIndexOf(' '), line.lastIndexOf('\t'));
            String t = sp >= 0 ? line.substring(sp + 1) : line;
            int c = t.indexOf(':');
            if (c > 0) t = t.substring(0, c);
            if (t.indexOf('.') > 0 && t.matches("[A-Za-z0-9_.]+")) s.add(t);
        }
        return s;
    }

    /** Kết nối tới 127.0.0.1:5555, không được thì thử các địa chỉ IPv4 của TV trong mạng LAN. */
    static AdbClient open(Context c) throws Exception {
        List<String> hosts = new ArrayList<String>();
        hosts.add("127.0.0.1");
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                Enumeration<InetAddress> addrs = ifs.nextElement().getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) hosts.add(a.getHostAddress());
                }
            }
        } catch (Throwable ignored) {
        }
        Exception last = null;
        for (String h : hosts) {
            AdbClient cl = new AdbClient(c.getFilesDir());
            try {
                cl.connect(h, PORT);
                return cl;
            } catch (Exception e) {
                last = e;
                cl.close();
            }
        }
        throw last != null ? last : new java.io.IOException("Không kết nối được ADB");
    }
}
