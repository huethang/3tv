package thang.math;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Trang chủ toàn màn hình: Activity đặc (một lớp riêng như app thường) gồm nền màu trôi + dock luôn hiện nằm trong
 * Activity (không overlay). Nút Home do MainActivity (trong suốt) nhận để không chớp đen. Dọn RAM chỉ chạy sau khi mở app.
 */
public class HomeActivity extends Activity {
    static boolean askedLocation;
    static boolean openRecent = true; // chỉ 1 lần cho mỗi lần tiến trình launcher khởi động
    boolean wasStopped;   // đã bị che hẳn (onStop): lần resume kế tiếp là vào lại trang chủ từ app khác
    boolean setupShown;   // đang hiện màn hình xin quyền (không dựng lại dock lúc này)
    TextView dateView, clockView, lunarView;      // ngày tháng, giờ:phút và âm lịch ở giữa trang chủ
    LinearLayout clockBox;                        // khối chứa hai dòng trên, đệm dưới bằng chiều cao dock
    int dockH;                                    // chiều cao dock ở trạng thái thanh (không tính menu giữ)
    final Handler handler = new Handler();
    final Runnable tickTask = new Runnable() {
        public void run() { updateClock(); }
    };
    FrameLayout host;     // nội dung của Activity: dock là view nằm trong này (không overlay)

    // Nền màu trôi liên tục: mỗi chặng nội suy (HSV) từ màu hiện tại sang một màu ngẫu nhiên mới
    final ColorDrawable bg = new ColorDrawable(0);
    ValueAnimator anim;
    final float[] from = new float[3], to = new float[3];
    long lastDraw;

    void pickTarget() {
        // đổi hue 40-120 độ (hướng ngẫu nhiên), s/v dịch nhẹ để không bị gắt
        float dir = Math.random() < 0.5 ? -1f : 1f;
        to[0] = (from[0] + dir * (40f + (float) Math.random() * 80f) + 360f) % 360f;
        to[1] = 0.4f + (float) Math.random() * 0.6f;
        to[2] = 0.3f + (float) Math.random() * 0.4f;
    }

