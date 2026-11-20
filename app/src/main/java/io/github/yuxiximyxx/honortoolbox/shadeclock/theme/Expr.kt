package io.github.yuxiximyxx.honortoolbox.shadeclock.theme

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 荣耀锁屏（MAML 系）表达式的求值结果。
 * Missing 专用于「天气真实值还没拿到」：任何运算碰到它都传播 Missing，
 * Text 渲染时据此把整段文字画成空（不用主题 manifest 里的占位假值）。
 */
sealed interface EVal {
    data class Num(val v: Double) : EVal
    data class Str(val s: String) : EVal
    object Missing : EVal
}

fun formatNum(v: Double): String =
    if (v.isFinite() && v == Math.floor(v) && abs(v) < 1e15) v.toLong().toString() else v.toString()

fun EVal.asNum(): Double = when (this) {
    is EVal.Num -> v
    is EVal.Str -> s.trim().toDoubleOrNull() ?: 0.0
    EVal.Missing -> 0.0
}

fun EVal.asStr(): String = when (this) {
    is EVal.Num -> formatNum(v)
    is EVal.Str -> s
    EVal.Missing -> ""
}

fun EVal.truthy(): Boolean = when (this) {
    is EVal.Num -> v != 0.0
    is EVal.Str -> s.isNotEmpty() && s != "0"
    EVal.Missing -> false
}

/** 变量解析入口：渲染器按每次绘制构造一个实现（内建变量 + 主题 Var + 天气）。 */
interface VarResolver {
    fun resolve(name: String): EVal
}

/**
 * 锁屏表达式求值器（子集）：数字/字符串、四则 + - * / %、比较
 * eq/gt/lt/ge/le 函数式与 ==/!=/>/</>=/<= 中缀、ifelse（可变参数，
 * 奇数个参数时最后一个是默认值）、int()/abs()/min()/max()/not()、
 * 字符串 + 拼接、#var 与 @var 引用（变量名可含点，如
 * #Weather.today.mintemp、#GoUnlock.move_y）。未知变量一律 0，
 * Weather.* 无真实值时为 Missing。解析结果按原文缓存 AST。
 */
object Expr {

    private val astCache = HashMap<String, Node>()

    fun eval(text: String, resolver: VarResolver): EVal {
        val ast = synchronized(astCache) {
            astCache.getOrPut(text) { Parser(text).parse() }
        }
        return ast.eval(resolver)
    }

    // ---------- AST ----------

    private sealed interface Node {
        fun eval(r: VarResolver): EVal
    }

    private class NumNode(val v: Double) : Node {
        override fun eval(r: VarResolver): EVal = EVal.Num(v)
    }

    private class StrNode(val s: String) : Node {
        override fun eval(r: VarResolver): EVal = EVal.Str(s)
    }

    private class VarNode(val name: String) : Node {
        override fun eval(r: VarResolver): EVal = r.resolve(name)
    }

    private class UnaryNode(val op: String, val a: Node) : Node {
        override fun eval(r: VarResolver): EVal {
            val v = a.eval(r)
            if (v === EVal.Missing) return EVal.Missing
            return when (op) {
                "-" -> EVal.Num(-v.asNum())
                "!" -> EVal.Num(if (v.truthy()) 0.0 else 1.0)
                else -> v
            }
        }
    }

