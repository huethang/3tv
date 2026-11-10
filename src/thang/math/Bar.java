package thang.math;

import android.app.ActivityManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.net.Uri;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Thanh ứng dụng nổi (overlay) hiện đè lên mọi ứng dụng.
 * Luôn chỉ hiện 1 hàng: hàng 0 là dock chính (app đã ghim), các hàng sau là tất cả app còn lại.
 */
public class Bar {
    static final int MIN_COLS = 5;           // số cột tối thiểu (dock trống thì chia 5 cột)
    static final int MAX_APPS = 15;          // dock chính chứa tối đa 1 hàng = 15 cột
    // Khoảng cách ở số cột tối thiểu; mỗi cột thêm vào thì co lại một chút (không nhỏ hơn mức sàn)
    static final int EDGE_DP = 36, EDGE_STEP_DP = 2, EDGE_MIN_DP = 14;    // tới 2 viền trái/phải
    static final int BOTTOM_DP = 20, BOTTOM_STEP_DP = 1, BOTTOM_MIN_DP = 8; // tới viền dưới (nhỏ hơn 2 viền bên)
    static final int GAP_DP = 18, GAP_STEP_DP = 1, GAP_MIN_DP = 8;        // giữa các ô
    static final float FOCUS_SCALE = 1.12f;  // độ phóng to khi focus
    static final int RADIUS_DP = 6;          // độ bo góc của icon
    static final long DREAM_IDLE_MS = 10000; // dock hiện mà không thao tác bao lâu thì tự vào Màn hình bảo vệ
    static final long DREAM_AFTER_OPEN_MS = 5000; // chờ app gần nhất lên rồi mới vào Màn hình bảo vệ (lúc khởi động)
    static final long CLEAN_DELAY_MS = 2500;      // chờ app mới lên tiền cảnh rồi mới dọn RAM
    static final int BAR = 0, EDIT = 1, PICK = 2, MENU = 3, FMENU = 4, MOVE = 5, ASSIGN = 6;
    static final String LEANBACK = "android.intent.category.LEANBACK_LAUNCHER";

    /** true nếu đang có app chạy phía dưới launcher (launcher trong suốt); false thì hiện nền màu ngẫu nhiên. */
    static boolean appBeneath = false;
    /** HomeActivity đã được tạo và chưa bị huỷ: Back thoát màn hình mở từ dock overlay thì lùi ra sau là rơi về app bên dưới, hết app thì về đó. */
    static volatile boolean homeExists;
    static int bgColor = randomColor();
    static volatile int killGen; // đếm yêu cầu dọn: mở app khác thì các lượt dọn cũ tự huỷ
    /** HomeActivity đang resumed (trang chủ toàn màn hình đang là màn hình trên cùng). */
    static volatile boolean launcherResumed;
    /** HomeActivity đang hiện (từ onStart tới onStop, kể cả khi bị MainActivity trong suốt che): không có app nào ở trên nó. */
    static volatile boolean homeStarted;
    static final String EXTRA_LAUNCH = "thang.math.LAUNCH", EXTRA_HOW = "thang.math.HOW", EXTRA_KEEP = "thang.math.KEEP";

    static int randomColor() {
        float[] hsv = {
            (float) (Math.random() * 360),
            0.4f + (float) Math.random() * 0.6f,
            0.3f + (float) Math.random() * 0.4f
        };
        return Color.HSVToColor(hsv);
    }

    static Bar inst;

    static Bar get(Context c) {
        if (inst == null) inst = new Bar(c.getApplicationContext());
        return inst;
    }

    Context ctx;
    PackageManager pm;
    SharedPreferences sp, usageSp, cntSp, inSp, keySp;
    WindowManager wm;
    Root root;
    ViewGroup host;                               // Activity trang chủ: nơi đặt dock khi launcher toàn màn hình
    boolean shown, inHost;                        // inHost: dock là view trong Activity (không phải cửa sổ overlay)
    int mode = BAR;
    float d;
    List<String> apps = new ArrayList<String>(); // dock chính
    Set<String> hidden = new LinkedHashSet<String>(); // app đã ẩn khỏi mọi hàng
    String settingsCache;                         // gói Cài đặt (tra 1 lần)
    boolean settingsKnown;
    List<Cand> all;                               // tất cả app trừ dock (null = cần tạo lại)
    LinearLayout curRow;                          // hàng đang hiện
    int curRowIdx;
    int menuRow, menuCol;                         // ô đang mở menu giữ (hàng danh sách tất cả)
    String assignTo;                              // đích đang chờ gán phím (chế độ ASSIGN): gói app hoặc "fx:<chức năng ô cố định>"
    int assignFrom;                               // menu để quay lại khi gán xong/huỷ: EDIT, MENU, FMENU hoặc PICK
    boolean moving;                               // chế độ Di chuyển: true = đang đổi chỗ icon, false = đang chọn icon khác
    int fmGen;                                    // đếm số lần mở menu giữ ô cố định (để biết hành động có mở menu khác không)
    Runnable[] fmLong;                            // hành động khi bấm giữ từng nút của menu ô cố định (có thể null)
    int fmDef;                                    // nút focus mặc định của menu giữ ô cố định
    int fmCol;                                    // ô của hàng cố định đang mở menu giữ
    String fmTitle;                               // menu giữ ô ở hàng cố định: tiêu đề, nhãn nút và hành động
    String[] fmLabels;
    Runnable[] fmActs;
    int editIdx;                                  // app đang chỉnh sửa (để quay lại đúng ô khi bấm Back)

    static class Cand { String pkg; String name; long last; int cnt; }

    /** 1 ô trên thanh: app thường (pkg != null) hoặc mục cố định (action != null). */
    static class Item {
        String pkg, label, glyph;
        Runnable action;
        Runnable longAction; // hành động khi giữ nút (null = không có)
        static Item app(String p) { Item i = new Item(); i.pkg = p; return i; }
    }

    /** 1 đầu vào của TV (HDMI...). */
    static class Inp { String id, label, pkg; boolean hw, device; }

    final Handler handler = new Handler(Looper.getMainLooper());
    boolean autoDream = true; // tắt hẳn tự động vào màn hình bảo vệ nếu TV không chạy được
    final Runnable dreamTask = new Runnable() {
        public void run() {
            if (shown && mode == BAR && !startDream()) autoDream = false;
        }
    };
    volatile boolean bootDreamPending; // còn chờ vào Màn hình bảo vệ sau khi mở app gần nhất lúc khởi động
    long bootDreamAt;                  // thời điểm sớm nhất được vào (uptime)
    final Runnable bootDream = new Runnable() {
        public void run() {
            if (!bootDreamPending) return;
            bootDreamPending = false;
            startDream();
        }
    };
    List<Item> fixed;                             // hàng cố định cuối cùng (null = cần tạo lại)
    List<Inp> inputs;                             // các đầu vào TV (null = cần tạo lại)
    long inputsAt;                                // lúc tạo danh sách đầu vào (cache 60 giây, đỡ hỏi TvInputManager mỗi lần dựng dock)
    List<String[]> base;                          // {gói, tên} mọi app mở được trên TV: tra PackageManager một lần cho tới khi gói thay đổi
    Map<String, Long> usageCache;                 // lần dùng gần nhất theo "dữ liệu sử dụng" (cache 60 giây)
    long usageAt;
    boolean loaded;                               // đã đọc dock/app ẩn từ bộ nhớ
    volatile boolean pkgDirty;                    // có app vừa cài/gỡ/cập nhật: bỏ cache danh sách app
    final Map<String, ResolveInfo> resolved = Collections.synchronizedMap(new HashMap<String, ResolveInfo>());
    int inputIdx = -1;                            // vị trí ô "Đầu vào" trong hàng cố định
    static final long FADE_MS = 400;              // dock mờ dần rồi mới ẩn; cũng là khoảng chờ để nhận Back đúp
    boolean backPending;                          // đã bấm Back 1 lần, đang chờ lần 2
    boolean homeFading;                           // đang mờ dần do bấm Home
    String lastApp, prevApp;                      // app mở gần nhất từ dock (đang chạy bên dưới) và app trước nó
    boolean openedApp;                            // lần mở gần nhất từ dock overlay là app (true) hay màn hình phụ như Cài đặt (false)
    String focusPkg;                              // app vừa mở từ dock: focus lại khi dock hiện lại bằng Home
    boolean restoreFocus;                         // lần dựng dock kế tiếp (do bấm Home) có focus vào focusPkg
    final Runnable backHideTask = new Runnable() {
        public void run() {
            cancelFade();
            hide(); // hide() tự bỏ qua khi launcher đang toàn màn hình
        }
    };
    final Runnable homeHideTask = new Runnable() {
        public void run() {
            hideNow(); // gồm cả cancelFade()
        }
    };

    /** Mờ dần dock trong FADE_MS rồi chạy task (ẩn dock). */
    void fadeOut(Runnable task) {
        root.animate().alpha(0f).setDuration(FADE_MS).start();
        handler.postDelayed(task, FADE_MS);
    }

    /** Huỷ lượt mờ dần / chờ Back đúp: dừng hiệu ứng, hiện lại dock đầy đủ. */
    void cancelFade() {
        handler.removeCallbacks(backHideTask);
        handler.removeCallbacks(homeHideTask);
        backPending = homeFading = false;
        if (root != null) {
            root.animate().cancel();
            root.setAlpha(1f);
        }
    }

    /**
     * Bấm Home khi đang dùng app: dock overlay đang hiện thì mờ dần rồi ẩn (bấm lại lúc đang mờ thì giữ dock), đang ẩn
     * thì hiện.
     */
    boolean homeToggle() {
        if (homeFading) { cancelFade(); return true; }
        if (shown) {
            cancelFade();
            homeFading = true;
            fadeOut(homeHideTask);
            return true;
        }
        restoreFocus = true;
        return toggle();
    }

    /** Vị trí {hàng, cột} của app pkg trong dock/danh sách tất cả, null nếu không có (đã ẩn/gỡ). */
    int[] posOf(String pkg) {
        if (pkg == null) return null;
        for (int r = 0; r < rows(); r++) {
            List<Item> l = rowItems(r);
            for (int c = 0; c < l.size(); c++) if (pkg.equals(l.get(c).pkg)) return new int[] {r, c};
        }
        return null;
    }
    boolean shutdownPending;                      // đang chờ tắt nguồn

    class Root extends LinearLayout {
        Root(Context c) { super(c); }

        @Override
        public boolean dispatchTouchEvent(MotionEvent e) {
            armDream();
            return super.dispatchTouchEvent(e);
        }

        @Override
        public boolean dispatchGenericMotionEvent(MotionEvent e) {
            armDream(); // chuột/air-mouse
            return super.dispatchGenericMotionEvent(e);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent e) {
            armDream();
            int k = e.getKeyCode();
            if (e.getAction() == KeyEvent.ACTION_DOWN && (homeFading || backPending && k != KeyEvent.KEYCODE_BACK))
                cancelFade(); // bấm phím trong lúc đang mờ: huỷ ẩn dock
            if (mode == BAR && (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN)) {
                View f = findFocus();
                if (f != null && curRow != null && f.getParent() == curRow) {
                    if (e.getAction() == KeyEvent.ACTION_DOWN)
                        moveRow(k == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1, curRow.indexOfChild(f));
                    return true;
                }
            }
            if (mode == MOVE && (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) moveIcon(k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                return true;
            }
            if (mode != BAR && k == KeyEvent.KEYCODE_DPAD_DOWN) { // thanh bấm giữ: phím xuống như Back
                if (e.getAction() == KeyEvent.ACTION_DOWN) panelBack();
                return true;
            }
            if (k == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_UP) {
                    if (mode != BAR) panelBack();
                    else if (backPending) { // Back lần 2 liền sau: dừng tất cả ứng dụng
                        cancelFade();
                        stopAll();
                    } else { // Back lần 1: chờ xem có lần 2 không rồi mới ẩn dock (ở trang chủ toàn màn hình không mờ)
                        backPending = true;
                        if (inHost) handler.postDelayed(backHideTask, FADE_MS);
                        else fadeOut(backHideTask);
                    }
                }
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
    }

    Bar(Context c) {
        ctx = c;
        pm = c.getPackageManager();
        sp = c.getSharedPreferences("bar", Context.MODE_PRIVATE);
        usageSp = c.getSharedPreferences("usage", Context.MODE_PRIVATE);
        cntSp = c.getSharedPreferences("count", Context.MODE_PRIVATE); // tổng số lượt mở từng app trên launcher
        inSp = c.getSharedPreferences("inputs", Context.MODE_PRIVATE); // số lần dùng của từng đầu vào
        keySp = c.getSharedPreferences("keys", Context.MODE_PRIVATE); // phím điều khiển -> app đã gán ("k<mã phím>" -> gói)
        wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        d = c.getResources().getDisplayMetrics().density;
        IntentFilter pf = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pf.addDataScheme("package");
        c.registerReceiver(new BroadcastReceiver() {
            public void onReceive(Context ctx, Intent i) { pkgDirty = true; }
        }, pf);
    }

    /** Tính lại 10s rảnh: hết giờ mà dock vẫn hiện, không có thao tác thì tự vào Màn hình bảo vệ. */
    void armDream() {
        handler.removeCallbacks(dreamTask);
        if (shown && autoDream) handler.postDelayed(dreamTask, DREAM_IDLE_MS);
    }

    Toast lastToast;

    /** Thông báo hiện ở đầu màn hình (dock nằm ở đáy và vẽ đè lên toast nên không để toast ở đáy). */
    void toast(final String s) {
        if (Looper.myLooper() != Looper.getMainLooper()) { // gọi từ luồng nền
            handler.post(new Runnable() { public void run() { toast(s); } });
            return;
        }
        if (lastToast != null) lastToast.cancel(); // thông báo mới thay thế ngay thông báo cũ, không xếp hàng chờ
        Toast t = Toast.makeText(ctx, s, Toast.LENGTH_LONG);
        t.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, dp(40));
        t.show();
        lastToast = t;
    }

