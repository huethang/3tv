package thang.math;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * Điểm vào của nút Home (và biểu tượng app). Activity TRONG SUỐT, không hiện gì của riêng nó nên không bao giờ chớp đen:
 * - Đang ở trang chủ toàn màn hình (HomeActivity): tự đóng, trang chủ giữ nguyên.
 * - Đang dùng app khác: hiện/ẩn dock dạng overlay rồi lùi ra sau để app hiện lại.
 * - Không có app nào bên dưới (mới khởi động, hoặc app vừa thoát): mở trang chủ toàn màn hình.
 */
public class MainActivity extends Activity {
    static final long ENTER_DELAY_MS = 120; // chờ xem onNewIntent (bấm Home) có tới ngay sau onResume không
    final Handler handler = new Handler();
    boolean pendingHome;  // vừa được tạo mới (do bấm Home): xử lý ở onResume
    boolean gotIntent;    // vừa xử lý một Intent và đang chờ onResume đi kèm
    long intentAt;        // lúc xử lý Intent gần nhất; cờ quá cũ (onResume không tới) thì bỏ
    static final long DOUBLE_HOME_MS = 400; // bấm Home lần 2 trong khoảng này (0,4s) khi dock overlay đang hiện: về trang chủ
    static long lastHomeAt;
    final Runnable enterTask = new Runnable() {
        public void run() {
            // Được đưa ra trước mà không có Intent Home: vừa thoát một màn hình/app mở từ dock overlay. Lùi ra sau để hiện app
            // bên dưới (còn sống thì là app đang dùng trước đó, hết app thì rơi về trang chủ)
            Bar bar = Bar.get(MainActivity.this);
            if (bar.openedApp) { // app vừa thoát: app bên dưới giờ là app trước nó
                bar.lastApp = bar.prevApp;
                bar.prevApp = null;
            }
            if (Bar.homeExists) moveTaskToBack(true);
            else goHome();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        pendingHome = true; // tạo mới vì bấm Home (hoặc mở từ danh sách app): xử lý ở onResume
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handler.removeCallbacks(enterTask);
        Intent q = i.getParcelableExtra(Bar.EXTRA_LAUNCH);
        if (q != null) { // dock overlay nhờ mở app: launcher lên trước, giờ mới mở để Back từ app luôn về trang chủ
            gotIntent = true;
            intentAt = SystemClock.uptimeMillis();
            Bar bar = Bar.get(this);
            bar.hideNow();
            if (!bar.openNow(q, i.getIntExtra(Bar.EXTRA_HOW, Bar.OPEN_SCREEN), i.getStringArrayExtra(Bar.EXTRA_KEEP)))
                bar.toast("Không mở được mục này trên TV");
            return;
        }
        handleHome();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingHome) {
            pendingHome = false;
            handleHome();
            return;
        }
        boolean fresh = gotIntent && SystemClock.uptimeMillis() - intentAt < 1500;
        gotIntent = false;
        if (fresh) return; // đã xử lý ở onCreate/onNewIntent
        // Hệ thống đưa Activity này ra trước mà chưa có Intent Home: hoãn một chút (bấm Home lúc có app chạy sẽ chạy
        // onResume -> onPause -> onNewIntent -> onResume, Intent tới ngay sau đó và huỷ việc này)
        handler.removeCallbacks(enterTask);
        handler.postDelayed(enterTask, ENTER_DELAY_MS);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(enterTask);
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        // Launcher không tự đóng
    }

    void handleHome() {
        gotIntent = true;
        long now = SystemClock.uptimeMillis();
        intentAt = now;
        boolean twice = now - lastHomeAt < DOUBLE_HOME_MS; // Home lần 2 liền sau: về trang chủ toàn màn hình
        lastHomeAt = now;
        // Đang dùng app khác: dock overlay đè lên app (không làm gì khi đang ở trang chủ toàn màn hình)
        boolean overlayOk = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
        if (!twice && !Bar.homeStarted && Bar.appBeneath && overlayOk && Bar.get(this).homeToggle()) {
            moveTaskToBack(true); // app bên dưới hiện lại, dock overlay nằm đè lên
            return;
        }
        goHome(); // trang chủ toàn màn hình (đang hiện sẵn thì chỉ giữ nguyên), hoặc hiện màn hình cấp quyền nếu thiếu
    }

    /**
     * Đưa HomeActivity ra trước rồi đóng Activity này. Không được chỉ finish(): Activity HOME cuối cùng tự đóng thì hệ thống
     * lại mở HOME ngay, tạo vòng lặp mở/đóng làm dock nháy liên tục.
     */
    void goHome() {
        Bar.appBeneath = false;
        try {
            startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) {}
        finish();
    }
}
