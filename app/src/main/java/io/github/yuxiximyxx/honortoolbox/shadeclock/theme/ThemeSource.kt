package io.github.yuxiximyxx.honortoolbox.shadeclock.theme

import java.io.File
import java.util.zip.ZipFile

/**
 * 主题资产来源（v2.0.18）：渲染器不再直接摸主题目录，统一经此
 * 接口按「相对锁屏目录的路径」读字节。
 *
 * - [ZipThemeSource]：直接读主题包本体（zip）——导入只在主题
 *   目录里存这一份文件，不再解压散图，图库也就索引不到。
 * - [DirThemeSource]：旧版（≤2.0.17）解压目录的兼容读法。设置
 *   页会把存量解压主题打包迁移成 zip；迁移跑完之前 SystemUI
 *   侧靠它照常渲染，迁移后不再走到。
 *
 * 一个实例只归一个 ThemeRenderer 在一条线程链上使用（预热线程
 * 建、attach 后主线程接管），zip 读内部另有同步兜底。
 */
interface ThemeSource {

    /** manifest.xml 的字节；不存在/读不出返回 null。 */
    fun manifestBytes(): ByteArray?

    /** 相对锁屏目录的资产字节（如 "time/time_0.png"）；不存在返回 null。 */
    fun assetBytes(relPath: String): ByteArray?

    /** 主题字体字节（包根 fonts/ 或旧目录 fonts/ 下第一个 ttf/otf）。 */
    fun fontBytes(): ByteArray?

    /** 全部图片的相对路径（位图预解码枚举用，上限由调用方截断）。 */
    fun imagePaths(): List<String>

    /** 释放底层句柄（zip 文件）。 */
    fun close() {}
}

/** 直接读主题包本体（zip 文件）。 */
class ZipThemeSource(packageFile: File) : ThemeSource {

    private val zip = ZipFile(packageFile)
    private val prefix: String? = ThemePackage.lockscreenPrefix(zip)

    /** 包里定位得到锁屏 manifest 才算有效主题包。 */
    val valid: Boolean get() = prefix != null

    override fun manifestBytes(): ByteArray? =
        prefix?.let { entryBytes(it + "manifest.xml") }

    override fun assetBytes(relPath: String): ByteArray? {
        val p = prefix ?: return null
        return entryBytes(p + relPath)
    }

    override fun fontBytes(): ByteArray? {
        val entry = synchronized(zip) {
            zip.entries().asSequence().firstOrNull {
                !it.isDirectory && it.name.startsWith("fonts/") &&
                    (it.name.endsWith(".ttf", true) || it.name.endsWith(".otf", true))
            }
        } ?: return null
        return entryBytes(entry.name)
    }

    override fun imagePaths(): List<String> {
        val p = prefix ?: return emptyList()
        return synchronized(zip) {
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith(p) }
                .map { it.name.removePrefix(p) }
                .filter { isImagePath(it) }
                .toList()
        }
    }

    private fun entryBytes(name: String): ByteArray? {
        return try {
            synchronized(zip) {
                val entry = zip.getEntry(name) ?: return null
                zip.getInputStream(entry).use { it.readBytes() }
            }
        } catch (t: Throwable) {
            null
        }
    }

    override fun close() {
        try {
            zip.close()
        } catch (t: Throwable) {
            // 尽力而为
        }
    }
}

/** 旧版解压目录的兼容读法（迁移前兜底）。 */
class DirThemeSource(private val dir: File) : ThemeSource {

    override fun manifestBytes(): ByteArray? = readFile(File(dir, "manifest.xml"))

    override fun assetBytes(relPath: String): ByteArray? = readFile(File(dir, relPath))

    override fun fontBytes(): ByteArray? {
        val fontFile = File(dir, "fonts").listFiles()
            ?.firstOrNull {
                it.isFile && (it.name.endsWith(".ttf", true) || it.name.endsWith(".otf", true))
            }
            ?: return null
        return readFile(fontFile)
    }

    override fun imagePaths(): List<String> {
        return try {
            dir.walkTopDown().maxDepth(4)
                .filter { it.isFile && isImagePath(it.name) }
                .map { it.relativeTo(dir).path.replace(File.separatorChar, '/') }
                .toList()
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun readFile(file: File): ByteArray? {
        return try {
            if (file.isFile) file.readBytes() else null
        } catch (t: Throwable) {
            null
        }
    }
}

private fun isImagePath(path: String): Boolean =
    path.endsWith(".png", true) || path.endsWith(".jpg", true) ||
        path.endsWith(".jpeg", true) || path.endsWith(".webp", true)
