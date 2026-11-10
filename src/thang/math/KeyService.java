package thang.math;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayList;
import java.util.List;

/**
 * Dịch vụ trợ năng: nhận phím điều khiển trên toàn hệ thống (gán phím mở app) và, khi không có ADB, buộc dừng ứng dụng
 * bằng cách mở màn hình Thông tin ứng dụng rồi bấm "Buộc dừng" (chỉ cho nút "Buộc dừng toàn bộ"). Không lưu hay gửi nội dung
 * màn hình đi đâu.
 */
public class KeyService extends AccessibilityService {
    static KeyService inst; // dịch vụ đang chạy (null = chưa bật quyền trợ năng)

    @Override
    protected void onServiceConnected() {
        inst = this;
    }

    @Override
    public boolean onUnbind(Intent i) {
        inst = null;
        return super.onUnbind(i);
    }

    static final String[] STOP_LABELS = {"Force stop", "Buộc dừng", "Dừng buộc"};
    static final String[] OK_LABELS = {"OK", "Có", "Đồng ý", "Force stop", "Buộc dừng"};

    /**
     * Buộc dừng từng gói bằng giao diện: mở Thông tin ứng dụng, bấm "Buộc dừng" (nút mờ = app không chạy thì bỏ qua), xác nhận
     * rồi Back. Chạy tuần tự trên luồng nền, dừng ngay khi gen đổi (người dùng đã mở việc khác). Trả về số app đã dừng.
     */
    static int forceStop(Context ctx, List<String> pkgs, int gen) {
        KeyService s = inst;
        int n = 0;
        for (String p : pkgs) {
            if (s == null || inst == null || gen != Bar.killGen) break;
            try {
                ctx.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + p))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable t) {
                continue;
            }
            AccessibilityNodeInfo stop = clickable(waitNode(s, STOP_LABELS, 3000));
            if (stop != null && stop.isEnabled()) {
                stop.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                AccessibilityNodeInfo ok = clickable(waitNode(s, OK_LABELS, 1500));
                if (ok != null) {
                    ok.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    n++;
                }
                SystemClock.sleep(300);
            }
            s.performGlobalAction(GLOBAL_ACTION_BACK);
            SystemClock.sleep(250);
        }
        return n;
    }

    /** Chờ tối đa ms để trên màn hình đang hiện có chữ khớp hẳn một trong labels (không phân biệt hoa thường). */
    static AccessibilityNodeInfo waitNode(KeyService s, String[] labels, long ms) {
        long end = SystemClock.uptimeMillis() + ms;
        do {
            for (AccessibilityNodeInfo root : roots(s)) {
                AccessibilityNodeInfo hit = match(root, labels);
                if (hit != null) return hit;
            }
            SystemClock.sleep(150);
        } while (SystemClock.uptimeMillis() < end);
        return null;
    }

    /** Cửa sổ đang hoạt động và các cửa sổ khác (hộp thoại nguồn của hệ thống là cửa sổ riêng). */
    static List<AccessibilityNodeInfo> roots(KeyService s) {
        List<AccessibilityNodeInfo> l = new ArrayList<AccessibilityNodeInfo>();
        AccessibilityNodeInfo r = s.getRootInActiveWindow();
        if (r != null) l.add(r);
        try {
            List<AccessibilityWindowInfo> ws = s.getWindows();
            if (ws != null) {
                for (AccessibilityWindowInfo w : ws) {
                    AccessibilityNodeInfo wr = w.getRoot();
                    if (wr != null && !l.contains(wr)) l.add(wr);
                }
            }
        } catch (Throwable ignored) {}
        return l;
    }

    static final String[] OFF_LABELS = {"Power off", "Shut down", "Tắt nguồn", "Tắt máy"};
    static final String[] RESTART_LABELS = {"Restart", "Reboot", "Khởi động lại"};
    static final String[] CONFIRM_LABELS = {"OK", "Có", "Đồng ý", "Power off", "Tắt nguồn", "Restart", "Khởi động lại"};

    /**
     * Tắt hẳn nguồn (reboot=false, không phải chế độ chờ) hoặc khởi động lại bằng hộp thoại nguồn của hệ thống
     * (GLOBAL_ACTION_POWER_DIALOG = 6): mở hộp thoại, bấm "Tắt nguồn"/"Khởi động lại" rồi xác nhận nếu có.
     * Chạy trên luồng nền. false nếu chưa bật dịch vụ hoặc không thấy nút (TV không có hộp thoại nguồn).
     */
    static boolean powerAction(boolean reboot) {
        KeyService s = inst;
        if (s == null || !s.performGlobalAction(6)) return false;
        AccessibilityNodeInfo item = clickable(waitNode(s, reboot ? RESTART_LABELS : OFF_LABELS, 3000));
        if (item == null) return false;
        item.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        AccessibilityNodeInfo ok = clickable(waitNode(s, CONFIRM_LABELS, 1500));
        if (ok != null) ok.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        return true;
    }

    /** Nút/dòng đầu tiên trên màn hình có chữ khớp hẳn một trong labels (theo thứ tự labels), không phân biệt hoa thường. */
    static AccessibilityNodeInfo match(AccessibilityNodeInfo root, String[] labels) {
        if (root == null) return null;
        for (String l : labels) {
            List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(l);
            if (found == null) continue;
            for (AccessibilityNodeInfo nd : found) {
                CharSequence t = nd.getText();
                if (t != null && t.toString().trim().equalsIgnoreCase(l)) return nd;
            }
        }
        return null;
    }

    static final String[] DEBUG_LABELS = {"Gỡ lỗi qua Wi-Fi", "Gỡ lỗi qua USB", "Gỡ lỗi không dây", "Gỡ lỗi USB", "Wireless debugging", "USB debugging"};

    /**
     * Sau khi mở Tùy chọn nhà phát triển: cuộn xuống tìm dòng "Gỡ lỗi qua Wi-Fi" hoặc "Gỡ lỗi qua USB" rồi đưa focus tới đó.
     * Chạy nền, bỏ cuộc sau khoảng 10 giây nếu không thấy (vd. chưa bật chế độ nhà phát triển).
     */
    static void focusDebugging(final Context ctx) {
        final KeyService s = inst;
        if (s == null) return;
        new Thread(new Runnable() {
            public void run() {
                SystemClock.sleep(900); // chờ màn hình Cài đặt mở
                for (int i = 0; i < 25 && inst != null; i++) {
                    AccessibilityNodeInfo root = s.getRootInActiveWindow();
                    if (root != null && !ctx.getPackageName().contentEquals(root.getPackageName())) { // đang ở màn hình Cài đặt
                        AccessibilityNodeInfo row = match(root, DEBUG_LABELS);
                        for (int j = 0; row != null && j < 6; j++, row = row.getParent()) {
                            if (row.isFocusable() && row.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) return;
                        }
                        AccessibilityNodeInfo list = scrollable(root);
                        if (list != null) list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                    }
                    SystemClock.sleep(400);
                }
            }
        }).start();
    }

    /** Danh sách cuộn được đầu tiên trong cây giao diện. */
    static AccessibilityNodeInfo scrollable(AccessibilityNodeInfo n) {
        if (n == null) return null;
        if (n.isScrollable()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = scrollable(n.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    /** Nút bấm được chứa nd (chính nó hoặc view cha gần nhất). */
    static AccessibilityNodeInfo clickable(AccessibilityNodeInfo nd) {
        for (int i = 0; nd != null && i < 6; i++, nd = nd.getParent()) if (nd.isClickable()) return nd;
        return null;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {}

    @Override
    public void onInterrupt() {}

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        return Bar.get(this).onGlobalKey(e);
    }
}
