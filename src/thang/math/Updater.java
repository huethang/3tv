package thang.math;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Cập nhật: tải APK mới rồi cài đặt (ưu tiên cài im lặng qua ADB, không được thì mở trình cài đặt của hệ thống). */
public class Updater {
    static volatile boolean busy;

    /** Tải APK từ url rồi cài. self=true: đây là bản cập nhật của chính launcher (cài xong launcher khởi động lại). */
    static void start(final Bar bar, final String url, final String name, final boolean self) {
        if (busy) return;
        busy = true;
        bar.toast(self ? "Đang tải bản cập nhật..." : "Đang tải " + name + "...");
        new Thread(new Runnable() {
            public void run() {
                try {
                    File f = download(bar, url, name);
                    bar.toast("Đang cài đặt...");
                    install(bar, f, self);
                } catch (Throwable t) {
                    bar.toast("Cập nhật thất bại: " + (t.getMessage() != null ? t.getMessage() : t.toString()));
                } finally {
                    busy = false;
                }
            }
        }).start();
    }

    /** Đọc một file văn bản nhỏ (tối đa 256 KB) từ url, dùng cho danh sách ứng dụng 3tv.js. */
    static String fetchText(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setUseCaches(false);
        try {
            if (c.getResponseCode() != 200) throw new IOException("máy chủ trả về mã " + c.getResponseCode());
            java.io.ByteArrayOutputStream os = new java.io.ByteArrayOutputStream();
            InputStream in = c.getInputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0 && os.size() < 256 * 1024) os.write(buf, 0, n);
            in.close();
            return os.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** Tải url về file dest (ghi thẳng ra đĩa, không giữ cả file trong RAM). Dùng chung cho APK và ảnh icon. */
    static void fetch(String url, File dest) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setUseCaches(false);
        try {
            if (c.getResponseCode() != 200) throw new IOException("máy chủ trả về mã " + c.getResponseCode());
            InputStream in = c.getInputStream();
            FileOutputStream os = new FileOutputStream(dest);
            try {
                copy(in, os);
            } finally {
                os.close();
                in.close();
            }
        } finally {
            c.disconnect();
        }
    }

    static void copy(InputStream in, OutputStream os) throws IOException {
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
    }

    static File dir(Context c) {
        return c.getExternalFilesDir(null);
    }

    /** Xoá ảnh icon tuỳ chỉnh đã tải của bản cũ (tính năng đã bỏ). */
    static void cleanup(Context c) {
        File d = dir(c);
        File[] l = d != null ? d.listFiles() : null;
        if (l != null) {
            for (File f : l) {
                String n = f.getName();
                if (n.endsWith(".apk") || n.endsWith(".part")) f.delete();
            }
        }
        // Ảnh icon cũ (chưa cắt, tên "icon_*") đã bỏ; ảnh mới "logo_*" là dữ liệu cần giữ nên không bao giờ bị xoá ở đây
        l = c.getFilesDir().listFiles();
        if (l != null) for (File f : l) if (f.getName().startsWith("icon_")) f.delete();
    }

    /** Tải APK về thư mục riêng của app trên bộ nhớ ngoài (trình cài đặt và ADB đọc được, không cần xin quyền). */
    static File download(Bar bar, String url, String name) throws IOException {
        File dir = dir(bar.ctx);
        if (dir == null) throw new IOException("không có bộ nhớ để lưu file");
        File out = new File(dir, name);
        File part = new File(dir, name + ".part");
        try {
            fetch(url, part);
            if (bar.pm.getPackageArchiveInfo(part.getPath(), 0) == null) throw new IOException("file tải về không phải APK hợp lệ");
            out.delete();
            if (!part.renameTo(out)) throw new IOException("không lưu được file");
        } finally {
            part.delete(); // không còn gì sau khi đổi tên; còn thì là file lỗi/dở dang
        }
        return out;
    }

    static void install(final Bar bar, final File f, final boolean self) {
        if (self) bar.markUpdating(); // cài xong launcher khởi động lại: đừng tự mở app/screensaver
        if (Adb.ready(bar.ctx) && installViaAdb(bar, f)) return;
        // Dự phòng: PackageInstaller của hệ thống (hỏi quyền "cài từ nguồn không xác định" nếu chưa cấp)
        bar.handler.post(new Runnable() {
            public void run() {
                if (Build.VERSION.SDK_INT >= 26 && !bar.pm.canRequestPackageInstalls()) {
                    f.delete(); // bấm Cập nhật lại sẽ tải lại
                    bar.toast("Hãy bật \"Cho phép từ nguồn này\" cho BarTV rồi bấm Cập nhật lại");
                    try {
                        bar.ctx.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + bar.ctx.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Throwable t) {
                        bar.toast("Không mở được cài đặt nguồn không xác định");
                    }
                    if (self) bar.clearUpdating();
                    return;
                }
                if (!installViaSession(bar, f, self)) { if (self) bar.clearUpdating(); }
            }
        });
    }

    static final String ACTION_DONE = "thang.math.INSTALL_DONE";

    /** Cài qua PackageInstaller: hệ thống tự hiện hộp thoại xác nhận cài đặt. */
    static boolean installViaSession(final Bar bar, final File f, final boolean self) {
        final Context c = bar.ctx;
        try {
            final PackageInstaller pi = c.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            final int id = pi.createSession(sp);
            PackageInstaller.Session s = pi.openSession(id);
            try {
                InputStream in = new FileInputStream(f);
                OutputStream os = s.openWrite(f.getName(), 0, f.length());
                try {
                    copy(in, os);
                    s.fsync(os);
                } finally {
                    os.close();
                    in.close();
                }
                c.registerReceiver(new BroadcastReceiver() {
                    public void onReceive(Context ctx, Intent i) {
                        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
                        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                            Intent confirm = (Intent) i.getParcelableExtra(Intent.EXTRA_INTENT);
                            try {
                                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                ctx.startActivity(confirm);
                            } catch (Throwable t) {
                                bar.toast("Không mở được hộp thoại cài đặt");
                                if (self) bar.clearUpdating();
                            }
                            return;
                        }
                        try { ctx.unregisterReceiver(this); } catch (Throwable ignored) {}
                        if (st != PackageInstaller.STATUS_SUCCESS) {
                            if (self) bar.clearUpdating();
                            String m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                            bar.toast("Cài đặt thất bại" + (m != null ? ": " + m : ""));
                        }
                    }
                }, new IntentFilter(ACTION_DONE));
                Intent it = new Intent(ACTION_DONE).setPackage(c.getPackageName());
                int fl = Build.VERSION.SDK_INT >= 31 ? 0x02000000 /* FLAG_MUTABLE */ : 0;
                s.commit(android.app.PendingIntent.getBroadcast(c, id, it, android.app.PendingIntent.FLAG_UPDATE_CURRENT | fl).getIntentSender());
            } finally {
                s.close();
                f.delete(); // dữ liệu đã nằm trong phiên cài đặt, không cần giữ file nữa
            }
            return true;
        } catch (Throwable t) {
            bar.toast("Cài đặt thất bại: " + (t.getMessage() != null ? t.getMessage() : t.toString()));
            return false;
        }
    }

    /** Cài bằng "pm install -r" qua ADB. Cài xong app bị hệ thống tắt và tự khởi động lại nên có thể không bao giờ trả về. */
    static boolean installViaAdb(Bar bar, File f) {
        try {
            AdbClient a = Adb.open(bar.ctx);
            a.setTimeout(120000);
            String out = a.shell("pm install -r \"" + f.getPath() + "\"");
            a.close();
            if (out.contains("Success")) {
                f.delete();
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
