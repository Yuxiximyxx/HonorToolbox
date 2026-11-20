package io.github.yuxiximyxx.honortoolbox.shadeclock.theme

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream

/** 通用 XML 节点树（manifest.xml 先整体解析成树，再按语义解释）。 */
class XNode(
    val tag: String,
    val attrs: Map<String, String>,
    val children: List<XNode>,
) {
    fun attr(name: String): String? = attrs[name]
    fun child(tag: String): XNode? = children.firstOrNull { it.tag == tag }
    fun descendants(out: MutableList<XNode>) {
        for (c in children) {
            out.add(c)
            c.descendants(out)
        }
    }
}

fun parseXmlTree(input: InputStream): XNode? {
    val factory = XmlPullParserFactory.newInstance()
    factory.isNamespaceAware = false
    val parser = factory.newPullParser()
    parser.setInput(input, "UTF-8")
    var root: XNode? = null
    val stack = ArrayDeque<MutableNode>()
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> {
                val attrs = HashMap<String, String>()
                for (i in 0 until parser.attributeCount) {
                    val name = parser.getAttributeName(i)
                    if (name != null) attrs[name] = parser.getAttributeValue(i) ?: ""
                }
                stack.addLast(MutableNode(parser.name ?: "", attrs))
            }
            XmlPullParser.END_TAG -> {
                val done = stack.removeLastOrNull() ?: break
                val node = XNode(done.tag, done.attrs, done.children)
                val parent = stack.lastOrNull()
                if (parent == null) {
                    root = node
                } else {
                    parent.children.add(node)
                }
            }
        }
        event = parser.next()
    }
    return root
}

private class MutableNode(
    val tag: String,
    val attrs: Map<String, String>,
    val children: MutableList<XNode> = ArrayList(),
)

/** 主题 Var 定义：表达式型，或 VariableAnimation 型（只取终态值）。 */
sealed interface VarDef {
    class ExprDef(val expression: String) : VarDef
    class AnimDef(val finalValue: Double) : VarDef
}

/** VarArray 定义：Vars 里第一个 Var 的 name/index + Items 的值列表。 */
class VarArrayDef(
    val varName: String,
    val indexExpr: String,
    val items: List<String>,
    val numeric: Boolean,
)

/**
 * 一份锁屏主题的解析结果。
 * @param screenWidth manifest 根节点的 screenWidth（设计宽，缺省 1080）
 * @param roots 可视元素根列表（Group/Image/Text/DateTime/Time，文档顺序）
 * @param vars 全局 Var（不含 Weather 块内的占位默认值，见 ThemeParser 说明）
 * @param varArrays VarArray 列表
 */
class ThemeModel(
    val screenWidth: Int,
    val roots: List<XNode>,
    val vars: Map<String, VarDef>,
    val varArrays: List<VarArrayDef>,
)

object ThemeParser {

    private val VISUAL_TAGS = setOf("Group", "Image", "Text", "DateTime", "Time")

    /** 不进入变量收集与渲染的子树（解锁/按钮/命令/外部命令）。 */
    private val SKIP_SUBTREES = setOf(
        "Unlocker", "Button", "Triggers", "Trigger", "ExternalCommands",
        "AnimationCommand", "StartPoint", "EndPoint",
    )

    /**
     * 解析 manifest.xml 的字节（v2.0.18 起资产经 [ThemeSource] 读，
     * 主题包本体与旧解压目录共用此入口）。解析失败返回 null
     * （调用方回退原头部）。
     */
    fun parse(manifestBytes: ByteArray): ThemeModel? {
        val root = manifestBytes.inputStream().buffered().use { parseXmlTree(it) } ?: return null
        if (root.tag != "Lockscreen") return null
        val screenWidth = root.attr("screenWidth")?.toIntOrNull() ?: 1080

        val vars = LinkedHashMap<String, VarDef>()
        val varArrays = ArrayList<VarArrayDef>()
        collectDefs(root, vars, varArrays, insideWeather = false)

        val roots = root.children.filter { it.tag in VISUAL_TAGS }
        return ThemeModel(screenWidth, roots, vars, varArrays)
    }

    /**
     * 收集 Var / VarArray 定义。Weather 块里的同名 Var 是主题作者写的
     * 占位默认值（如 mintemp=20），真实值只能来自天气查询：整块跳过，
     * Weather.* 命名空间由渲染时的真实数据独占，查不到即 Missing。
     */
    private fun collectDefs(
        node: XNode,
        vars: MutableMap<String, VarDef>,
        varArrays: MutableList<VarArrayDef>,
        insideWeather: Boolean,
    ) {
        for (child in node.children) {
            when {
                child.tag in SKIP_SUBTREES -> Unit
                child.tag == "Weather" -> collectDefs(child, vars, varArrays, insideWeather = true)
                child.tag == "VarArray" -> {
                    if (!insideWeather) parseVarArray(child)?.let { varArrays.add(it) }
                }
                child.tag == "Var" -> {
                    if (!insideWeather) {
                        val name = child.attr("name")
                        if (!name.isNullOrEmpty()) {
                            vars[name] = parseVarDef(child)
                        }
                    }
                }
                else -> collectDefs(child, vars, varArrays, insideWeather)
            }
        }
    }

    private fun parseVarDef(node: XNode): VarDef {
        val anim = node.child("VariableAnimation")
        if (anim != null) {
            // 只取终态：time 最大的 AniFrame 的 value
            var bestTime = Long.MIN_VALUE
            var bestValue = 0.0
            for (frame in anim.children) {
                if (frame.tag != "AniFrame") continue
                val t = frame.attr("time")?.toLongOrNull() ?: 0L
                val v = frame.attr("value")?.toDoubleOrNull() ?: 0.0
                if (t >= bestTime) {
                    bestTime = t
                    bestValue = v
                }
            }
            return VarDef.AnimDef(bestValue)
        }
        return VarDef.ExprDef(node.attr("expression") ?: "0")
    }

    private fun parseVarArray(node: XNode): VarArrayDef? {
        val varsNode = node.child("Vars") ?: return null
        val varNode = varsNode.children.firstOrNull { it.tag == "Var" } ?: return null
        val name = varNode.attr("name") ?: return null
        val indexExpr = varNode.attr("index") ?: "0"
        val itemsNode = node.child("Items")
        val items = itemsNode?.children
            ?.filter { it.tag == "Item" }
            ?.map { it.attr("value") ?: "" }
            ?: emptyList()
        val numeric = node.attr("type")?.equals("string", ignoreCase = true) != true
        return VarArrayDef(name, indexExpr, items, numeric)
    }
}