    void startBg() {
        if (anim != null && anim.isRunning()) return; // đang trôi thì giữ nguyên, không bị giật
        Color.colorToHSV(Bar.bgColor, from);
        pickTarget();
        bg.setColor(Bar.bgColor);
        getWindow().setBackgroundDrawable(bg);
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(12000);
        anim.setInterpolator(new LinearInterpolator());
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) {
                long n = SystemClock.uptimeMillis();
                if (n - lastDraw < 100) return; // màu đổi rất chậm: ~10 khung/giây là đủ mượt, nhẹ máy hơn
                lastDraw = n;
                float t = a.getAnimatedFraction();
                float dh = ((to[0] - from[0] + 540f) % 360f) - 180f; // đường ngắn nhất trên vòng hue
                float[] c = {
                    (from[0] + dh * t + 360f) % 360f,
                    from[1] + (to[1] - from[1]) * t,
                    from[2] + (to[2] - from[2]) * t
                };
                Bar.bgColor = Color.HSVToColor(c);
                bg.setColor(Bar.bgColor);
            }
        });
        anim.addListener(new AnimatorListenerAdapter() {
            public void onAnimationRepeat(Animator a) {
                System.arraycopy(to, 0, from, 0, 3); // chặng mới bắt đầu đúng tại màu vừa tới
                pickTarget();
            }
        });
        anim.start();
    }

    void stopBg() {
        if (anim != null) { anim.cancel(); anim = null; }
    }

    /** Khối ngày tháng + đồng hồ số chữ trắng to nằm giữa màn hình trang chủ (dock nằm đè phía dưới). */
    LinearLayout clockBox() {
        LinearLayout box = clockBox = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        dockH = (int) (getResources().getDisplayMetrics().heightPixels * 0.28f); // ước lượng ban đầu, chỉnh lại khi dock đã dựng
        box.setPadding(0, 0, 0, dockH);
        dateView = new TextView(this);
        dateView.setTextColor(0xFFFFFFFF);
        dateView.setTextSize(30);
        dateView.setGravity(Gravity.CENTER);
        dateView.setShadowLayer(6, 0, 2, 0x80000000); // dễ đọc trên cả nền sáng
        clockView = new TextView(this);
        clockView.setTextColor(0xFFFFFFFF);
        clockView.setTextSize(120);
        clockView.setGravity(Gravity.CENTER);
        clockView.setIncludeFontPadding(false);
        clockView.setShadowLayer(10, 0, 3, 0x80000000);
        lunarView = new TextView(this);
        lunarView.setTextColor(0xFFFFFFFF);
        lunarView.setTextSize(22);
        lunarView.setGravity(Gravity.CENTER);
        lunarView.setShadowLayer(6, 0, 2, 0x80000000);
        box.addView(dateView);
        box.addView(clockView);
        box.addView(lunarView);
        return box;
    }

    /** Cập nhật ngày giờ rồi hẹn lại đúng đầu phút kế tiếp (chỉ hiện giờ:phút nên mỗi phút mới cần vẽ lại một lần). */
    void updateClock() {
        Date now = new Date();
        Bar bar = Bar.get(this);
        dateView.setText(bar.dateText(now));
        clockView.setText(bar.timeText(now));
        lunarView.setText(bar.lunarText(now));
        handler.removeCallbacks(tickTask);
        handler.postDelayed(tickTask, 60000 - System.currentTimeMillis() % 60000 + 50);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Bar.homeExists = true;
        host = new FrameLayout(this);
        host.addView(clockBox(), new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(host);
        // Đồng hồ nằm giữa vùng từ mép trên màn hình tới mép trên dock: đệm dưới của khối bằng chiều cao dock lúc ở dạng thanh
        host.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            public void onGlobalLayout() {
                Bar bar = Bar.get(HomeActivity.this);
                if (bar.inHost && bar.root != null && bar.mode == Bar.BAR) {
                    int h = bar.root.getHeight();
                    if (h > 0 && h != dockH) {
                        dockH = h;
                        clockBox.setPadding(0, 0, 0, h);
                    }
                }
            }
        });
        Bar.get(this).host = host;
        Updater.cleanup(this); // xoá APK cập nhật còn sót (không đụng tới ảnh icon đã lưu)
        Adb.firstRunProbe(this); // chưa kết nối ADB cổng 5555 được thì thử lại ở nền
        // Từ Android 8.1, đọc tên Wi-Fi cần quyền vị trí: xin 1 lần cho mỗi lần launcher khởi động
        if (!askedLocation && Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            askedLocation = true;
            requestPermissions(new String[] {Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION}, 1); // Android 10+ cần quyền chính xác
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        Bar.homeStarted = true;
    }

    @Override
    protected void onStop() {
        Bar.homeStarted = false;
        wasStopped = true;
        Bar bar = Bar.get(this);
        if (bar.inHost) bar.hideNow(); // bị che hẳn (đã mở app khác): bỏ dock cho nhẹ, vào lại sẽ dựng lại
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Bar.launcherResumed = true;
        updateClock();
        Bar bar = Bar.get(this);
        if (wasStopped || !bar.inHost) { // mới khởi động, từ app khác quay về, hoặc còn dock overlay cũ: dựng lại trang chủ
            wasStopped = false;
            enter();
        } else { // chỉ bị MainActivity (nút Home) che trong chốc lát: giữ nguyên dock, không dựng lại gì
            startBg();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Bar.launcherResumed = false;
        handler.removeCallbacks(tickTask); // không hiện thì không cần cập nhật đồng hồ
        stopBg(); // không hiện nền thì không cần chạy hiệu ứng
    }

    @Override
    protected void onDestroy() {
        Bar.homeExists = false;
        handler.removeCallbacks(tickTask);
        stopBg();
        Bar bar = Bar.get(this);
        bar.hideNow();
        bar.host = null;
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        Bar bar = Bar.get(this);
        if (bar.shown) return super.dispatchKeyEvent(e); // dock nằm trong Activity: tự xử lý phím (cả Back)
        if (e.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) return true; // Back không làm gì ở trang chủ
        // Trang chủ luôn phải có dock: nếu vì lý do nào đó dock không có, phím bất kỳ dựng lại
        if (e.getAction() == android.view.KeyEvent.ACTION_DOWN && !setupShown) {
            if (!bar.show()) showSetup();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onBackPressed() {
        // Launcher không tự đóng
    }

    /** Vào trang chủ: nền màu ngẫu nhiên trôi + dock toàn màn hình (cần quyền overlay cho dock khi bấm Home trên app). */
    void enter() {
        // Cần quyền overlay ngay từ đầu (thiếu quyền thì dock overlay bị ẩn, dễ treo màn hình): chưa có thì xin trước
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            showSetup();
            return;
        }
        if (setupShown) {
            setupShown = false;
            setContentView(host);
        }
        Bar bar = Bar.get(this);
        Bar.appBeneath = false;
        Bar.bgColor = Bar.randomColor();
        bar.focusPkg = null; // vào trang chủ: focus mặc định
        bar.lastApp = bar.prevApp = null; // vào trang chủ: không còn app nào cần giữ sống bên dưới
        bar.hideNow();
        startBg();
        if (!bar.show()) {
            showSetup();
            return;
        }
        if (openRecent) { // mỗi lần launcher khởi động (cả lúc bật TV): mở app gần nhất, chưa có thì mở Cài đặt
            openRecent = false;
            if (!bar.consumeUpdated()) { // vừa cài bản cập nhật thì không tự mở app
                if (!bar.launchRecent()) bar.openSettings();
                else bar.dreamAfterOpen(); // mở app gần nhất xong thì vào Màn hình bảo vệ
            }
        }
    }

    static final String OVERLAY_ACTION = "android.settings.action.MANAGE_OVERLAY_PERMISSION";
    TextView info; // văn bản hướng dẫn ở màn hình cấp quyền

    boolean tryStart(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Mở thẳng Ứng dụng > Quyền truy cập đặc biệt > Hiển thị trên ứng dụng khác.
     * 1) Intent chuẩn. 2) Quét các Activity của app Cài đặt trên chính TV này, tìm màn hình "overlay" (tên khác nhau theo hãng).
     * 3) Dự phòng: trang "Ứng dụng" (chứa mục Quyền truy cập đặc biệt), rồi thông tin ứng dụng (có hướng dẫn đường đi).
     */
    void openOverlaySettings() {
        Uri pkg = Uri.parse("package:" + getPackageName());
        if (tryStart(new Intent(OVERLAY_ACTION)) || tryStart(new Intent(OVERLAY_ACTION, pkg))) return;

        List<String> hints = new ArrayList<String>(); // để hiển thị khi không mở được, giúp xác định đúng tên màn hình
        for (ActivityInfo ai : settingsActivities()) {
            String n = ai.name.toLowerCase();
            boolean overlay = n.contains("overlay") || n.contains("systemalert") || n.contains("drawover");
            if (overlay && ai.exported && tryStart(new Intent().setClassName(ai.packageName, ai.name))) return;
            if (overlay || n.contains("specialaccess") || n.contains("specialapp"))
                hints.add(ai.name.substring(ai.name.lastIndexOf('.') + 1) + (ai.exported ? "" : " (khóa)"));
        }
        if (tryStart(new Intent("android.settings.MANAGE_APPLICATIONS_SETTINGS"))
            || tryStart(new Intent("android.settings.APPLICATION_DETAILS_SETTINGS", pkg))) {
            Toast.makeText(this, "Vào Quyền truy cập đặc biệt > Hiển thị trên ứng dụng khác, bật cho BarTV",
                Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "TV không có màn hình cấp quyền, hãy dùng lệnh adb", Toast.LENGTH_LONG).show();
        }
        if (info != null && !hints.isEmpty()) info.append("\nMục Cài đặt tìm thấy: " + hints);
    }

    /** Mọi Activity của các app Cài đặt (TV, điện thoại/hộp và app đang xử lý "Cài đặt" của máy). */
    List<ActivityInfo> settingsActivities() {
        List<ActivityInfo> out = new ArrayList<ActivityInfo>();
        Set<String> pkgs = new LinkedHashSet<String>();
        pkgs.add("com.android.tv.settings");
        pkgs.add("com.android.settings");
        ResolveInfo ri = getPackageManager().resolveActivity(new Intent("android.settings.SETTINGS"), 0);
        if (ri != null) pkgs.add(ri.activityInfo.packageName);
        for (String p : pkgs) {
            try {
                ActivityInfo[] a = getPackageManager().getPackageInfo(p, PackageManager.GET_ACTIVITIES).activities;
                if (a != null) out.addAll(Arrays.asList(a));
            } catch (Throwable t) {}
        }
        return out;
    }

    void showSetup() {
        int p = (int) (32 * getResources().getDisplayMetrics().density);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(p, p, p, p);

        // Nút đặt trên cùng để luôn thấy và focus được, văn bản hướng dẫn nằm trong ScrollView
        Button grant = new Button(this);
        grant.setText("Cấp quyền hiển thị trên ứng dụng khác");
        grant.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openOverlaySettings(); }
        });
        l.addView(grant);

        Button retry = new Button(this);
        retry.setText("Thử lại");
        retry.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { enter(); }
        });
        l.addView(retry);

        TextView t = info = new TextView(this);
        t.setTextSize(18);
        t.setTextColor(0xFFFFFFFF);
        t.setPadding(0, p / 2, 0, 0);
        t.setText("BarTV cần quyền \"Hiển thị trên các ứng dụng khác\" để hiện thanh đè lên app.\n\n"
            + "Đường đi: Ứng dụng > Quyền truy cập đặc biệt > Hiển thị trên ứng dụng khác > bật BarTV, rồi quay lại và bấm Thử lại.\n\n"
            + "Nếu TV không mở được màn hình cấp quyền, dùng adb:\n"
            + "adb shell appops set thang.math SYSTEM_ALERT_WINDOW allow\n\n"
            + "Nên bật thêm 'Quyền truy cập dữ liệu sử dụng' để quét ứng dụng chính xác hơn\n\n"
            + "Bấm giữ vào các biểu tượng để sắp xếp, mở tùy chọn nâng cao,...\n");
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        l.addView(sv);

        l.setBackgroundColor(0xFF000000);
        setupShown = true;
        setContentView(l);
        grant.requestFocus();
    }
}
