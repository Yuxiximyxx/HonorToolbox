package io.github.yuxiximyxx.honortoolbox.lockpanel;
/*
 * 移植自 LockPanel（作者 chaosz）v1.20 的 Icons：原厂图标
 * 目录（androidhnext 资源包）与取值格式零改。
 */

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import java.util.ArrayList;
import java.util.List;

/* JADX INFO: loaded from: classes.dex */
final class Icons {
    private static final List<Item> ALL = new ArrayList();
    private static final String PKG_HNEXT = "androidhnext";
    private static Resources sHnext;

    static final class Item {
        final String group;
        final String label;
        final String name;

        Item(String str, String str2, String str3) {
            this.name = str;
            this.label = str2;
            this.group = str3;
        }
    }

    static {
        add("icsvg_public_camera_filled", "相机", "媒体与拍摄");
        add("icsvg_public_switch_camera", "切换相机", "媒体与拍摄");
        add("icsvg_public_video", "录像", "媒体与拍摄");
        add("icsvg_public_photos", "相册", "媒体与拍摄");
        add("icsvg_public_music_filled", "音乐", "媒体与拍摄");
        add("icsvg_public_music_off_filled", "暂停音乐", "媒体与拍摄");
        add("icsvg_public_earphone_filled", "耳机", "媒体与拍摄");
        add("icsvg_public_recorder", "录音机", "工具");
        add("icsvg_public_recorder_filled", "录音", "工具");
        add("icsvg_public_calculator_filled", "计算器", "工具");
        add("icsvg_hnunlock_calculator", "计算器（锁屏）", "工具");
        add("icsvg_public_timercircle_filled", "时钟", "工具");
        add("icsvg_public_clock_filled", "时钟（表盘）", "工具");
        add("icsvg_public_timer_filled", "计时器", "工具");
        add("icsvg_public_alarm_filled", "闹钟", "工具");
        add("icsvg_public_worldclock_filled", "世界时钟", "工具");
        add("icsvg_public_calendar_filled", "日历", "工具");
        add("icsvg_public_note_filled", "笔记", "工具");
        add("icsvg_public_notepad_filled", "备忘录", "工具");
        add("icsvg_public_hivision", "扫描", "扫描与识别");
        add("icsvg_public_hivision_filled", "扫描（实心）", "扫描与识别");
        add("icsvg_public_qrcode", "二维码", "扫描与识别");
        add("icsvg_public_scan_filled", "扫码", "扫描与识别");
        add("icsvg_public_yoyoscan_filled", "智能识物", "扫描与识别");
        add("icsvg_public_search_filled", "搜索", "扫描与识别");
        add("icsvg_public_translate", "翻译", "扫描与识别");
        add("icsvg_public_aitranslate_filled", "AI 翻译", "扫描与识别");
        add("icsvg_public_voice_filled", "语音", "扫描与识别");
        add("icsvg_public_phonecall_filled", "电话", "通讯");
        add("icsvg_public_message_filled", "信息", "通讯");
        add("icsvg_public_contacts_filled", "联系人", "通讯");
        add("icsvg_public_email_filled", "邮件", "通讯");
        add("icsvg_public_flashlight_filled", "手电筒", "系统");
        add("icsvg_public_flashlight_off_filled", "手电筒（关）", "系统");
        add("icsvg_public_flashlight_on_filled", "手电筒（开）", "系统");
        add("icsvg_public_settings_filled", "设置", "系统");
        add("icsvg_public_screenshot_filled", "截图", "系统");
        add("icsvg_public_brightness", "亮度", "系统");
        add("icsvg_public_volume", "音量", "系统");
        add("icsvg_public_wifi6_filled", "WLAN", "系统");
        add("icsvg_public_bluetooth", "蓝牙", "系统");
        add("icsvg_public_airplane_filled", "飞行模式", "系统");
        add("icsvg_public_nfc_filled", "NFC", "系统");
        add("icsvg_public_battery_filled", "电池", "系统");
        add("icsvg_public_lock_filled", "锁屏", "系统");
        add("icsvg_public_fingerprint", "指纹", "系统");
        add("icsvg_public_wallet_filled", "钱包", "其它");
        add("icsvg_public_app_filled", "应用", "其它");
        add("icsvg_public_home_filled", "桌面", "其它");
        add("icsvg_public_add_norm_filled", "添加", "其它");
        add("icsvg_public_more12_filled", "更多", "其它");
    }

    private static void add(String str, String str2, String str3) {
        ALL.add(new Item(str, str2, str3));
    }

    private Icons() {
    }

    static List<Item> all() {
        return ALL;
    }

    static Item byName(String str) {
        if (str == null || str.isEmpty()) {
            return null;
        }
        for (Item item : ALL) {
            if (item.name.equals(str)) {
                return item;
            }
        }
        return null;
    }

    static synchronized Resources hnext(Context context) {
        if (sHnext != null) {
            return sHnext;
        }
        if (context == null) {
            return null;
        }
        try {
            sHnext = context.getPackageManager().getResourcesForApplication(PKG_HNEXT);
        } catch (Throwable th) {
        }
        return sHnext;
    }

    static int resId(Context context, String str) {
        Resources resourcesHnext;
        if (str == null || str.isEmpty() || (resourcesHnext = hnext(context)) == null) {
            return 0;
        }
        try {
            return resourcesHnext.getIdentifier(str, "drawable", PKG_HNEXT);
        } catch (Throwable th) {
            return 0;
        }
    }

    static Drawable drawable(Context context, String str) {
        int iResId;
        Resources resourcesHnext = hnext(context);
        if (resourcesHnext == null || (iResId = resId(context, str)) == 0) {
            return null;
        }
        try {
            Drawable drawable = resourcesHnext.getDrawable(iResId, null);
            if (drawable != null) {
                return drawable.mutate();
            }
            return null;
        } catch (Throwable th) {
            return null;
        }
    }
}