    /** Hiện thanh nếu đang ẩn, không làm gì nếu đã hiện. Trả về false nếu chưa có quyền overlay. */
    boolean show() { return shown || toggle(); }

    /**
     * Hiện thanh nếu đang ẩn, ẩn nếu đang hiện. Launcher toàn màn hình: dock là view trong Activity (không cần quyền
     * overlay). Có app bên dưới: dock là cửa sổ overlay. Trả về false nếu cần overlay mà chưa có quyền.
     */
    boolean toggle() {
        if (shown) { hide(); return true; }
        boolean inAct = !appBeneath && host != null;
        // Android 12+ (Google TV): thiếu quyền thì addView vẫn "thành công" nhưng cửa sổ bị ẩn, nên phải kiểm tra quyền trước
        if (!inAct && Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(ctx)) return false;
        detach(); // phòng còn dock cũ chưa gỡ: chỉ được có đúng 1 dock
        menuFocus.clear(); // mở lại dock: các menu giữ về lại nút focus mặc định
        if (pkgDirty || !loaded) { // chỉ khi gói thay đổi mới bỏ cache và đọc lại (tra PackageManager rất chậm trên TV)
            pkgDirty = false;
            loaded = true;
            base = null;
            labels.clear();
            load();
        }
        all = null; // sắp xếp lại theo số lượt mở (nhanh, không hỏi PackageManager)
        fixed = null;
        root = new Root(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setClipChildren(false);
        root.setClipToPadding(false);
        // Không có nền phủ: icon nằm trực tiếp trên nền màu / app bên dưới
        int rp = rootPad();
        root.setPadding(rp, rp, rp, dp(Math.max(BOTTOM_MIN_DP, BOTTOM_DP - extraCols() * BOTTOM_STEP_DP)) - gap() / 2);
        try {
            if (inAct) {
                host.addView(root, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
            } else {
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    Build.VERSION.SDK_INT >= 26 ? 2038 : 2002, // APPLICATION_OVERLAY / PHONE
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.BOTTOM;
                wm.addView(root, lp);
            }
        } catch (Throwable t) {
            root = null;
            return false;
        }
        inHost = inAct;
        shown = true;
        int[] pos = restoreFocus ? posOf(focusPkg) : null;
        restoreFocus = false;
        try {
            if (pos != null) render(BAR, pos[0], pos[1]);
            else render(BAR, 0, Math.max(0, (rowItems(0).size() - 1) / 2)); // mặc định focus ô giữa (chẵn thì lấy ô bên trái)
        } catch (Throwable t) { // dựng giao diện lỗi: gỡ dock thay vì để dock trống (phím kế tiếp sẽ dựng lại)
            hideNow();
        }
        armDream();
        armMinute();
        warmUp();
        return true;
    }

    void hide() {
        if (inHost) return; // trang chủ toàn màn hình: Back không ẩn dock
        hideNow();
    }

    /** Ẩn dock bất kể trạng thái (chỉ dùng khi mở app/màn hình chờ hoặc dựng lại dock). */
    void hideNow() {
        handler.removeCallbacks(dreamTask);
        cancelFade();
        detach();
    }

    /**
     * Gỡ dock khỏi nơi nó đang nằm (view trong Activity hoặc cửa sổ overlay), xác định theo cha thật của view chứ không
     * theo cờ, để không bao giờ sót một dock cũ chồng lên dock mới.
     */
    void detach() {
        handler.removeCallbacks(minuteTask);
        assignTo = null;
        shown = inHost = false;
        curRow = null;
        if (root == null) return;
        try {
            Object p = root.getParent();
            if (p == host && host != null) host.removeView(root);
            else if (p != null) wm.removeView(root);
        } catch (Throwable t) {}
        root.removeAllViews(); // giải phóng ảnh/ô khi dock đang ẩn (hiện lại sẽ dựng lại)
    }

    /** Mọi gói màn hình chờ (screensaver/dream) cài trên máy. */
    Set<String> dreamPkgs() {
        Set<String> s = new HashSet<String>();
        s.add("com.google.android.backdrop"); // màn hình chờ Android TV
        try {
            for (ResolveInfo ri : pm.queryIntentServices(new Intent("android.service.dreams.DreamService"), 0))
                s.add(ri.serviceInfo.packageName);
        } catch (Throwable t) {}
        s.remove(ctx.getPackageName());
        return s;
    }

    static final String[] SU_PATHS = {"/system/xbin/su", "/system/bin/su", "/sbin/su",
        "/su/bin/su", "/vendor/bin/su", "/system/sbin/su"};

    boolean hasRoot() {
        for (String p : SU_PATHS) if (new File(p).exists()) return true;
        return false;
    }

    /** Chạy lệnh bằng su, trả về stdout (null nếu lỗi/không có root). */
    String su(String cmd) {
        Process pr = null;
        try {
            pr = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            pr.waitFor();
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (pr != null) pr.destroy();
        }
    }

    /**
     * Sau khi đã mở xong 1 app/màn hình: ẩn dock rồi dọn RAM 1 lần trên luồng nền (không làm chậm lúc bấm).
     * Chờ app mới lên tiền cảnh, rồi đóng MỌI gói đã cài (kể cả app hệ thống, bàn phím, màn hình chờ) trừ launcher,
     * các gói trong keepPkgs, gói đích của target và gói "android" (chính hệ thống, không dừng được); cuối cùng nhờ ADB
     * (nếu đã kết nối được) đóng nốt các tiến trình nền/cache. Mở app khác trong lúc chờ/đang dọn thì lần dọn cũ tự huỷ
     * (nếu không sẽ dừng nhầm app mới mở).
     */
    String openedPkg;                             // app/màn hình vừa mở từ launcher (đang ở phía trước khi appBeneath)
    /** Mọi gói đã được mở từ launcher kể từ khi launcher khởi động: các app có thể còn chạy, để buộc dừng bằng trợ năng. */
    final Set<String> openedSince = Collections.synchronizedSet(new HashSet<String>());

    void afterOpen(final Intent target, String... keepPkgs) {
        appBeneath = true;
        openedPkg = keepPkgs.length > 0 ? keepPkgs[0] : null;
        final boolean everything = exclusiveOpen; // "Mở riêng": dừng cả app hệ thống
        exclusiveOpen = false;
        hideNow();
        bootDreamPending = false; // đã mở app khác: huỷ việc vào Màn hình bảo vệ đang chờ
        final Set<String> keep = new HashSet<String>();
        for (String k : keepPkgs) if (k != null) keep.add(k);
        final int gen = ++killGen;
        new Thread(new Runnable() {
            public void run() {
                try {
                    ResolveInfo ri = target != null ? pm.resolveActivity(target, 0) : null;
                    if (ri != null && !"android".equals(ri.activityInfo.packageName)) keep.add(ri.activityInfo.packageName);
                } catch (Throwable t) {}
                openedSince.addAll(keep);
                nap(CLEAN_DELAY_MS);
                if (gen != killGen) return;
                boolean adb = Adb.ready(ctx); // không có ADB thì dọn gần như không có tác dụng: không thông báo
                if (adb) toast("Đang dừng các ứng dụng khác...");
                try { sweep(keep, gen, everything); } catch (Throwable t) {}
                if (gen == killGen) Adb.clean(ctx);
                if (adb && gen == killGen) toast("Đã dừng các ứng dụng khác");
                // Vào Màn hình bảo vệ sau cùng: dọn xong mới vào (nếu vào trước, lượt dọn sẽ đóng luôn màn hình bảo vệ)
                if (gen == killGen && bootDreamPending) {
                    nap(bootDreamAt - SystemClock.uptimeMillis());
                    if (gen == killGen) handler.post(bootDream);
                }
            }
        }).start();
    }

    static void nap(long ms) {
        try { Thread.sleep(ms); } catch (Exception e) {}
    }

    boolean exclusiveOpen;                        // lần mở app kế tiếp là "Mở riêng": dọn cả app hệ thống

    /** Gói có thể đang ở trước màn hình theo "dữ liệu sử dụng" (đã MOVE_TO_FOREGROUND mà chưa MOVE_TO_BACKGROUND, 6 giờ qua). */
    Set<String> usageForeground() {
        Set<String> fg = new HashSet<String>();
        try {
            UsageStatsManager u = (UsageStatsManager) ctx.getSystemService("usagestats");
            long now = System.currentTimeMillis();
            UsageEvents ev = u.queryEvents(now - 6L * 3600 * 1000, now); // chưa cấp quyền thì rỗng
            UsageEvents.Event e = new UsageEvents.Event();
            while (ev != null && ev.hasNextEvent()) {
                ev.getNextEvent(e);
                if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) fg.add(e.getPackageName());
                else if (e.getEventType() == UsageEvents.Event.MOVE_TO_BACKGROUND) fg.remove(e.getPackageName());
            }
        } catch (Throwable t) {}
        return fg;
    }

    /**
     * Nút "Mở riêng": mở app p và buộc dừng các app khác, kể cả app hệ thống (trừ launcher và vài gói lõi). Có ADB thì mở
     * app rồi dọn bằng ADB. Không có ADB mà đã bật trợ năng thì buộc dừng trước bằng trợ năng (chậm, chỉ các app đã mở từ
     * launcher hoặc "dữ liệu sử dụng" cho là đang ở trước màn hình), rồi mới mở app. Thiếu cả hai thì chỉ mở app.
     */
    void openExclusive(final String p) {
        final int gen = ++killGen;
        focusPkg = p;
        hideNow();
        new Thread(new Runnable() {
            public void run() {
                if (!Adb.ready(ctx) && KeyService.inst != null) {
                    toast("Không có ADB: buộc dừng bằng trợ năng, sẽ mất một lúc...");
                    nap(300); // chờ dock gỡ xong để Cài đặt giữ focus
                    Set<String> c = new HashSet<String>(openedSince);
                    c.addAll(usageForeground());
                    try { // ứng dụng bàn phím (IME) cũng hay chiếm RAM dù không mở từ launcher
                        InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
                        for (InputMethodInfo im : imm.getInputMethodList()) c.add(im.getPackageName());
                    } catch (Throwable ignored) {}
                    c.remove(p);
                    String set = settingsPkg();
                    List<String> l = new ArrayList<String>();
                    for (String x : c) if (!x.equals(ctx.getPackageName()) && !x.equals(set) && !neverStop(x, true)) l.add(x);
                    if (c.contains(set)) l.add(set); // Cài đặt dừng sau cùng vì là nơi bấm nút
                    KeyService.forceStop(ctx, l, gen);
                } else {
                    exclusiveOpen = true; // có ADB (hoặc không có gì): afterOpen dọn tất cả nếu có ADB
                }
                if (gen != killGen) return;
                handler.post(new Runnable() {
                    public void run() { launch(p); }
                });
            }
        }).start();
    }

    /** Back đúp khi dock đang hiện: về launcher rồi buộc dừng mọi gói đang chạy (kể cả gói Google), trừ launcher. */
    void stopAll() {
        hideNow();
        bootDreamPending = false;
        appBeneath = false;
        final int gen = ++killGen;
        try {
            ctx.startActivity(new Intent(ctx, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) {}
        new Thread(new Runnable() {
            public void run() {
                // Chờ launcher lên màn hình chính (tối đa 3s) rồi mới dừng, để người dùng thấy trang chủ trước
                for (int i = 0; i < 30 && !launcherResumed && gen == killGen; i++) nap(100);
                nap(400);
                if (gen != killGen) return;
                boolean adb = Adb.ready(ctx);
                if (adb) toast("Đang dừng tất cả ứng dụng...");
                try { sweep(new HashSet<String>(), gen, true); } catch (Throwable t) {}
                if (gen == killGen) Adb.clean(ctx);
                if (adb && gen == killGen) toast("Đã dừng tất cả ứng dụng");
            }
        }).start();
    }

    /**
     * Dọn RAM, chạy trên luồng nền ưu tiên thấp. Có ADB: đọc danh sách gói đang chạy một lần rồi đóng tiến trình nền
     * (killBackgroundProcesses) và "am force-stop" riêng các gói đó. Không có ADB: chỉ đóng tiến trình nền của app người dùng cài.
     */
    void sweep(Set<String> keepPkgs, int gen) {
        sweep(keepPkgs, gen, false);
    }

    /** all=true: dừng cả gói Google/hệ thống thường dừng không an toàn (chỉ chừa vài gói lõi để TV không treo). */
    void sweep(Set<String> keepPkgs, int gen, boolean all) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); // không giành CPU với giao diện (Process đã dùng cho java.lang.Process)
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        Set<String> keep = new HashSet<String>(keepPkgs);
        keep.add(ctx.getPackageName());
        keep.add("android");
        // Có ADB: chỉ xử lý các gói ĐANG CHẠY (đọc 1 lần từ "ps"). Không có: đóng tiến trình nền của app người dùng cài
        AdbClient a = null;
        Set<String> run = null;
        try {
            if (Adb.ready(ctx)) {
                a = Adb.open(ctx.getApplicationContext());
                run = Adb.parsePs(a.shell("ps -A -o NAME 2>/dev/null || ps 2>/dev/null"));
                if (run.isEmpty()) run = null; // không đọc được danh sách tiến trình
            }
            List<String> todo = new ArrayList<String>();
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                String p = ai.packageName;
                if (keep.contains(p)) continue;
                if (run != null ? !run.contains(p) : (!all && (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0)) continue;
                todo.add(p);
            }
            for (String p : todo) {
                if (gen != killGen) return;
                try { am.killBackgroundProcesses(p); } catch (Throwable t) {}
            }
            if (a != null && gen == killGen) adbForceStop(a, todo, gen, all);
        } catch (Throwable ignored) {
        } finally {
            if (a != null) a.close();
        }
    }

    /** Các gói hạ tầng không bao giờ buộc dừng (dừng sẽ mất remote Bluetooth, thanh hệ thống, dịch vụ Google...). */
    static final String[] NEVER_STOP = {
        "android", "com.android.systemui", "com.android.bluetooth", "com.android.shell",
        "com.android.providers.", "com.android.server.", "com.android.inputdevices", "com.google.android.gms",
        "com.google.android.gsf", "com.google.android.tv.remote", "com.android.tv.settings"
    };

    /** Gói lõi luôn giữ kể cả khi dừng tất cả: mất là mất remote Bluetooth / thanh hệ thống / ADB shell. */
    static final String[] CORE = {"android", "com.android.systemui", "com.android.bluetooth", "com.android.shell"};

    /** all=true (dừng tất cả / Mở riêng) chỉ giữ CORE; còn lại giữ cả NEVER_STOP. Mục kết thúc bằng "." là tiền tố. */
    static boolean neverStop(String p, boolean all) {
        for (String n : all ? CORE : NEVER_STOP) if (n.endsWith(".") ? p.startsWith(n) : p.equals(n)) return true;
        return false;
    }

    /**
     * "Buộc dừng" như trong Cài đặt > Ứng dụng, bằng "am force-stop" qua ADB đã mở. todo chỉ gồm gói đang chạy. Chạy song song
     * theo nhóm nhỏ (mỗi lệnh là một tiến trình Java, nhiều quá sẽ làm TV khựng); mở app khác giữa chừng (gen đổi) thì dừng ngay.
     */
    void adbForceStop(AdbClient a, List<String> todo, int gen, boolean all) throws Exception {
        StringBuilder cmd = new StringBuilder();
        int n = 0;
        for (String p : todo) {
            if (neverStop(p, all) || !p.matches("[A-Za-z0-9_.]+")) continue;
            cmd.append("am force-stop ").append(p).append(" & ");
            if (++n % 3 == 0) {
                if (gen != killGen) return;
                a.shell(cmd.append("wait").toString());
                cmd.setLength(0);
            }
        }
        if (cmd.length() > 0 && gen == killGen) a.shell(cmd.append("wait").toString());
    }

    /** Mở màn hình Thông tin ứng dụng của hệ thống (không chạy dọn RAM). */
    void openInfo(String p) {
        Intent q = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + p));
        if (!launchDream(q)) toast("Không mở được Thông tin ứng dụng trên TV");
    }