    private class BinaryNode(val op: String, val a: Node, val b: Node) : Node {
        override fun eval(r: VarResolver): EVal {
            val x = a.eval(r)
            val y = b.eval(r)
            if (x === EVal.Missing || y === EVal.Missing) return EVal.Missing
            return when (op) {
                "+" -> if (x is EVal.Str || y is EVal.Str) {
                    EVal.Str(x.asStr() + y.asStr())
                } else {
                    EVal.Num(x.asNum() + y.asNum())
                }
                "-" -> EVal.Num(x.asNum() - y.asNum())
                "*" -> EVal.Num(x.asNum() * y.asNum())
                "/" -> EVal.Num(if (y.asNum() == 0.0) 0.0 else x.asNum() / y.asNum())
                "%" -> EVal.Num(if (y.asNum() == 0.0) 0.0 else x.asNum() % y.asNum())
                "&&" -> EVal.Num(if (x.truthy() && y.truthy()) 1.0 else 0.0)
                "||" -> EVal.Num(if (x.truthy() || y.truthy()) 1.0 else 0.0)
                "==" -> EVal.Num(if (looseEquals(x, y)) 1.0 else 0.0)
                "!=" -> EVal.Num(if (looseEquals(x, y)) 0.0 else 1.0)
                ">" -> EVal.Num(if (compare(x, y) > 0) 1.0 else 0.0)
                "<" -> EVal.Num(if (compare(x, y) < 0) 1.0 else 0.0)
                ">=" -> EVal.Num(if (compare(x, y) >= 0) 1.0 else 0.0)
                "<=" -> EVal.Num(if (compare(x, y) <= 0) 1.0 else 0.0)
                else -> EVal.Num(0.0)
            }
        }
    }

    private class CallNode(val name: String, val args: List<Node>) : Node {
        override fun eval(r: VarResolver): EVal {
            when (name) {
                "ifelse" -> {
                    // ifelse(c1, v1, c2, v2, ..., [default])
                    var i = 0
                    while (i + 1 < args.size) {
                        if (args[i].eval(r).truthy()) return args[i + 1].eval(r)
                        i += 2
                    }
                    return if (i < args.size) args[i].eval(r) else EVal.Num(0.0)
                }
                "eq", "ne", "gt", "lt", "ge", "le" -> {
                    if (args.size < 2) return EVal.Num(0.0)
                    val x = args[0].eval(r)
                    val y = args[1].eval(r)
                    if (x === EVal.Missing || y === EVal.Missing) return EVal.Missing
                    val c = compare(x, y)
                    val ok = when (name) {
                        "eq" -> looseEquals(x, y)
                        "ne" -> !looseEquals(x, y)
                        "gt" -> c > 0
                        "lt" -> c < 0
                        "ge" -> c >= 0
                        else -> c <= 0
                    }
                    return EVal.Num(if (ok) 1.0 else 0.0)
                }
                "int" -> {
                    val v = args.firstOrNull()?.eval(r) ?: return EVal.Num(0.0)
                    if (v === EVal.Missing) return EVal.Missing
                    return EVal.Num(v.asNum().toLong().toDouble())
                }
                "abs" -> {
                    val v = args.firstOrNull()?.eval(r) ?: return EVal.Num(0.0)
                    if (v === EVal.Missing) return EVal.Missing
                    return EVal.Num(abs(v.asNum()))
                }
                "min", "max" -> {
                    if (args.isEmpty()) return EVal.Num(0.0)
                    var acc: Double? = null
                    for (a in args) {
                        val v = a.eval(r)
                        if (v === EVal.Missing) return EVal.Missing
                        val n = v.asNum()
                        acc = if (acc == null) n else if (name == "min") min(acc, n) else max(acc, n)
                    }
                    return EVal.Num(acc ?: 0.0)
                }
                "not" -> {
                    val v = args.firstOrNull()?.eval(r) ?: return EVal.Num(1.0)
                    if (v === EVal.Missing) return EVal.Missing
                    return EVal.Num(if (v.truthy()) 0.0 else 1.0)
                }
                else -> return EVal.Num(0.0)
            }
        }
    }

    private fun looseEquals(x: EVal, y: EVal): Boolean {
        if (x is EVal.Str || y is EVal.Str) return x.asStr() == y.asStr()
        return x.asNum() == y.asNum()
    }

    private fun compare(x: EVal, y: EVal): Int {
        if (x is EVal.Str && y is EVal.Str) return x.s.compareTo(y.s)
        return x.asNum().compareTo(y.asNum())
    }

    // ---------- 解析 ----------

    private class Parser(private val src: String) {
        private var pos = 0

        fun parse(): Node = parseOr()

        private fun peek(): Char = if (pos < src.length) src[pos] else Char.MIN_VALUE

