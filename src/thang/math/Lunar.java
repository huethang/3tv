package thang.math;

import java.util.Calendar;

/**
 * Âm lịch Việt Nam (múi giờ UTC+7) theo thuật toán của Hồ Ngọc Đức: ngày/tháng/năm âm, tháng nhuận, can chi của
 * năm/tháng/ngày và tiết khí. Chỉ tính toán, không dùng bộ nhớ hay tài nguyên nào khác.
 */
public class Lunar {
    static final double TZ = 7.0;
    static final String[] CAN = {"Giáp", "Ất", "Bính", "Đinh", "Mậu", "Kỷ", "Canh", "Tân", "Nhâm", "Quý"};
    static final String[] CHI = {"Tý", "Sửu", "Dần", "Mão", "Thìn", "Tỵ", "Ngọ", "Mùi", "Thân", "Dậu", "Tuất", "Hợi"};
    // 24 tiết khí, bắt đầu từ Xuân phân (kinh độ Mặt Trời 0°), mỗi tiết cách nhau 15°
    static final String[] TERMS = {"Xuân phân", "Thanh minh", "Cốc vũ", "Lập hạ", "Tiểu mãn", "Mang chủng", "Hạ chí", "Tiểu thử",
        "Đại thử", "Lập thu", "Xử thử", "Bạch lộ", "Thu phân", "Hàn lộ", "Sương giáng", "Lập đông", "Tiểu tuyết", "Đại tuyết",
        "Đông chí", "Tiểu hàn", "Đại hàn", "Lập xuân", "Vũ thủy", "Kinh trập"};

    static int floor(double d) { return (int) Math.floor(d); }

    static int jdFromDate(int dd, int mm, int yy) {
        int a = (14 - mm) / 12;
        int y = yy + 4800 - a;
        int m = mm + 12 * a - 3;
        int jd = dd + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045;
        if (jd < 2299161) jd = dd + (153 * m + 2) / 5 + 365 * y + y / 4 - 32083;
        return jd;
    }

    /** Thời điểm trăng mới thứ k (ngày Julian). */
    static double newMoon(int k) {
        double t = k / 1236.85, t2 = t * t, t3 = t2 * t, dr = Math.PI / 180;
        double jd1 = 2415020.75933 + 29.53058868 * k + 0.0001178 * t2 - 0.000000155 * t3;
        jd1 += 0.00033 * Math.sin((166.56 + 132.87 * t - 0.009173 * t2) * dr);
        double m = 359.2242 + 29.10535608 * k - 0.0000333 * t2 - 0.00000347 * t3;
        double mpr = 306.0253 + 385.81691806 * k + 0.0107306 * t2 + 0.00001236 * t3;
        double f = 21.2964 + 390.67050646 * k - 0.0016528 * t2 - 0.00000239 * t3;
        double c1 = (0.1734 - 0.000393 * t) * Math.sin(m * dr) + 0.0021 * Math.sin(2 * dr * m);
        c1 -= 0.4068 * Math.sin(mpr * dr) - 0.0161 * Math.sin(dr * 2 * mpr);
        c1 -= 0.0004 * Math.sin(dr * 3 * mpr);
        c1 += 0.0104 * Math.sin(dr * 2 * f) - 0.0051 * Math.sin(dr * (m + mpr));
        c1 -= 0.0074 * Math.sin(dr * (m - mpr)) - 0.0004 * Math.sin(dr * (2 * f + m));
        c1 -= 0.0004 * Math.sin(dr * (2 * f - m)) + 0.0006 * Math.sin(dr * (2 * f + mpr));
        c1 += 0.0010 * Math.sin(dr * (2 * f - mpr)) + 0.0005 * Math.sin(dr * (2 * mpr + m));
        double dt = t < -11 ? 0.001 + 0.000839 * t + 0.0002261 * t2 - 0.00000845 * t3 - 0.000000081 * t * t3
            : -0.000278 + 0.000265 * t + 0.000262 * t2;
        return jd1 + c1 - dt;
    }