    /** Gỡ cài đặt: hệ thống tự hiện hộp thoại xác nhận. Ứng dụng hệ thống gốc thì không gỡ được. */
    void uninstallApp(String p) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(p, 0);
            boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean upd = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            if (sys && !upd) { toast(label(p) + " là ứng dụng hệ thống, không gỡ được"); return; }
        } catch (Throwable t) {}
        if (p.equals(ctx.getPackageName())) { toast("Không thể gỡ chính launcher"); return; }
        Intent q = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + p));
        if (!launchDream(q)) toast("Không mở được hộp thoại gỡ cài đặt trên TV");
    }

    // ---------- dữ liệu ----------
    int dp(int v) { return (int) (v * d + 0.5f); }
    /** Số cột vượt mức tối thiểu: mỗi cột thêm làm khoảng cách giữa ô và tới 3 viền co lại một chút. */
    int extraCols() { return cols() - MIN_COLS; }

    int gap() { return dp(Math.max(GAP_MIN_DP, GAP_DP - extraCols() * GAP_STEP_DP)); }
    /** Padding của root = khoảng cách tới viền trừ nửa khoảng cách ô (mỗi ô đã có margin = gap/2). */
    int rootPad() { return dp(Math.max(EDGE_MIN_DP, EDGE_DP - extraCols() * EDGE_STEP_DP)) - gap() / 2; }
    /** Số cột = số ô của dock (tối thiểu MIN_COLS khi dock trống/ít app, tối đa MAX_APPS). */
    int cols() { return Math.max(MIN_COLS, Math.min(apps.size(), MAX_APPS)); }
    /** Bề rộng 1 ô theo số cột, mọi hàng giống nhau. */
    int cellW(int n) {
        int sw = ctx.getResources().getDisplayMetrics().widthPixels;
        return Math.min(dp(170), (sw - 2 * rootPad()) / Math.max(cols(), n) - gap());
    }

    boolean installed(String p) {
        try { pm.getPackageInfo(p, 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    void load() {
        apps.clear();
        hidden.clear();
        for (String p : sp.getString("hidden", "").split(",")) {
            if (p.length() > 0 && installed(p)) hidden.add(p);
        }
        for (String p : sp.getString("apps", "").split(",")) {
            if (p.length() == 0 || hidden.contains(p) || apps.contains(p) || !installed(p)) continue;
            if (apps.size() >= MAX_APPS) break;
            apps.add(p);
        }
    }

    void save() {
        sp.edit().putString("apps", TextUtils.join(",", apps))
            .putString("hidden", TextUtils.join(",", hidden)).apply();
    }

    final Map<String, String> labels = new HashMap<String, String>(); // tên app đã tra (xoá mỗi lần dựng lại dock)

    String label(String p) {
        String s = labels.get(p);
        if (s == null) {
            try {
                s = pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString();
                labels.put(p, s);
            } catch (Exception e) {
                return p;
            }
        }
        return s;
    }

    Drawable icon(String p) {
        try { return pm.getApplicationIcon(p); }
        catch (Exception e) { return pm.getDefaultActivityIcon(); }
    }

    /** Activity mở app (ưu tiên giao diện TV), tra 1 lần rồi nhớ lại để bấm là mở ngay. null nếu app không mở được. */
    ResolveInfo resolve(String p) {
        ResolveInfo r = resolved.get(p);
        if (r != null) return r;
        for (String cat : new String[] {LEANBACK, Intent.CATEGORY_LAUNCHER}) {
            List<ResolveInfo> l = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(cat).setPackage(p), 0);
            if (!l.isEmpty()) {
                resolved.put(p, l.get(0));
                return l.get(0);
            }
        }
        return null;
    }

    /** Tra sẵn activity của mọi app trên luồng nền, để lần bấm đầu tiên cũng không phải chờ. */
    boolean warming;                              // đang nạp trước thông tin app (chỉ 1 luồng tại 1 thời điểm)

    void warmUp() {
        if (warming) return;
        warming = true;
        final List<String> l = new ArrayList<String>(apps);
        for (Cand c : all()) l.add(c.pkg);
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    for (String p : l) resolve(p);
                } finally {
                    warming = false;
                }
            }
        });
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Ảnh icon tuỳ chỉnh cho một số app (tải về 1 lần, lưu trong bộ nhớ app). */
    static final Map<String, String> CUSTOM_ICONS = new HashMap<String, String>();
    static {
        CUSTOM_ICONS.put("org.smarttube.stable",
            "https://www.gstatic.com/marketing-cms/assets/images/43/3a/7d91281f442b86d2402f615fa886/external-logo-core-2.png");
        CUSTOM_ICONS.put("io.gh.reisxd.tizentube.cobalt",
            "https://www.gstatic.com/marketing-cms/assets/images/f6/39/f3b4730c409b8dabc66cda080f5a/external-logo-core-3.png");
    }
    final Map<String, android.graphics.Bitmap> customBmp = new HashMap<String, android.graphics.Bitmap>();
    final Map<String, Long> customTry = new HashMap<String, Long>(); // lần thử tải gần nhất (uptime) của từng app

    static final float LOGO_KEEP = 0.8f;      // giữ lại 80% ở giữa ảnh (bỏ 10% mỗi cạnh)
    static final int LOGO_MAX_PX = 640;       // bề rộng tối đa khi lưu: ô chỉ cần ~2 lần bề rộng ô

    /** Ảnh đã cắt và nén (WEBP), lưu vĩnh viễn trong bộ nhớ app (chỉ xoá khi file hỏng thật, để tải và cắt lại). */
    File customFile(String p) { return new File(ctx.getFilesDir(), "logo_" + p + ".webp"); }


    static android.graphics.BitmapFactory.Options bounds(String path) {
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(path, o);
        return o;
    }

    /** Giải mã ảnh, thu nhỏ (luỹ thừa của 2) cho tới khi bề rộng chưa tới 2 lần maxW. null nếu lỗi (kể cả hết RAM). */
    static android.graphics.Bitmap decode(String path, int maxW) {
        try {
            android.graphics.BitmapFactory.Options o = bounds(path);
            int s = 1;
            while (o.outWidth / (s * 2) >= maxW) s *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = s;
            return android.graphics.BitmapFactory.decodeFile(path, o);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Cắt giữ lại tỉ lệ keep ở giữa ảnh src, thu nhỏ nếu rộng quá LOGO_MAX_PX, nén WEBP rồi lưu vào dst (nhỏ hơn nhiều so
     * với PNG, vẫn giữ nền trong suốt). true nếu thành công.
     */
    static boolean cropSave(File src, File dst, float keep) {
        android.graphics.Bitmap full = decode(src.getPath(), 1024), crop = null, small = null;
        File tmp = new File(dst.getPath() + ".tmp");
        try {
            if (full == null) return false;
            int w = full.getWidth(), h = full.getHeight(), x = Math.round(w * (1 - keep) / 2), y = Math.round(h * (1 - keep) / 2);
            crop = android.graphics.Bitmap.createBitmap(full, x, y, w - 2 * x, h - 2 * y);
            small = crop;
            if (crop.getWidth() > LOGO_MAX_PX) {
                small = android.graphics.Bitmap.createScaledBitmap(crop, LOGO_MAX_PX, crop.getHeight() * LOGO_MAX_PX / crop.getWidth(), true);
            }
            java.io.FileOutputStream os = new java.io.FileOutputStream(tmp);
            boolean ok;
            try {
                ok = small.compress(android.graphics.Bitmap.CompressFormat.WEBP, 85, os);
            } finally {
                os.close();
            }
            return ok && tmp.renameTo(dst);
        } catch (Throwable t) {
            return false;
        } finally {
            if (small != null && small != crop) small.recycle();
            if (crop != null && crop != full) crop.recycle();
            if (full != null) full.recycle();
            tmp.delete(); // sau khi đổi tên thì không còn; còn thì là file lỗi
        }
    }

    /**
     * Ảnh tuỳ chỉnh của app p (null nếu chưa có / không cấu hình). Đã tải và cắt thành công một lần thì file được giữ
     * mãi và không bao giờ tải lại (giải mã lỗi tạm thời, vd. thiếu RAM lúc TV vừa khởi động, không xoá file). Chưa có
     * file thì tải nền (chưa có mạng thì thử lại sau mỗi 30 giây), cắt, lưu rồi vẽ lại dock.
     */
    android.graphics.Bitmap customIcon(final String p) {
        final String url = CUSTOM_ICONS.get(p);
        if (url == null) return null;
        android.graphics.Bitmap bm = customBmp.get(p);
        if (bm != null) return bm;
        File f = customFile(p);
        if (f.exists()) {
            bm = decode(f.getPath(), dp(340)); // ~2 lần bề rộng ô là đủ nét
            if (bm != null) { customBmp.put(p, bm); return bm; }
            if (bounds(f.getPath()).outWidth > 0) return null; // ảnh tốt, giải mã lỗi tạm thời: lần dựng dock sau thử lại
            f.delete(); // file hỏng thật: tải và cắt lại
        }
        long now = SystemClock.uptimeMillis();
        Long last = customTry.get(p);
        if (last == null || now - last >= 30000) {
            customTry.put(p, now);
            new Thread(new Runnable() {
                public void run() {
                    File part = new File(ctx.getFilesDir(), "logo_" + p + ".part");
                    try {
                        Updater.fetch(url, part);
                        if (cropSave(part, customFile(p), LOGO_KEEP)) {
                            handler.post(new Runnable() {
                                public void run() { refresh(); }
                            });
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        part.delete();
                    }
                }
            }).start();
        }
        return null;
    }

    /** Banner 16:9 của app trên TV (hoặc banner của ứng dụng), null nếu không có. */
    Drawable banner(String p) {
        try {
            ResolveInfo ri = resolve(p);
            Drawable b = ri != null ? ri.activityInfo.loadBanner(pm) : null;
            return b != null ? b : pm.getApplicationBanner(p);
        } catch (Throwable e) {
            return null;
        }
    }

    /** Mở lại app dùng gần nhất (mốc thời gian lưu lúc bấm mở app từ dock/tất cả app). false nếu không có app nào để mở. */
    boolean launchRecent() {
        String best = null;
        long t = 0;
        for (Map.Entry<String, ?> e : usageSp.getAll().entrySet()) {
            if (!(e.getValue() instanceof Long) || (Long) e.getValue() <= t) continue;
            try {
                pm.getApplicationInfo(e.getKey(), 0); // bỏ qua app đã gỡ
            } catch (Throwable x) {
                continue;
            }
            best = e.getKey();
            t = (Long) e.getValue();
        }
        return best != null && !best.equals(ctx.getPackageName()) && launch(best);
    }

    boolean launch(String p) {
        ResolveInfo ri = resolve(p);
        if (ri == null) {
            toast("Không mở được: " + label(p));
            return false;
        }
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(ri.activityInfo.packageName, ri.activityInfo.name);
        if (!open(q, OPEN_APP, p)) {
            resolved.remove(p);
            toast("Không mở được: " + label(p));
            return false;
        }
        usageSp.edit().putLong(p, System.currentTimeMillis()).apply();
        lastApp = p;
        return true;
    }

    boolean startOutside(Intent q, String... keepPkgs) {
        if (open(q, OPEN_SCREEN, keepPkgs)) return true;
        toast("Không mở được mục này trên TV");
        return false;
    }

    /** Mở một màn hình hệ thống mà không dọn RAM (vd. Thông tin ứng dụng, gỡ cài đặt, Màn hình bảo vệ). */
    boolean launchDream(Intent q) {
        return open(q, OPEN_PLAIN);
    }

    static final String SYSTEMUI = "com.android.systemui";
    static final String SOMNAMBULATOR = "com.android.systemui.Somnambulator";
    static final int OPEN_APP = 0, OPEN_SCREEN = 1, OPEN_PLAIN = 2; // mở app (dọn RAM, chừa app đó) / màn hình (dọn RAM) / không dọn (từ dock overlay: Back trở về app đang dùng)

    /**
     * Mở q. Đang hiện dock overlay (còn app khác nằm bên dưới): đưa launcher lên trước rồi mới mở, để bấm Back ở app mới
     * luôn về trang chủ, không lùi về app cũ bên dưới (app đó có thể đã bị dừng, phải khởi động lại cả vài giây gây đơ màn hình).
     */
    boolean open(Intent q, int how, String... keep) {
        q.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (shown && !inHost) {
            // Mở từ dock overlay (đang có app chạy bên dưới): vẫn dọn RAM nhưng chừa app đó, để Back thoát ra là trở về nó
            String[] k = keep;
            if (lastApp != null && how != OPEN_PLAIN) {
                k = Arrays.copyOf(keep, keep.length + 1);
                k[keep.length] = lastApp;
            }
            openedApp = how == OPEN_APP;
            if (openedApp) prevApp = lastApp;
            try {
                ctx.startActivity(new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_LAUNCH, q).putExtra(EXTRA_HOW, how).putExtra(EXTRA_KEEP, k));
                return true;
            } catch (Throwable ignored) {} // không được thì mở thẳng
        } else if (how == OPEN_APP) {
            prevApp = null; // mở từ trang chủ: bên dưới không còn app nào cần giữ
        }
        return openNow(q, how, keep);
    }

    /** Mở q ngay (launcher đã ở trước hoặc không cần đưa lên trước). */
    boolean openNow(Intent q, int how, String... keep) {
        try {
            ctx.startActivity(q); // mở trước, mọi việc phụ làm sau (trên luồng nền)
        } catch (Throwable t) {
            return false;
        }
        if (how == OPEN_PLAIN) {
            hideNow();
            appBeneath = true;
        } else {
            afterOpen(how == OPEN_SCREEN ? q : null, keep);
        }
        return true;
    }

    /**
     * Chạy ngay màn hình bảo vệ (dream) của TV. App thường không có quyền gọi thẳng DreamManager nên thử lần lượt:
     * Somnambulator của SystemUI, intent "dock", các Activity tên dream/screensaver trên máy, cuối cùng là root.
     */
    boolean startDream() {
        Set<String> dreams = dreamPkgs();
        List<Intent> tries = new ArrayList<Intent>();
        tries.add(new Intent(Intent.ACTION_MAIN).setClassName(SYSTEMUI, SOMNAMBULATOR));
        tries.add(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_DESK_DOCK));
        Set<String> pkgs = new LinkedHashSet<String>(dreams);
        pkgs.add(SYSTEMUI);
        for (String p : pkgs) {
            try {
                ActivityInfo[] acts = pm.getPackageInfo(p, PackageManager.GET_ACTIVITIES).activities;
                if (acts == null) continue;
                for (ActivityInfo ai : acts) {
                    String n = ai.name.toLowerCase();
                    if (ai.exported && (n.contains("somnambulator") || n.contains("dream") || n.contains("screensaver")))
                        tries.add(new Intent(Intent.ACTION_MAIN).setClassName(ai.packageName, ai.name));
                }
            } catch (Throwable t) {}
        }
        for (Intent q : tries) {
            if (pm.resolveActivity(q, 0) == null) continue; // không có/không mở được: bỏ qua, không báo lỗi
            // Task mới hoàn toàn: nếu không, task cũ của app screensaver (vd. màn hình cài đặt Aerial Views mở thủ công trước
            // đó) bị kéo lên và nằm dưới screensaver, thoát screensaver là lại thấy màn hình cài đặt đó
            q.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            if (launchDream(q)) return true;
        }
        if (hasRoot()) { // root: chạy lệnh am start (trên luồng riêng vì su có thể chờ)
            new Thread(new Runnable() {
                public void run() {
                    su("am start -n " + SYSTEMUI + "/.Somnambulator 2>/dev/null");
                    handler.post(new Runnable() {
                        public void run() {
                            hideNow();
                            appBeneath = true;
                        }
                    });
                }
            }).start();
            return true;
        }
        return false;
    }

    /** Lúc khởi động, sau khi vừa tự mở app gần nhất: vào Màn hình bảo vệ 1 lần, sau khi dọn xong và đủ DREAM_AFTER_OPEN_MS. */
    void dreamAfterOpen() {
        bootDreamAt = SystemClock.uptimeMillis() + DREAM_AFTER_OPEN_MS;
        bootDreamPending = true; // lượt dọn của app vừa mở sẽ gọi bootDream khi xong và đủ thời gian chờ
    }

    /** Vừa cài bản cập nhật (launcher khởi động lại): đánh dấu để lần khởi động ngay sau đó không tự mở app/screensaver. */
    void markUpdating() { sp.edit().putLong("updating", System.currentTimeMillis()).commit(); }

    void clearUpdating() { sp.edit().remove("updating").apply(); }

    boolean consumeUpdated() {
        long t = sp.getLong("updating", 0);
        if (t == 0) return false;
        sp.edit().remove("updating").commit();
        return System.currentTimeMillis() - t < 120000;
    }

    /** Mở app Cài đặt (như bấm nút Cài đặt trên dock). */
    void openSettings() {
        startOutside(new Intent(Settings.ACTION_SETTINGS), settingsPkg());
    }

    /** Gói của app Cài đặt (null nếu không tìm thấy); tra 1 lần rồi nhớ lại để bấm không bị chậm. */
    String settingsPkg() {
        if (!settingsKnown) {
            settingsCache = findSettingsPkg();
            settingsKnown = true;
        }
        return settingsCache;
    }

    String findSettingsPkg() {
        try {
            List<ResolveInfo> l = pm.queryIntentActivities(new Intent(Settings.ACTION_SETTINGS), 0);
            String first = null;
            for (ResolveInfo ri : l) {
                String p = ri.activityInfo.packageName;
                if (p.equals("com.android.tv.settings")) return p;
                if (first == null && !p.equals("android")) first = p;
            }
            if (first != null) return first;
        } catch (Throwable t) {}
        return installed("com.android.tv.settings") ? "com.android.tv.settings"
            : installed("com.android.settings") ? "com.android.settings" : null;
    }

    /** Tên Wi-Fi đang kết nối, "LAN" nếu cắm dây Ethernet, "Chưa kết nối" nếu không có mạng. */
    String netLabel() {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm.getActiveNetworkInfo();
            if (ni == null || !ni.isConnected()) return "Chưa kết nối";
            if (ni.getType() == ConnectivityManager.TYPE_ETHERNET) return "LAN";
            if (ni.getType() != ConnectivityManager.TYPE_WIFI) return "Đã kết nối";
            String s = null;
            try { // cần quyền vị trí (Android 8.1+); thiếu quyền thì trả về "<unknown ssid>"
                WifiManager wm0 = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                s = wm0.getConnectionInfo().getSSID();
            } catch (Throwable t) {}
            if (badSsid(s)) s = ni.getExtraInfo(); // dự phòng: một số bản Android vẫn trả tên Wi-Fi ở đây
            if (badSsid(s)) { // vẫn bị che (TV không bật Vị trí...): đọc qua ADB nếu có
                s = adbSsid;
                if (badSsid(s)) fetchSsid();
            }
            return badSsid(s) ? "Wi-Fi" : s.replaceAll("^\"|\"$", "");
        } catch (Throwable t) {
            return "Mạng";
        }
    }

    volatile String adbSsid;   // tên Wi-Fi đọc qua ADB (khi hệ thống không cho app đọc)
    long ssidTry;              // lần thử đọc gần nhất (uptime), để không thử dồn dập

    /** Đọc tên Wi-Fi từ "dumpsys wifi" qua ADB trên luồng nền, xong thì vẽ lại dock. */
    void fetchSsid() {
        long now = SystemClock.uptimeMillis();
        if (ssidTry != 0 && now - ssidTry < 20000) return;
        ssidTry = now;
        new Thread(new Runnable() {
            public void run() {
                AdbClient a = null;
                try {
                    if (!Adb.ready(ctx)) return;
                    a = Adb.open(ctx.getApplicationContext());
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("mWifiInfo.*?SSID: (.*?), (?:BSSID|MAC)")
                        .matcher(a.shell("dumpsys wifi | grep mWifiInfo"));
                    if (m.find() && !badSsid(m.group(1)) && !m.group(1).equals(adbSsid)) {
                        adbSsid = m.group(1);
                        handler.post(new Runnable() {
                            public void run() { fixed = null; refresh(); }
                        });
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (a != null) a.close();
                }
            }
        }).start();
    }

    /** Vẽ lại hàng đang hiện, giữ nguyên ô đang focus. */
    void refresh() {
        if (!shown || mode != BAR || curRow == null) return;
        View fc = curRow.getFocusedChild();
        render(BAR, curRowIdx, fc != null ? Math.max(0, curRow.indexOfChild(fc)) : 0);
    }

    static boolean badSsid(String s) {
        return s == null || s.length() == 0 || s.contains("unknown ssid");
    }

    /** Mở cài đặt mạng (Wi-Fi/Không dây), không có thì mở Cài đặt chung. */
    void openNetwork() {
        for (String a : new String[] {Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS}) {
            Intent q = new Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(q);
                afterOpen(q, settingsPkg());
                return;
            } catch (Throwable t) {} // máy không có màn hình này: thử màn hình kế tiếp
        }
        openSettings();
    }

    /**
     * Mục đầu vào do thiết bị CEC đang cắm vào cổng tạo ra (vd. MIBOX trên HDMI 1), trùng với chính cổng đó.
     * Id của TV input: cổng thật kết thúc bằng "/HW<số>", thiết bị CEC kết thúc bằng "/HDMI<địa chỉ hex>".
     * Thêm reflection (getHdmiDeviceInfo/getParentId, API ẩn) để bắt cả các máy đặt id khác.
     */
    static boolean isDeviceInput(TvInputInfo ti, String lastSeg) {
        if (lastSeg.matches("HDMI[0-9A-Fa-f]{6,}")) return true;
        for (String m : new String[] {"getHdmiDeviceInfo", "getParentId"}) {
            try {
                if (TvInputInfo.class.getMethod(m).invoke(ti) != null) return true;
            } catch (Throwable t) {}
        }
        return false;
    }

    /** Các cổng HDMI của TV, sắp theo tên, mỗi cổng 1 mục (cổng đang có thiết bị không bị liệt kê thêm lần nữa). */
    List<Inp> inputs() {
        if (inputs != null && SystemClock.uptimeMillis() - inputsAt < 60000) return inputs;
        List<Inp> found = new ArrayList<Inp>();
        boolean anyHw = false, anyPort = false;
        try {
            TvInputManager tm = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
            for (TvInputInfo ti : tm.getTvInputList()) {
                if (ti.getType() != TvInputInfo.TYPE_HDMI) continue;
                try { if (ti.isHidden(ctx)) continue; } catch (Throwable t) {} // cổng bị người dùng ẩn
                CharSequence lb = null;
                try { lb = ti.loadCustomLabel(ctx); } catch (Throwable t) {}
                if (lb == null || lb.length() == 0) lb = ti.loadLabel(ctx);
                Inp it = new Inp();
                it.id = ti.getId();
                String seg = it.id.substring(it.id.lastIndexOf('/') + 1);
                it.label = lb != null ? lb.toString() : "HDMI";
                it.pkg = ti.getServiceInfo() != null ? ti.getServiceInfo().packageName : null;
                it.hw = seg.startsWith("HW");
                it.device = isDeviceInput(ti, seg);
                anyHw |= it.hw;
                anyPort |= !it.device;
                found.add(it);
            }
        } catch (Throwable t) {}
        Collections.sort(found, new Comparator<Inp>() {
            public int compare(Inp x, Inp y) { return x.label.compareToIgnoreCase(y.label); }
        });
        List<Inp> out = new ArrayList<Inp>();
        Set<String> seen = new HashSet<String>(); // tên đã có: bỏ mục trùng tên
        for (Inp i : found) {
            if (anyHw ? !i.hw : (anyPort && i.device)) continue; // chỉ giữ cổng thật
            if (seen.add(i.label.toLowerCase())) out.add(i);
        }
        inputs = out;
        inputsAt = SystemClock.uptimeMillis();
        return out;
    }

    /** Đầu vào dùng nhiều nhất (chưa dùng đầu vào nào thì lấy cái đầu tiên). Chỉ gọi khi inputs() không rỗng. */
    Inp bestInput() {
        List<Inp> l = inputs();
        Inp best = l.get(0);
        int bc = -1;
        for (Inp i : l) {
            int c = inSp.getInt(i.id, 0);
            if (c > bc) { bc = c; best = i; }
        }
        return best;
    }

    /** Chuyển sang 1 đầu vào và tính thêm 1 lượt dùng. */
    void launchInput(Inp i) {
        if (startOutside(new Intent(Intent.ACTION_VIEW,
                TvContract.buildChannelUriForPassthroughInput(i.id)), i.pkg)) {
            inSp.edit().putInt(i.id, inSp.getInt(i.id, 0) + 1).apply();
        }
    }

    /**
     * Nút "Tắt nguồn" / "Khởi động lại" trong menu giữ Gỡ lỗi. Ưu tiên ADB (tắt hẳn / reboot); không có ADB thì dùng
     * hộp thoại nguồn của hệ thống qua trợ năng. Nút được kích hoạt khi đã nhả phím; chờ thêm một chút cho remote yên
     * (tắt khi phím còn rung có thể đánh thức TV bật lại ngay).
     */
    void power(final boolean reboot) {
        if (shutdownPending) return;
        shutdownPending = true;
        toast(reboot ? "Đang khởi động lại..." : "Đang tắt nguồn...");
        handler.postDelayed(new Runnable() {
            public void run() {
                shutdownPending = false;
                new Thread(new Runnable() {
                    public void run() {
                        try {
                            if (Adb.ready(ctx)) {
                                Adb.power(ctx, reboot);
                                return;
                            }
                        } catch (Throwable ignored) {}
                        if (KeyService.inst != null) {
                            handler.post(new Runnable() {
                                public void run() { hideNow(); } // dock không che hộp thoại nguồn
                            });
                            nap(300);
                            if (KeyService.powerAction(reboot)) return;
                        }
                        toast("Cần kết nối ADB hoặc bật quyền trợ năng (TV có hộp thoại nguồn) để " + (reboot ? "khởi động lại" : "tắt nguồn"));
                    }
                }).start();
            }
        }, 800);
    }

    /**
     * Nút "Buộc dừng" của một app: có ADB thì "am force-stop" qua ADB; không thì dùng trợ năng (mở Thông tin ứng dụng rồi bấm
     * Buộc dừng); không có cả hai thì mở Thông tin ứng dụng để bấm tay.
     */
    void forceStopApp(final String p) {
        if (p.equals(ctx.getPackageName())) {
            toast("Không thể buộc dừng chính launcher");
            return;
        }
        final String name = label(p);
        final boolean host = inHost;
        new Thread(new Runnable() {
            public void run() {
                AdbClient a = null;
                try {
                    if (p.matches("[A-Za-z0-9_.]+") && Adb.ready(ctx)) {
                        a = Adb.open(ctx.getApplicationContext());
                        a.shell("am force-stop " + p);
                        toast("Đã buộc dừng " + name);
                        return;
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (a != null) a.close();
                }
                if (KeyService.inst != null) {
                    handler.post(new Runnable() {
                        public void run() { hideNow(); } // dock không che màn hình Thông tin ứng dụng
                    });
                    nap(300);
                    int n = KeyService.forceStop(ctx, Collections.singletonList(p), ++killGen);
                    if (host) { // đang ở trang chủ: về lại trang chủ (dựng lại dock)
                        try { ctx.startActivity(new Intent(ctx, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Throwable ignored) {}
                    }
                    toast(n > 0 ? "Đã buộc dừng " + name : name + " không chạy hoặc không buộc dừng được");
                    return;
                }
                toast("Chưa có ADB hoặc trợ năng: hãy bấm Buộc dừng trong Thông tin ứng dụng");
                handler.post(new Runnable() {
                    public void run() { openInfo(p); }
                });
            }
        }).start();
    }

    final SimpleDateFormat clockFmt = new SimpleDateFormat("HH:mm", Locale.US);
    final SimpleDateFormat dateFmt = new SimpleDateFormat("EEEE, d 'tháng' M", new Locale("vi"));
    int lunarKey;                                 // ngày (yyyyMMdd) của lunarCache
    String lunarCache;

    String timeText(Date d) { return clockFmt.format(d); }

    /** Thứ, ngày, tháng (vd. "Thứ Sáu, 6 tháng 11"). */
    String dateText(Date d) {
        String s = dateFmt.format(d);
        return s.length() > 0 ? Character.toUpperCase(s.charAt(0)) + s.substring(1) : s;
    }

    /** Thông tin âm lịch chi tiết (2 dòng) của ngày d; tính một lần cho mỗi ngày. */
    String lunarText(Date d) {
        Calendar c = Calendar.getInstance();
        c.setTime(d);
        int key = c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH);
        if (lunarCache == null || key != lunarKey) {
            String[] l = Lunar.info(c);
            lunarCache = l[0] + "\n" + l[1];
            lunarKey = key;
        }
        return lunarCache;
    }

    /** Mỗi đầu phút: nếu đang ở hàng cố định thì vẽ lại để ô giờ:phút đúng giờ. */
    final Runnable minuteTask = new Runnable() {
        public void run() {
            if (!shown) return;
            if (mode == BAR && curRow != null && isFixedRow(curRowIdx)) {
                fixed = null;
                refresh();
            }
            armMinute();
        }
    };

    void armMinute() {
        handler.removeCallbacks(minuteTask);
        handler.postDelayed(minuteTask, 60000 - System.currentTimeMillis() % 60000 + 50);
    }

    static final String APPS_URL = "https://thangmath.github.io/3tv.js";

    /** Tên file APK lưu tạm: phần cuối của link nếu là .apk, không thì tên nút. */
    static String apkName(String url, String name) {
        String f = url.substring(url.lastIndexOf('/') + 1);
        int q = f.indexOf('?');
        if (q >= 0) f = f.substring(0, q);
        return f.toLowerCase().endsWith(".apk") ? f : name.replaceAll("[^A-Za-z0-9_.-]", "_") + ".apk";
    }

    /**
     * Nút "Tải ứng dụng": đọc danh sách từ 3tv.js (mảng JSON các mục {"name", "url", "self"}) rồi hiện menu, mỗi nút tải APK
     * và cài đặt. Giữ lại tối đa một lần tải tại một thời điểm (Updater.busy).
     */
    void showAppList(final int col) {
        toast("Đang lấy danh sách ứng dụng...");
        new Thread(new Runnable() {
            public void run() {
                try {
                    String txt = Updater.fetchText(APPS_URL);
                    JSONArray arr = new JSONArray(txt.substring(txt.indexOf('['), txt.lastIndexOf(']') + 1));
                    final List<String> labels = new ArrayList<String>();
                    final List<Runnable> acts = new ArrayList<Runnable>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        final String name = o.getString("name"), url = o.getString("url");
                        final boolean self = o.optBoolean("self", false);
                        labels.add(name);
                        acts.add(new Runnable() {
                            public void run() { Updater.start(Bar.this, url, apkName(url, name), self); }
                        });
                    }
                    if (labels.isEmpty()) throw new JSONException("danh sách trống");
                    handler.post(new Runnable() {
                        public void run() {
                            if (shown) showFixedMenu("Chọn ứng dụng muốn cài đặt", col, labels.toArray(new String[0]), acts.toArray(new Runnable[0]), (labels.size() - 1) / 2); // mặc định vào ô giữa như dock
                        }
                    });
                } catch (Throwable t) {
                    toast("Không lấy được danh sách ứng dụng");
                }
            }
        }).start();
    }

    /** Hàng cố định cuối cùng: Cài đặt -> Gỡ lỗi -> Đầu vào (gộp). */
    List<Item> fixed() {
        if (fixed != null) return fixed;
        List<Item> l = new ArrayList<Item>();
        inputIdx = -1;

        // 1. Cài đặt: bấm mở Cài đặt, giữ mở menu (Mạng, Hiện). Dòng dưới là tên Wi-Fi đang kết nối hoặc "LAN"
        Item nw = new Item();
        nw.glyph = "Cài đặt";
        nw.label = netLabel();
        nw.action = new Runnable() {
            public void run() { runFixed("settings"); }
        };
        final int nwCol = l.size();
        nw.longAction = new Runnable() {
            public void run() {
                List<String> labels = new ArrayList<String>();
                List<Runnable> acts = new ArrayList<Runnable>();
                // Mạng đứng đầu; có app ẩn thì thêm Ứng dụng ẩn (focus mặc định); Gán phím sau cùng
                int def = 0;
                labels.add("Mạng");
                acts.add(new Runnable() {
                    public void run() { openNetwork(); }
                });
                if (hasHiddenApps()) {
                    def = labels.size();
                    labels.add("Ứng dụng ẩn");
                    acts.add(new Runnable() {
                        public void run() { showHiddenApps(nwCol); }
                    });
                }
                labels.add(assignLabel("fx:settings"));
                acts.add(new Runnable() {
                    public void run() { beginAssign("fx:settings", FMENU); }
                });
                showFixedMenu("Nguyễn Đức Thăng © 0374201353", nwCol, labels.toArray(new String[0]), acts.toArray(new Runnable[0]), def);
            }
        };
        l.add(nw);

        // 2. Đồng hồ: hiện giờ:phút và thứ ngày tháng; bấm mở cài đặt nhà phát triển (gỡ lỗi), giữ mở menu (tiêu đề là âm lịch;
        // Khởi động lại, Tắt nguồn, Tải ứng dụng, Gán phím)
        Item dv = new Item();
        Date now = new Date();
        dv.glyph = timeText(now);     // giờ:phút
        dv.label = dateText(now);     // thứ, ngày, tháng giống ngoài trang chủ
        dv.action = new Runnable() {
            public void run() { runFixed("dev"); }
        };
        final int dvCol = l.size();
        dv.longAction = new Runnable() {
            public void run() {
                showFixedMenu(lunarText(new Date()), dvCol, new String[] {"Khởi động lại", "Tắt nguồn", "Tải ứng dụng", assignLabel("fx:dev")}, new Runnable[] {
                    new Runnable() {
                        public void run() { power(true); }
                    },
                    new Runnable() {
                        public void run() { power(false); }
                    },
                    new Runnable() {
                        public void run() { showAppList(dvCol); }
                    },
                    new Runnable() {
                        public void run() { beginAssign("fx:dev", FMENU); }
                    }
                }, 1); // mặc định vào Tắt nguồn
            }
        };
        l.add(dv);

        // 3. Đầu vào (gộp, đứng sau Gỡ lỗi): bấm mở đầu vào dùng nhiều nhất, giữ để chọn trong danh sách
        if (!inputs().isEmpty()) {
            final Inp best = bestInput();
            Item ip = new Item();
            ip.glyph = best.label;
            ip.label = "Danh sách đầu vào";
            ip.action = new Runnable() {
                public void run() { runFixed("input"); }
            };
            ip.longAction = new Runnable() {
                public void run() { render(PICK, 0, 0); }
            };
            inputIdx = l.size();
            l.add(ip);
        }

        fixed = l;
        return l;
    }

    /** Thời điểm dùng gần nhất của từng app từ UsageStats (cần cấp quyền; không có quyền thì rỗng). */
    Map<String, Long> usage() {
        Map<String, Long> m = new HashMap<String, Long>();
        try {
            UsageStatsManager u = (UsageStatsManager) ctx.getSystemService("usagestats");
            long now = System.currentTimeMillis();
            List<UsageStats> l = u.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 30L * 24 * 3600 * 1000, now);
            if (l != null) {
                for (UsageStats s : l) {
                    Long o = m.get(s.getPackageName());
                    if (o == null || s.getLastTimeUsed() > o) m.put(s.getPackageName(), s.getLastTimeUsed());
                }
            }
        } catch (Throwable t) {}
        return m;
    }

    /** Tất cả app có thể mở, trừ launcher, app đã ghim vào dock và app đã ẩn; app dùng gần nhất lên trước. */
    /** Mọi app mở được trên TV (launcher/leanback), trừ launcher và Cài đặt: {gói, tên}. Hỏi PackageManager, nên được cache. */
    List<String[]> base() {
        if (base != null) return base;
        List<String[]> list = new ArrayList<String[]>();
        Set<String> seen = new HashSet<String>();
        List<ResolveInfo> ris = new ArrayList<ResolveInfo>();
        Intent a = new Intent(Intent.ACTION_MAIN);
        a.addCategory(LEANBACK);
        Intent b = new Intent(Intent.ACTION_MAIN);
        b.addCategory(Intent.CATEGORY_LAUNCHER);
        ris.addAll(pm.queryIntentActivities(a, 0));
        ris.addAll(pm.queryIntentActivities(b, 0));
        String setPkg = settingsPkg(); // Cài đặt mở từ nút Mạng, không lặp trong danh sách
        for (ResolveInfo ri : ris) {
            String p = ri.activityInfo.packageName;
            if (p.equals(ctx.getPackageName()) || !seen.add(p) || p.equals(setPkg) || p.equals("com.android.tv.settings")) continue;
            list.add(new String[] {p, label(p)});
        }
        base = list;
        return list;
    }

    /** Lần dùng gần nhất của từng app theo "dữ liệu sử dụng", cache 60 giây (truy vấn này khá chậm). */
    Map<String, Long> usageCached() {
        long now = SystemClock.uptimeMillis();
        if (usageCache == null || now - usageAt > 60000) {
            usageCache = usage();
            usageAt = now;
        }
        return usageCache;
    }

    List<Cand> all() {
        if (all != null) return all;
        List<Cand> list = new ArrayList<Cand>();
        Map<String, Long> us = usageCached();
        for (String[] bp : base()) {
            String p = bp[0];
            if (hidden.contains(p) || apps.contains(p)) continue;
            Cand c = new Cand();
            c.pkg = p;
            c.name = bp[1];
            c.last = usageSp.getLong(p, 0);
            c.cnt = cntSp.getInt(p, 0);
            Long u = us.get(p);
            if (u != null && u > c.last) c.last = u;
            list.add(c);
        }
        Collections.sort(list, new Comparator<Cand>() {
            public int compare(Cand x, Cand y) {
                if (x.cnt != y.cnt) return x.cnt > y.cnt ? -1 : 1;     // mở nhiều nhất lên đầu
                if (x.last != y.last) return x.last > y.last ? -1 : 1; // bằng nhau: gần nhất lên trước
                return x.name.compareToIgnoreCase(y.name);             // chưa dùng: theo tên
            }
        });
        all = list;
        return list;
    }

    boolean hasDock() { return !apps.isEmpty(); }

    /** Tổng số hàng: dock (nếu có app) + các hàng "tất cả app" + 1 hàng cố định cuối cùng. */
    int rows() {
        return (hasDock() ? 1 : 0) + (all().size() + cols() - 1) / cols() + 1;
    }

    boolean isFixedRow(int r) { return r == rows() - 1; }

    List<Item> rowItems(int r) {
        List<Item> out = new ArrayList<Item>();
        if (isFixedRow(r)) { out.addAll(fixed()); return out; }
        for (String p : rowPkgs(r)) out.add(Item.app(p));
        return out;
    }

    boolean isDockRow(int r) { return hasDock() && r == 0; }

    List<String> rowPkgs(int r) {
        List<String> out = new ArrayList<String>();
        if (hasDock()) {
            if (r == 0) { out.addAll(apps); return out; }
            r--;
        }
        List<Cand> a = all();
        int c = cols(), start = r * c, m = Math.min(a.size(), start + c) - start;
        if (m <= 0) return out;
        String[] pos = new String[m]; // hạng cao đứng gần giữa hàng, hàng trên cùng chứa các hạng cao nhất
        int[] o = centerOut(m);
        for (int k = 0; k < m; k++) pos[o[k]] = a.get(start + k).pkg;
        out.addAll(Arrays.asList(pos));
        return out;
    }

    /** Thứ tự vị trí trong hàng m ô theo hạng: giữa trước (chẵn thì ô giữa bên trái), rồi xen kẽ phải/trái ra hai bên. */
    static int[] centerOut(int m) {
        int[] o = new int[m];
        int lo = (m - 1) / 2, hi = lo + 1, k = 0;
        o[k++] = lo--;
        while (k < m) {
            if (hi < m) o[k++] = hi++;
            if (k < m && lo >= 0) o[k++] = lo--;
        }
        return o;
    }

    /** Vị trí {hàng, cột} của app hạng rank (0 = mở nhiều nhất) trong danh sách tất cả. */
    int[] posOfRank(int rank) {
        int c = cols(), r = rank / c, start = r * c, m = Math.min(all().size(), start + c) - start;
        return new int[] {r + (hasDock() ? 1 : 0), centerOut(m)[rank - start]};
    }

    // ---------- giao diện ----------
    TextView text(String s, int size, int color) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    /** Giảm cỡ chữ (sp) cho tới khi 1 dòng vừa bề rộng maxW (px), tối thiểu 7sp. */
    final android.graphics.Paint measure = new android.graphics.Paint();

    TextView fit(TextView t, int maxW) {
        float sp = t.getTextSize() / ctx.getResources().getDisplayMetrics().scaledDensity;
        android.graphics.Paint p = measure;
        p.set(t.getPaint());
        String s = t.getText().toString();
        while (sp > 7) {
            p.setTextSize(sp * ctx.getResources().getDisplayMetrics().scaledDensity);
            if (p.measureText(s) <= maxW) break;
            sp -= 0.5f;
        }
        t.setTextSize(sp);
        return t;
    }

    void render(int m, int a, int b) {
        if (!shown) return;
        mode = m;
        root.removeAllViews();
        curRow = null;
        if (m == BAR) renderBar(a, b);
        else if (m == PICK) renderPick();
        else if (m == MENU) renderMenu(a, b);
        else if (m == FMENU) renderFixedMenu();
        else if (m == MOVE) renderMove(a);
        else if (m == ASSIGN) renderAssign();
        else renderEdit(a, b);
    }

    static final int GRAY = 0xFF2A2F45; // nền xám của thẻ/nút

    GradientDrawable rounded(int color) {
        return rounded(color, dp(RADIUS_DP));
    }

    GradientDrawable rounded(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    /** Lớp kính trắng mờ hiện khi được focus/nhấn. */
    GradientDrawable glassOn() {
        return glassOn(dp(RADIUS_DP));
    }

    GradientDrawable glassOn(float radiusPx) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{0x66FFFFFF, 0x1FFFFFFF, 0x40FFFFFF});
        g.setCornerRadius(radiusPx);
        return g;
    }

    /** Nền theo trạng thái: bình thường = off, focus/nhấn = on, chuyển mờ dần. */
    Drawable fadeBg(Drawable off, Drawable on) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{android.R.attr.state_pressed}, on);
        s.addState(new int[]{}, off);
        s.setEnterFadeDuration(140);
        s.setExitFadeDuration(140);
        return s;
    }

    /** Lớp phủ kính trong suốt của ô: chỉ hiện khi ô được focus, bo góc cùng icon. */
    Drawable glassBg() {
        return fadeBg(rounded(0), glassOn());
    }

    /** Nền nút tuỳ chọn: xám như thẻ ở hàng dưới cùng, được focus thì phủ kính giống các ô. */
    Drawable buttonBg() {
        float rz = dp(RADIUS_DP) * FOCUS_SCALE * 1.5f; // khi phóng to bo góc lớn hơn để không bị nhìn nhỏ đi
        return fadeBg(rounded(GRAY), new LayerDrawable(new Drawable[]{rounded(GRAY, rz), glassOn(rz)}));
    }

    /** Thêm lớp kính phủ lên trên nội dung của ô (gọi sau khi đã addView nội dung). */
    void addGlass(FrameLayout cell, int w, int h) {
        View g = new View(ctx);
        g.setDuplicateParentStateEnabled(true); // theo trạng thái focus của ô
        g.setBackgroundDrawable(glassBg());
        cell.addView(g, new FrameLayout.LayoutParams(w, h));
    }

    /** Phóng to khi focus; translationZ để ô đang focus được vẽ đè lên các ô bên cạnh. */
    final View.OnFocusChangeListener zoom = new View.OnFocusChangeListener() {
        public void onFocusChange(View v, boolean f) {
            float s = f ? FOCUS_SCALE : 1f;
            v.animate().scaleX(s).scaleY(s).translationZ(f ? dp(8) : 0).setDuration(140).start();
        }
    };

    FrameLayout makeCell(int pad) {
        FrameLayout c = new FrameLayout(ctx);
        c.setPadding(pad, pad, pad, pad);
        c.setFocusable(true);
        c.setClickable(true);
        c.setLongClickable(true);
        c.setOnFocusChangeListener(zoom);
        return c;
    }

    /** Bo 4 góc view bằng outline. */
    View round(View v) {
        final int r = dp(RADIUS_DP);
        v.setOutlineProvider(new ViewOutlineProvider() {
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        v.setClipToOutline(true);
        return v;
    }

    /** Thẻ thay thế khi không có banner: icon (hoặc ký hiệu lớn) + tên trên nền tối. */
    View card(String label, Drawable ic, String glyph, int w, int h) {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.setBackgroundDrawable(rounded(GRAY));
        if (ic != null) {
            ImageView iv = new ImageView(ctx);
            iv.setImageDrawable(ic);
            c.addView(iv, new LinearLayout.LayoutParams(h * 45 / 100, h * 45 / 100));
        } else if (glyph != null) {
            TextView gv = text(glyph, glyph.length() > 9 ? 13 : 20, 0xFFFFFFFF);
            gv.setSingleLine(true);
            gv.setGravity(Gravity.CENTER);
            gv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            fit(gv, w - dp(8));
            c.addView(gv, new LinearLayout.LayoutParams(w - dp(8), h * 45 / 100));
        }
        TextView tv = text(label, 11, 0xFFFFFFFF);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER);
        fit(tv, w - dp(8));
        c.addView(tv, new LinearLayout.LayoutParams(w - dp(8), ViewGroup.LayoutParams.WRAP_CONTENT));
        return c;
    }

    /** Ảnh 16:9 (bo góc): banner nếu có, không thì thẻ icon + tên. */
    View thumb(Item it, int w, int h) {
        android.graphics.Bitmap cb = it.pkg != null ? customIcon(it.pkg) : null;
        if (cb != null) { // ảnh tuỳ chỉnh: phóng to lấp đầy ô 16:9, cắt phần thừa
            ImageView iv = new ImageView(ctx);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(cb);
            return round(iv);
        }
        Drawable bn = it.pkg != null ? banner(it.pkg) : null;
        if (bn != null) {
            ImageView iv = new ImageView(ctx);
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            iv.setImageDrawable(bn);
            return round(iv);
        }
        String lb = it.pkg != null ? label(it.pkg) : it.label;
        Drawable ic = it.pkg != null ? icon(it.pkg) : null;
        return round(card(lb, ic, it.glyph, w, h));
    }

    /** Chuyển lên/xuống 1 hàng, giữ cột theo vị trí ngang (hàng cuối có thể ít ô hơn, căn giữa). */
    void moveRow(int dir, int col) {
        int nr = curRowIdx + dir;
        if (nr < 0 || nr >= rows()) return;
        int kcur = rowItems(curRowIdx).size();
        int knew = rowItems(nr).size();
        int c = Math.max(cols(), Math.max(kcur, knew));
        float gx = col + (c - kcur) / 2f;
        int nc = Math.round(gx - (c - knew) / 2f); // căn theo tâm; số ô khác chẵn/lẻ thì lệch .5 và làm tròn lên
        if (kcur % 2 == 1 && knew % 2 == 0) nc--;  // từ hàng lẻ sang hàng chẵn: lùi focus 1 ô (chẵn sang lẻ giữ nguyên)
        render(BAR, nr, Math.max(0, Math.min(knew - 1, nc)));
    }

    /** Giữ vào app trong danh sách tất cả: thêm thẳng vào dock chính. */
    /** Hạng của app trong danh sách tất cả (0 = mở nhiều nhất). */
    int rankOf(String p) {
        List<Cand> a = all();
        for (int i = 0; i < a.size(); i++) if (a.get(i).pkg.equals(p)) return i;
        return 0;
    }

    /** Dựng lại thanh với focus vào app đang ở hạng rank của danh sách tất cả (hết app thì về dock). */
    void renderAtRank(int rank) {
        int n = all().size();
        if (n == 0) {
            render(BAR, 0, Math.max(0, apps.size() - 1));
            return;
        }
        int[] ps = posOfRank(Math.min(rank, n - 1));
        render(BAR, ps[0], ps[1]);
    }

    void addToDock(String p, int row, int col) {
        if (apps.size() >= MAX_APPS) {
            toast("Thanh chính đã đủ " + MAX_APPS + " ứng dụng");
            render(BAR, row, col);
            return;
        }
        int rank = rankOf(p); // sau khi thêm vẫn focus đúng chỗ đó trong danh sách
        apps.add(p);
        save();
        all = null;
        toast("Đã thêm " + label(p) + " vào thanh chính");
        renderAtRank(rank);
    }

    boolean hasHiddenApps() {
        for (String p : hidden) if (installed(p)) return true;
        return false;
    }

    /** Nút "Ứng dụng ẩn": menu giữ liệt kê các app đã ẩn (theo tên), bấm vào để mở app đó. */
    void showHiddenApps(int col) {
        final List<String[]> hid = new ArrayList<String[]>(); // {tên, gói}
        for (String p : hidden) if (installed(p)) hid.add(new String[] {label(p), p});
        if (hid.isEmpty()) {
            toast("Không có ứng dụng nào đang ẩn");
            return;
        }
        Collections.sort(hid, new Comparator<String[]>() {
            public int compare(String[] x, String[] y) { return x[0].compareToIgnoreCase(y[0]); }
        });
        String[] labels = new String[hid.size()];
        Runnable[] acts = new Runnable[hid.size()], longs = new Runnable[hid.size()];
        final int fc = col;
        for (int i = 0; i < labels.length; i++) {
            final String p = hid.get(i)[1];
            labels[i] = hid.get(i)[0];
            acts[i] = new Runnable() {
                public void run() { launch(p); }
            };
            longs[i] = new Runnable() {
                public void run() { unhideApp(p, fc); }
            };
        }
        showFixedMenu("Ứng dụng ẩn (bấm giữ một ứng dụng để hiện lại)", col, labels, acts, (labels.length - 1) / 2, longs); // mặc định vào ô giữa như dock
    }

    /** Giữ nút một app trong danh sách "Ứng dụng ẩn": bỏ ẩn, app hiện lại trong danh sách tất cả. Rồi mở lại danh sách còn lại. */
    void unhideApp(String p, int col) {
        hidden.remove(p);
        save();
        all = null;
        toast("Đã hiện lại " + label(p) + " trong danh sách tất cả");
        if (hasHiddenApps()) showHiddenApps(col);
        else render(BAR, rows() - 1, col);
    }

    /** Menu giữ một ô ở hàng cố định: mỗi nút chạy hành động của nó rồi quay lại thanh; Back quay lại đúng ô. */
    void showFixedMenu(String title, int col, String[] labels, Runnable[] acts, int def) {
        showFixedMenu(title, col, labels, acts, def, null);
    }

    /** longs: hành động khi bấm giữ từng nút (null = không có). */
    void showFixedMenu(String title, int col, String[] labels, Runnable[] acts, int def, Runnable[] longs) {
        fmLong = longs;
        fmGen++;
        fmDef = def;
        fmTitle = title;
        fmCol = col;
        fmLabels = labels;
        fmActs = acts;
        render(FMENU, rows() - 1, col);
    }

    void renderFixedMenu() {
        LinearLayout r = optionPanel(fmTitle);
        for (int i = 0; i < fmLabels.length; i++) {
            final Runnable act = fmActs[i];
            TextView tb = button(fmLabels[i], new View.OnClickListener() {
                public void onClick(View v) {
                    int g = fmGen;
                    act.run();
                    if (shown && mode == FMENU && g == fmGen) render(BAR, rows() - 1, fmCol); // hành động không mở app/menu khác: quay lại thanh
                }
            });
            final Runnable lact = fmLong != null ? fmLong[i] : null;
            if (lact != null) {
                tb.setOnLongClickListener(new View.OnLongClickListener() {
                    public boolean onLongClick(View v) {
                        int g = fmGen;
                        lact.run();
                        if (shown && mode == FMENU && g == fmGen) render(BAR, rows() - 1, fmCol);
                        return true;
                    }
                });
            }
            r.addView(tb);
        }
        finishPanel(r, rows() - 1, fmCol, -1, "fm" + fmCol + fmTitle, fmDef);
    }

    /** Dựng 1 hàng ô. interactive=false: chỉ để xem (không focus/bấm), ô hlIdx được phóng to để đánh dấu. */
    LinearLayout buildRow(final int row, boolean interactive, int hlIdx) {
        final boolean dock = isDockRow(row);
        final boolean fixedRow = isFixedRow(row);
        List<Item> pk = rowItems(row);

        final int pad = 0, m = gap() / 2;
        int totalW = cellW(pk.size());
        int innerW = totalW - 2 * pad;
        int innerH = innerW * 9 / 16; // banner 16:9
        int totalH = innerH + 2 * pad;

        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setClipChildren(false); // để ô phóng to không bị cắt
        for (int i = 0; i < pk.size(); i++) {
            final int idx = i;
            final Item it = pk.get(i);
            final String p = it.pkg;
            FrameLayout cell = makeCell(pad);
            cell.addView(thumb(it, innerW, innerH), new FrameLayout.LayoutParams(innerW, innerH));
            if (interactive) {
                addGlass(cell, innerW, innerH);
                cell.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        if (p != null) {
                            focusPkg = p; // lần hiện dock sau (bấm Home) focus lại đúng app này
                            if (launch(p)) cntSp.edit().putInt(p, cntSp.getInt(p, 0) + 1).apply(); // chỉ tính lượt người dùng bấm mở
                        } else if (it.action != null) it.action.run();
                    }
                });
                cell.setOnLongClickListener(new View.OnLongClickListener() {
                    public boolean onLongClick(View v) {
                        if (it.longAction != null) { it.longAction.run(); return true; }
                        if (fixedRow || p == null) return true; // hàng cố định: không chỉnh sửa
                        if (dock) render(EDIT, idx, -1); // focus vào nút đã nhớ của menu dock
                        else render(MENU, row, idx);
                        return true;
                    }
                });
            } else {
                cell.setFocusable(false);
                cell.setClickable(false);
                cell.setLongClickable(false);
                if (i == hlIdx) { cell.setScaleX(FOCUS_SCALE); cell.setScaleY(FOCUS_SCALE); }
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(totalW, totalH);
            lp.setMargins(m, m, m, m);
            r.addView(cell, lp);
        }
        return r;
    }

    /** Chỉ hiện đúng 1 hàng: hàng 0 = dock chính, các hàng sau = tất cả app còn lại. */
    void renderBar(int row, int col) {
        row = Math.max(0, Math.min(row, rows() - 1));
        if (isFixedRow(row)) fixed = null; // ô giờ:phút và tên Wi-Fi luôn mới khi vào hàng này
        curRowIdx = row;
        LinearLayout r = buildRow(row, true, -1);
        curRow = r;
        root.addView(r, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        r.getChildAt(Math.max(0, Math.min(col, r.getChildCount() - 1))).requestFocus();
    }

    TextView button(String s, View.OnClickListener l) {
        TextView b = text(s, BTN_SP, 0xFFFFFFFF);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(BTN_PAD_DP), dp(BTN_PADV_DP), dp(BTN_PAD_DP), dp(BTN_PADV_DP));
        b.setFocusable(true);
        b.setClickable(true);
        b.setBackgroundDrawable(buttonBg());
        b.setOnFocusChangeListener(zoom);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(panelGap() / 2, 0, panelGap() / 2, 0);
        b.setLayoutParams(lp);
        return b;
    }

    /** Khung tuỳ chọn: tiêu đề + hàng nút ở trên (trả về để thêm nút), hàng ô giữ nguyên vị trí ở dưới (finishPanel). */
    // Thanh bấm giữ (menu giữ ở mọi dòng): cỡ chữ, đệm nút và khoảng cách nhỏ hơn dock một chút
    static final int BTN_SP = 16, BTN_PAD_DP = 17, BTN_PADV_DP = 12;

    /** Khoảng cách giữa các nút của thanh bấm giữ: nhỏ hơn khoảng cách ô của dock một chút. */
    int panelGap() { return Math.max(dp(4), gap() * 3 / 4); }

    LinearLayout optionPanel(String title) {
        TextView t = text(title, BTN_SP, 0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, 0, 0, dp(10));
        root.addView(t, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setClipChildren(false); // nút phóng to không bị cắt
        row.setPadding(0, 0, 0, dp(12));
        root.addView(row);
        return row;
    }

    /** Thêm hàng ô (chỉ để xem, ô hlIdx được đánh dấu) xuống dưới khung tuỳ chọn và focus vào nút thứ focus. */
    void finishPanel(LinearLayout row, int tileRow, int hlIdx, int focus) {
        finishPanel(row, tileRow, hlIdx, focus, null);
    }

    /** Vị trí nút đang focus của từng menu giữ (theo key), nhớ tới khi tắt launcher: mở lại dock/trang chủ không bị reset. */
    final Map<String, Integer> menuFocus = new HashMap<String, Integer>();

    /**
     * Thêm hàng ô phía trên rồi focus vào nút. focus < 0 (kèm key) thì focus vào nút đã nhớ của menu đó; có key thì mỗi lần
     * focus đổi sẽ được nhớ lại.
     */
    void finishPanel(LinearLayout row, int tileRow, int hlIdx, int focus, String key) {
        finishPanel(row, tileRow, hlIdx, focus, key, 0);
    }

    /** def: nút focus mặc định khi chưa có vị trí nhớ (focus < 0). */
    void finishPanel(LinearLayout row, int tileRow, int hlIdx, int focus, final String key, int def) {
        fitPanel(row);
        root.addView(buildRow(tileRow, false, hlIdx), new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int n = row.getChildCount();
        if (n == 0) return;
        if (key != null) {
            for (int i = 0; i < n; i++) {
                final int ci = i;
                row.getChildAt(i).setOnFocusChangeListener(new View.OnFocusChangeListener() {
                    public void onFocusChange(View v, boolean has) {
                        zoom.onFocusChange(v, has);
                        if (has) menuFocus.put(key, ci);
                    }
                });
            }
        }
        Integer saved = key != null ? menuFocus.get(key) : null;
        int f = focus >= 0 ? focus : (saved != null ? saved : def);
        row.getChildAt(Math.max(0, Math.min(f, n - 1))).requestFocus();
    }

    /** Nhiều nút quá thì thu nhỏ chữ và đệm ngang để cả hàng nằm gọn trong màn hình (chừa chỗ khi nút phóng to). */
    void fitPanel(LinearLayout row) {
        int n = row.getChildCount();
        if (n == 0) return;
        float sd = ctx.getResources().getDisplayMetrics().scaledDensity;
        int avail = (int) ((ctx.getResources().getDisplayMetrics().widthPixels - 2 * rootPad()) * 0.9f);
        float sp = BTN_SP;
        for (; sp > 10; sp -= 1) {
            int total = 0;
            for (int i = 0; i < n; i++) {
                View v = row.getChildAt(i);
                if (!(v instanceof TextView)) continue;
                android.graphics.Paint pt = measure;
                pt.set(((TextView) v).getPaint());
                pt.setTextSize(sp * sd);
                total += (int) pt.measureText(((TextView) v).getText().toString()) + 2 * dp((int) (BTN_PAD_DP * sp / BTN_SP)) + panelGap();
            }
            if (total <= avail) break;
        }
        for (int i = 0; i < n; i++) {
            View v = row.getChildAt(i);
            if (!(v instanceof TextView)) continue;
            ((TextView) v).setTextSize(sp);
            int ph = dp((int) (BTN_PAD_DP * sp / BTN_SP));
            v.setPadding(ph, v.getPaddingTop(), ph, v.getPaddingBottom());
        }
    }

    /** Menu giữ icon trong danh sách tất cả: Lên, Thông tin, Gỡ bỏ, Buộc dừng, Hiện (nếu có app đã ẩn). */
    void renderMenu(final int row, final int col) {
        menuRow = row;
        menuCol = col;
        final String p = rowItems(row).get(col).pkg;
        LinearLayout r = optionPanel(label(p));
        r.addView(button("Lên", new View.OnClickListener() {
            public void onClick(View v) { addToDock(p, row, col); }
        }));
        r.addView(button("Thông tin", new View.OnClickListener() {
            public void onClick(View v) { openInfo(p); }
        }));
        r.addView(button("Gỡ bỏ", new View.OnClickListener() {
            public void onClick(View v) { uninstallApp(p); }
        }));
        r.addView(button("Mở riêng", new View.OnClickListener() {
            public void onClick(View v) { openExclusive(p); }
        }));
        r.addView(button("Buộc dừng", new View.OnClickListener() {
            public void onClick(View v) { forceStopApp(p); }
        }));
        r.addView(button(assignLabel(p), new View.OnClickListener() {
            public void onClick(View v) { beginAssign(p, MENU); }
        }));
        r.addView(button("Ẩn", new View.OnClickListener() {
            public void onClick(View v) {
                int rank = rankOf(p);
                hidden.add(p);
                save();
                all = null; // app đã ẩn không còn ở hàng nào
                toast("Đã ẩn " + label(p));
                renderAtRank(rank);
            }
        }));
        finishPanel(r, row, col, -1, "menu", 3); // mặc định vào "Mở riêng"
    }

    /** Danh sách chọn đầu vào (hiện khi giữ nút Đầu vào). */
    void renderPick() {
        LinearLayout row = optionPanel("Chọn đầu vào");
        List<Inp> list = inputs();
        Inp best = list.isEmpty() ? null : bestInput();
        int focus = 0;
        for (int k = 0; k < list.size(); k++) {
            final Inp i = list.get(k);
            if (i == best) focus = k;
            row.addView(button(i.label, new View.OnClickListener() {
                public void onClick(View v) { launchInput(i); }
            }));
        }
        row.addView(button(assignLabel("fx:input"), new View.OnClickListener() {
            public void onClick(View v) { beginAssign("fx:input", PICK); }
        }));
        finishPanel(row, rows() - 1, inputIdx, focus);
    }

    /** Chỉnh sửa 1 app của dock: dịch trái/phải, xoá khỏi dock, ẩn khỏi mọi hàng, hiện lại các app đã ẩn. */
    /**
     * Chế độ di chuyển có 2 trạng thái (phím trái/phải xử lý trong Root): đang di chuyển (trái/phải đổi chỗ icon, OK để xong)
     * và đang chọn (trái/phải chọn icon khác, OK để di chuyển icon đó). Back hoặc phím xuống thì về menu giữ icon.
     */
    void renderMove(int idx) {
        editIdx = idx;
        LinearLayout row = optionPanel("Di chuyển: " + label(apps.get(idx)));
        row.addView(button(moving ? "⇄" : "Chọn ứng dụng khác để di chuyển",
            new View.OnClickListener() {
                public void onClick(View v) {
                    moving = !moving;
                    render(MOVE, editIdx, 0);
                }
            }));
        finishPanel(row, 0, idx, 0);
    }

    /** Phím trái/phải trong chế độ di chuyển: đổi chỗ icon (đang di chuyển) hoặc chọn icon khác (đang chọn). */
    void moveIcon(int d) {
        int to = editIdx + d;
        if (to < 0 || to >= apps.size()) return;
        if (moving) {
            Collections.swap(apps, editIdx, to);
            save();
        }
        render(MOVE, to, 0);
    }

    // ---------- gán phím điều khiển mở app (qua dịch vụ trợ năng KeyService) ----------

    /** Dịch vụ trợ năng của app đã bật chưa. */
    boolean accessibilityOn() {
        String s = Settings.Secure.getString(ctx.getContentResolver(), "enabled_accessibility_services");
        return s != null && s.contains(ctx.getPackageName() + "/") && s.contains("KeyService");
    }

    /** Cấp quyền trợ năng: bật dịch vụ qua ADB nếu có; không thì mở màn hình Trợ năng để bật tay. Chạy ở nền. */
    void enableAccessibility() {
        new Thread(new Runnable() {
            public void run() {
                AdbClient a = null;
                try {
                    if (Adb.ready(ctx)) {
                        String comp = ctx.getPackageName() + "/" + KeyService.class.getName();
                        String cur = Settings.Secure.getString(ctx.getContentResolver(), "enabled_accessibility_services");
                        String v = cur == null || cur.length() == 0 ? comp : cur + ":" + comp;
                        a = Adb.open(ctx.getApplicationContext());
                        a.shell("settings put secure enabled_accessibility_services " + v
                            + "; settings put secure accessibility_enabled 1");
                        toast("Đã bật quyền trợ năng cho BarTV");
                        return;
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (a != null) a.close();
                }
                toast("Hãy bật dịch vụ BarTV trong Trợ năng rồi gán phím lại");
                handler.post(new Runnable() {
                    public void run() {
                        launchDream(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    }
                });
            }
        }).start();
    }

    /** Chức năng của các ô cố định (bấm nút): "settings", "input" (đầu vào dùng nhiều nhất), "dev". */
    void runFixed(String id) {
        if (id.equals("settings")) {
            openSettings();
        } else if (id.equals("input")) {
            if (!inputs().isEmpty()) launchInput(bestInput());
        } else if (id.equals("dev")) {
            // Đã cấp quyền trợ năng: mở xong thì đưa focus tới dòng Gỡ lỗi qua Wi-Fi / USB
            if (startOutside(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), settingsPkg())) KeyService.focusDebugging(ctx);
        }
    }

    static String fixedName(String id) {
        return id.equals("settings") ? "Cài đặt" : id.equals("input") ? "Đầu vào" : "Gỡ lỗi";
    }

    /** Tên hiển thị của đích gán phím: app hoặc chức năng ô cố định. */
    String targetName(String t) {
        return t.startsWith("fx:") ? fixedName(t.substring(3)) : label(t);
    }

    /** Chạy đích đã gán: chức năng ô cố định hoặc mở app. false nếu không mở được (app đã gỡ). */
    boolean runTarget(String t) {
        if (t.startsWith("fx:")) {
            runFixed(t.substring(3));
            return true;
        }
        return launch(t);
    }

    /** Nhãn nút gán phím của đích t: đã có phím thì là "Sửa phím tắt" (hành động vẫn là chờ phím mới). */
    String assignLabel(String t) {
        for (Object v : keySp.getAll().values()) if (t.equals(v)) return "Sửa phím tắt";
        return "Gán phím";
    }

    /** Vào chế độ chờ phím để gán mở app / chạy chức năng t; from = EDIT, MENU, FMENU hoặc PICK là menu để quay lại. */
    void beginAssign(String t, int from) {
        assignTo = t;
        assignFrom = from;
        render(ASSIGN, 0, 0);
        if (!accessibilityOn()) enableAccessibility();
    }

    void renderAssign() {
        final String p = assignTo;
        LinearLayout row = optionPanel("Gán phím: " + targetName(p));
        row.addView(button("Bấm phím bất kỳ để gán mở app này (cần quyền trợ năng)", new View.OnClickListener() {
            public void onClick(View v) { clearAssign(); } // bấm OK: huỷ phím đã gán cho đích này
        }));
        if (assignFrom == EDIT) finishPanel(row, 0, editIdx, 0);
        else if (assignFrom == MENU) finishPanel(row, menuRow, menuCol, 0);
        else if (assignFrom == FMENU) finishPanel(row, rows() - 1, fmCol, 0);
        else finishPanel(row, rows() - 1, inputIdx, 0);
    }

    /** Phím điều hướng, âm lượng, nguồn: không gán được (gán sẽ làm hỏng thao tác cơ bản). */
    static boolean reservedKey(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_HOME: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_VOLUME_UP: case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_POWER: case KeyEvent.KEYCODE_TV_POWER: case KeyEvent.KEYCODE_UNKNOWN:
                return true;
            default:
                return false;
        }
    }

    /** Nhận mọi phím điều khiển từ KeyService. true = đã xử lý (nuốt phím), false = để hệ thống/app xử lý bình thường. */
    boolean onGlobalKey(KeyEvent e) {
        int k = e.getKeyCode();
        boolean first = e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0;
        if (assignTo != null && shown && mode == ASSIGN) { // đang chờ gán: phím đầu tiên (không phải điều hướng) là phím được gán
            if (reservedKey(k)) return false;
            if (first) assignKey(k);
            return true;
        }
        String p = keySp.getString("k" + k, null);
        if (p == null) return false;
        if (first && !runTarget(p)) keySp.edit().remove("k" + k).apply(); // app đã gỡ: bỏ gán
        return true;
    }

    /** Huỷ phím đã gán cho đích đang chọn (OK trong chế độ chờ phím) rồi quay lại menu. */
    void clearAssign() {
        String p = assignTo;
        SharedPreferences.Editor ed = keySp.edit();
        boolean had = false;
        for (Map.Entry<String, ?> en : keySp.getAll().entrySet()) {
            if (p.equals(en.getValue())) {
                ed.remove(en.getKey());
                had = true;
            }
        }
        ed.apply();
        toast(had ? "Đã huỷ gán phím cho " + targetName(p) : targetName(p) + " chưa được gán phím");
        panelBack(); // quay lại menu
    }

    void assignKey(int k) {
        String p = assignTo;
        SharedPreferences.Editor ed = keySp.edit();
        for (Map.Entry<String, ?> en : keySp.getAll().entrySet()) // mỗi app một phím: bỏ phím cũ của app này
            if (p.equals(en.getValue())) ed.remove(en.getKey());
        ed.putString("k" + k, p).apply();
        toast("Đã gán phím " + KeyEvent.keyCodeToString(k).replace("KEYCODE_", "") + " mở " + targetName(p));
        panelBack(); // quay lại menu
    }

    /** Back (hoặc phím xuống) trong thanh bấm giữ: lùi một bước, menu giữ thì về thanh ở đúng ô. */
    void panelBack() {
        if (mode == ASSIGN) {
            assignTo = null;
            if (assignFrom == EDIT) render(EDIT, editIdx, -1);
            else if (assignFrom == MENU) render(MENU, menuRow, menuCol);
            else if (assignFrom == FMENU) render(FMENU, rows() - 1, fmCol);
            else render(PICK, 0, 0);
        } else if (mode == MOVE) render(BAR, 0, editIdx); // xong di chuyển: về thẳng dock, bỏ qua menu giữ
        else if (mode == EDIT) render(BAR, 0, editIdx);
        else if (mode == MENU) render(BAR, menuRow, menuCol);
        else if (mode == FMENU) render(BAR, rows() - 1, fmCol);
        else if (mode == PICK) render(BAR, rows() - 1, Math.max(0, inputIdx));
    }

    void renderEdit(final int idx, int focus) {
        editIdx = idx;
        final String p = apps.get(idx);
        LinearLayout row = optionPanel("Chỉnh sửa: " + label(p));

        row.addView(button("Di chuyển", new View.OnClickListener() {
            public void onClick(View v) {
                moving = true;
                render(MOVE, idx, 0);
            }
        }));
        row.addView(button("Thông tin", new View.OnClickListener() {
            public void onClick(View v) { openInfo(p); }
        }));
        row.addView(button("Gỡ bỏ", new View.OnClickListener() {
            public void onClick(View v) { uninstallApp(p); }
        }));
        row.addView(button("Mở riêng", new View.OnClickListener() {
            public void onClick(View v) { openExclusive(p); }
        }));
        row.addView(button("Buộc dừng", new View.OnClickListener() {
            public void onClick(View v) { forceStopApp(p); }
        }));
        row.addView(button(assignLabel(p), new View.OnClickListener() {
            public void onClick(View v) { beginAssign(p, EDIT); }
        }));
        row.addView(button("Xuống", new View.OnClickListener() {
            public void onClick(View v) {
                apps.remove(idx);
                save();
                all = null; // app vừa xoá quay về danh sách tất cả
                render(BAR, 0, Math.min(idx, apps.size() - 1));
            }
        }));
        finishPanel(row, 0, idx, focus, "edit", 3); // mặc định vào "Mở riêng"
    }
}