        private fun skipWs() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun eat(s: String): Boolean {
            skipWs()
            if (src.startsWith(s, pos)) {
                pos += s.length
                return true
            }
            return false
        }

        private fun parseOr(): Node {
            var left = parseAnd()
            while (true) {
                left = when {
                    eat("||") -> BinaryNode("||", left, parseAnd())
                    else -> return left
                }
            }
        }

        private fun parseAnd(): Node {
            var left = parseCmp()
            while (true) {
                left = when {
                    eat("&&") -> BinaryNode("&&", left, parseCmp())
                    else -> return left
                }
            }
        }

        private fun parseCmp(): Node {
            var left = parseAdd()
            while (true) {
                val op = when {
                    eat("==") -> "=="
                    eat("!=") -> "!="
                    eat(">=") -> ">="
                    eat("<=") -> "<="
                    eat(">") -> ">"
                    eat("<") -> "<"
                    else -> return left
                }
                left = BinaryNode(op, left, parseAdd())
            }
        }

        private fun parseAdd(): Node {
            var left = parseMul()
            while (true) {
                left = when {
                    eat("+") -> BinaryNode("+", left, parseMul())
                    eat("-") -> BinaryNode("-", left, parseMul())
                    else -> return left
                }
            }
        }

        private fun parseMul(): Node {
            var left = parseUnary()
            while (true) {
                left = when {
                    eat("*") -> BinaryNode("*", left, parseUnary())
                    eat("/") -> BinaryNode("/", left, parseUnary())
                    eat("%") -> BinaryNode("%", left, parseUnary())
                    else -> return left
                }
            }
        }

        private fun parseUnary(): Node {
            skipWs()
            return when {
                eat("-") -> UnaryNode("-", parseUnary())
                eat("!") -> UnaryNode("!", parseUnary())
                eat("+") -> parseUnary()
                else -> parsePrimary()
            }
        }

        private fun parsePrimary(): Node {
            skipWs()
            val c = peek()
            return when {
                c == '(' -> {
                    pos++
                    val inner = parseOr()
                    eat(")")
                    inner
                }
                c == '\'' -> StrNode(parseStringLiteral())
                c == '#' || c == '@' -> {
                    pos++
                    VarNode(parseName())
                }
                c.isDigit() || c == '.' -> NumNode(parseNumber())
                c.isLetter() || c == '_' -> {
                    val name = parseName()
                    skipWs()
                    if (peek() == '(') {
                        pos++
                        val args = ArrayList<Node>()
                        skipWs()
                        if (peek() != ')') {
                            while (true) {
                                args.add(parseOr())
                                skipWs()
                                if (peek() == ',') {
                                    pos++
                                    continue
                                }
                                break
                            }
                        }
                        eat(")")
                        CallNode(name, args)
                    } else {
                        // 裸标识符：按变量处理（如 textExp 里不带 # 的名字）
                        VarNode(name)
                    }
                }
                else -> {
                    // 无法识别的字符：跳过并给 0，保证整条表达式不抛
                    if (pos < src.length) pos++
                    NumNode(0.0)
                }
            }
        }

        private fun parseStringLiteral(): String {
            // 当前字符是单引号；连续两个单引号表示字面单引号
            pos++
            val sb = StringBuilder()
            while (pos < src.length) {
                val ch = src[pos]
                if (ch == '\'') {
                    if (pos + 1 < src.length && src[pos + 1] == '\'') {
                        sb.append('\'')
                        pos += 2
                    } else {
                        pos++
                        break
                    }
                } else {
                    sb.append(ch)
                    pos++
                }
            }
            return sb.toString()
        }

        private fun parseName(): String {
            val start = pos
            while (pos < src.length) {
                val ch = src[pos]
                if (ch.isLetterOrDigit() || ch == '_' || ch == '.') pos++ else break
            }
            return src.substring(start, pos)
        }

        private fun parseNumber(): Double {
            val start = pos
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
            return src.substring(start, pos).toDoubleOrNull() ?: 0.0
        }
    }
}