    /** Kinh độ Mặt Trời (radian, 0..2π) tại ngày Julian jdn. */
    static double sunLongitude(double jdn) {
        double t = (jdn - 2451545.0) / 36525, t2 = t * t, dr = Math.PI / 180;
        double m = 357.52910 + 35999.05030 * t - 0.0001559 * t2 - 0.00000048 * t * t2;
        double l0 = 280.46645 + 36000.76983 * t + 0.0003032 * t2;
        double dl = (1.914600 - 0.004817 * t - 0.000014 * t2) * Math.sin(dr * m);
        dl += (0.019993 - 0.000101 * t) * Math.sin(dr * 2 * m) + 0.000290 * Math.sin(dr * 3 * m);
        double l = (l0 + dl) * dr;
        return l - Math.PI * 2 * floor(l / (Math.PI * 2));
    }

    /** Cung Mặt Trời (0..11, mỗi cung 30°) của ngày jd; dùng để tìm tháng nhuận. */
    static int sunSign(int jd) { return floor(sunLongitude(jd - 0.5 - TZ / 24) / Math.PI * 6); }

    static int newMoonDay(int k) { return floor(newMoon(k) + 0.5 + TZ / 24); }

    static int lunarMonth11(int yy) {
        double off = jdFromDate(31, 12, yy) - 2415021.076998695;
        int k = floor(off / 29.530588853);
        int nm = newMoonDay(k);
        if (sunSign(nm) >= 9) nm = newMoonDay(k - 1);
        return nm;
    }

    static int leapMonthOffset(int a11) {
        int k = floor((a11 - 2415021.076998695) / 29.530588853 + 0.5);
        int last, i = 1, arc = sunSign(newMoonDay(k + i));
        do {
            last = arc;
            i++;
            arc = sunSign(newMoonDay(k + i));
        } while (arc != last && i < 14);
        return i - 1;
    }

    /** Đổi ngày dương sang âm: {ngày, tháng, năm, nhuận (1/0)}. */
    static int[] solar2lunar(int dd, int mm, int yy) {
        int dayNumber = jdFromDate(dd, mm, yy);
        int k = floor((dayNumber - 2415021.076998695) / 29.530588853);
        int monthStart = newMoonDay(k + 1);
        if (monthStart > dayNumber) monthStart = newMoonDay(k);
        int a11 = lunarMonth11(yy), b11 = a11, lunarYear;
        if (a11 >= monthStart) {
            lunarYear = yy;
            a11 = lunarMonth11(yy - 1);
        } else {
            lunarYear = yy + 1;
            b11 = lunarMonth11(yy + 1);
        }
        int lunarDay = dayNumber - monthStart + 1;
        int diff = (monthStart - a11) / 29, lunarLeap = 0, lunarMonth = diff + 11;
        if (b11 - a11 > 365) {
            int leapDiff = leapMonthOffset(a11);
            if (diff >= leapDiff) {
                lunarMonth = diff + 10;
                if (diff == leapDiff) lunarLeap = 1;
            }
        }
        if (lunarMonth > 12) lunarMonth -= 12;
        if (lunarMonth >= 11 && diff < 4) lunarYear -= 1;
        return new int[] {lunarDay, lunarMonth, lunarYear, lunarLeap};
    }

    /** Tiết khí hiện tại của ngày jd (0..23 theo TERMS). */
    static int term(int jd) { return floor(sunLongitude(jd - 0.5 - TZ / 24) / Math.PI * 12) % 24; }

    /** Hai dòng thông tin âm lịch của ngày c: ngày tháng năm âm, rồi can chi ngày/tháng và tiết khí. */
    static String[] info(Calendar c) {
        int dd = c.get(Calendar.DAY_OF_MONTH), mm = c.get(Calendar.MONTH) + 1, yy = c.get(Calendar.YEAR);
        int[] l = solar2lunar(dd, mm, yy);
        int jd = jdFromDate(dd, mm, yy), t = term(jd);
        String leap = l[3] == 1 ? " nhuận" : "";
        String special = l[0] == 1 ? " (Mùng 1)" : l[0] == 15 ? " (Rằm)" : "";
        String line1 = "Âm lịch: " + l[0] + "/" + l[1] + leap + " năm " + CAN[(l[2] + 6) % 10] + " " + CHI[(l[2] + 8) % 12] + special;
        String line2 = "Ngày " + CAN[(jd + 9) % 10] + " " + CHI[(jd + 1) % 12]
            + " · Tháng " + CAN[(l[2] * 12 + l[1] + 3) % 10] + " " + CHI[(l[1] + 1) % 12] + leap
            + " · Tiết " + TERMS[t] + (t != term(jd - 1) ? " (bắt đầu hôm nay)" : "");
        return new String[] {line1, line2};
    }
}
