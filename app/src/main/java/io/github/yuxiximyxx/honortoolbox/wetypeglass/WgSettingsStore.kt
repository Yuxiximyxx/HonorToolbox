package io.github.yuxiximyxx.honortoolbox.wetypeglass

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import com.superboyyy.mosinputglass.Config
import io.github.yuxiximyxx.honortoolbox.R
import java.io.ByteArrayOutputStream

/**
 * 工具箱进程侧的输入法玻璃配置存储：设置页读写都落在这份
 * 镜像偏好上，[WetypeGlassConfigProvider] 把它供给两个输入
 * 法进程现读。值全按字符串存（与原模块 Config 的存储口径
 * 一致），键名/默认值/预设逐字沿用原模块。
 *
 * 原独立版经 LSPosed 远程配置存下的设置不迁移：镜像从空
 * 开始，首次打开子页时按 Config.DEFAULTS 整份播种，新装即
 * 默认（合入差异，见 v1.8.0 发布说明）。
 */
object WgSettingsStore {

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(WgConfigContract.MIRROR_PREFS, Context.MODE_PRIVATE)

    fun get(context: Context, key: String): String {
        val v = prefs(context).getString(key, null)
        return if (v.isNullOrEmpty()) Config.def(key) else v
    }

    fun put(context: Context, key: String, value: String) {
        prefs(context).edit().putString(key, value).apply()
    }

    fun bool(context: Context, key: String): Boolean = get(context, key) == "1"

    fun putBool(context: Context, key: String, value: Boolean) =
        put(context, key, if (value) "1" else "0")

    /** 首次播种：cfgver 对不上时把默认值整份写进镜像（保留已有的总开关）。 */
    fun seedIfNeeded(context: Context) {
        val p = prefs(context)
        if (p.getString(Config.CFG_VER_KEY, "") == Config.CFG_VERSION) return
        val ed = p.edit()
        for (key in Config.DEFAULTS.keys) {
            if (key == Config.ENABLE && p.contains(key)) continue
            ed.putString(key, Config.def(key))
        }
        ed.putString(Config.CFG_VER_KEY, Config.CFG_VERSION)
        ed.apply()
    }

    /**
     * 套用预设（preset == null 为恢复默认）：与原 SettingsActivity
     * 的 applyPreset 同语义——预设只覆盖 DEFAULTS 里的参数键，
     * 不动总开关与两个输入法开关；恢复默认连那三个也回默认。
     */
    fun applyPreset(context: Context, preset: Map<String, String>?) {
        val ed = prefs(context).edit()
        for (key in Config.DEFAULTS.keys) {
            if (preset != null &&
                (key == Config.ENABLE || key == Config.IME_WECHAT || key == Config.IME_DOUBAO)
            ) {
                continue
            }
            ed.putString(key, preset?.get(key) ?: Config.def(key))
        }
        ed.apply()
    }

    fun installed(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (t: Throwable) {
        false
    }

    /**
     * 荣耀圆环标识：原 SettingsActivity 把模块内置矢量画成
     * 192px 单色 PNG 以 Base64 存进配置给输入法进程用（输入
     * 法进程读不到设置 App 的资源）。合入后矢量随仓搬为
     * R.drawable.wg_honor_ring，生成口径与原版一致。
     */
    fun cacheHonorLogo(context: Context) {
        val p = prefs(context)
        if (Config.LOGO_SRC_VER == p.getString(Config.LOGO_SRC_KEY, "") &&
            p.contains(Config.LOGO_PNG)
        ) {
            return
        }
        try {
            val d = context.resources.getDrawable(R.drawable.wg_honor_ring, null)
            val size = 192
            val bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, size, size)
            d.draw(Canvas(bm))
            val out = ByteArrayOutputStream()
            bm.compress(Bitmap.CompressFormat.PNG, 100, out)
            p.edit()
                .putString(Config.LOGO_PNG, Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
                .putString(Config.LOGO_SRC_KEY, Config.LOGO_SRC_VER)
                .apply()
        } catch (t: Throwable) {
            // 生成失败不阻塞设置页；hook 端没图时回退原 logo
        }
    }

    /**
     * WE 字体：原 SettingsActivity 从已安装的微信输入法安装包
     * 里读其自带字体（assets/fonts/WE-Regular.ttf）以 Base64
     * 存进配置，豆包开启「用微信英文字体」时用它画字母数字。
     * 口径与原版一致（版本戳 = 版本号:更新时间，变了才重读；
     * 微信输入法没装时清掉旧缓存）。
     */
    fun cacheWeFont(context: Context) {
        val p = prefs(context)
        try {
            val pi = context.packageManager.getPackageInfo(Config.PKG_WECHAT, 0)
            val src = "${pi.longVersionCode}:${pi.lastUpdateTime}"
            if (src == p.getString(Config.WE_FONT_SRC, "") && p.contains(Config.WE_FONT)) return
            val data = context.createPackageContext(Config.PKG_WECHAT, 0).assets
                .open(Config.WE_FONT_ASSET).use { it.readBytes() }
            p.edit()
                .putString(Config.WE_FONT, Base64.encodeToString(data, Base64.NO_WRAP))
                .putString(Config.WE_FONT_SRC, src)
                .apply()
        } catch (e: PackageManager.NameNotFoundException) {
            if (p.contains(Config.WE_FONT)) {
                p.edit().remove(Config.WE_FONT).remove(Config.WE_FONT_SRC).apply()
            }
        } catch (t: Throwable) {
            // 读不到字体不阻塞设置页；豆包继续用自己的字体
        }
    }
}
