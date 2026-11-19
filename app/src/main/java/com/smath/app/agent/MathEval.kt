package com.smath.app.agent

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/** Small, dependency-free complex number used by the calculator and function plots. */
data class ComplexNumber(val re: Double, val im: Double = 0.0) {
    operator fun plus(other: ComplexNumber) = ComplexNumber(re + other.re, im + other.im)
    operator fun minus(other: ComplexNumber) = ComplexNumber(re - other.re, im - other.im)
    operator fun times(other: ComplexNumber) = ComplexNumber(
        re * other.re - im * other.im,
        re * other.im + im * other.re
    )

    operator fun div(other: ComplexNumber): ComplexNumber {
        val d = other.re * other.re + other.im * other.im
        if (d == 0.0) throw MathEvalException("除数不能为 0")
        return ComplexNumber(
            (re * other.re + im * other.im) / d,
            (im * other.re - re * other.im) / d
        )
    }

    fun pow(other: ComplexNumber): ComplexNumber {
        if (other.im == 0.0 && other.re == 0.0) return ComplexNumber(1.0)
        if (im == 0.0 && re > 0.0 && other.im == 0.0) {
            return ComplexNumber(re.pow(other.re))
        }
        val log = complexLn()
        return (log * other).complexExp()
    }

    fun magnitude(): Double = hypot(re, im)

    fun isReal(): Boolean = abs(im) < 1e-10
}

class MathEvalException(message: String, val position: Int = -1) : Exception(message)

/** Evaluates a numeric expression. [x] supplies the variable used by function plots. */
fun evalMath(expression: String, x: Double? = null): ComplexNumber =
    ExprParser(expression, x).parse()

fun formatComplex(value: ComplexNumber): String {
    fun number(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return v.toString()
        if (abs(v) < 1e-10) return "0"
        val rounded = if (abs(v) >= 1e6 || abs(v) < 1e-4) {
            String.format(java.util.Locale.US, "%.6e", v)
        } else {
            String.format(java.util.Locale.US, "%.10f", v).trimEnd('0').trimEnd('.')
        }
        return rounded
    }
    val real = number(value.re)
    val imag = number(value.im)
    return when {
        abs(value.im) < 1e-10 -> real
        abs(value.re) < 1e-10 -> if (imag == "1") "i" else if (imag == "-1") "-i" else "${imag}i"
        value.im > 0 -> "$real + ${if (imag == "1") "i" else "${imag}i"}"
        else -> "$real - ${if (imag == "-1") "i" else "${imag.removePrefix("-")}i"}"
    }
}

private fun Double.pow(exponent: Double): Double = Math.pow(this, exponent)

private fun ComplexNumber.complexExp() = ComplexNumber(
    exp(re) * cos(im),
    exp(re) * sin(im)
)

private fun ComplexNumber.complexLn(): ComplexNumber {
    if (re == 0.0 && im == 0.0) throw MathEvalException("对数参数不能为 0")
    return ComplexNumber(ln(magnitude()), atan2(im, re))
}

private fun ComplexNumber.complexSqrt(): ComplexNumber {
    if (im == 0.0 && re >= 0.0) return ComplexNumber(sqrt(re))
    val r = sqrt(magnitude())
    val theta = atan2(im, re) / 2.0
    return ComplexNumber(r * cos(theta), r * sin(theta))
}

private class ExprParser(
    private val source: String,
    private val variableX: Double?
) {
    private var pos = 0

    fun parse(): ComplexNumber {
        if (source.isBlank()) throw MathEvalException("表达式为空", 0)
        val value = parseAddSub()
        skipSpaces()
        if (pos < source.length) throw MathEvalException("无法识别的字符“${source[pos]}”", pos)
        if (value.re.isNaN() || value.im.isNaN()) throw MathEvalException("结果不是有效数字")
        return value
    }

    private fun parseAddSub(): ComplexNumber {
        var value = parseMulDiv()
        while (true) {
            skipSpaces()
            when {
                match('+') -> value += parseMulDiv()
                match('-') -> value -= parseMulDiv()
                else -> return value
            }
        }
    }

    private fun parseMulDiv(): ComplexNumber {
        var value = parseUnary()
        while (true) {
            skipSpaces()
            when {
                match('*') -> value *= parseUnary()
                match('/') -> value /= parseUnary()
                // 2x, 2pi, 3i, 2(x+1) 这样的隐式乘法。
                pos < source.length && (source[pos].isLetterOrDigit() || source[pos] == '(') -> {
                    value *= parseUnary()
                }
                else -> return value
            }
        }
    }

    private fun parseUnary(): ComplexNumber {
        skipSpaces()
        if (match('+')) return parseUnary()
        if (match('-')) return ComplexNumber(-1.0) * parseUnary()
        return parsePower()
    }

    private fun parsePower(): ComplexNumber {
        val base = parsePrimary()
        skipSpaces()
        return if (match('^')) base.pow(parseUnary()) else base
    }

    private fun parsePrimary(): ComplexNumber {
        skipSpaces()
        if (pos >= source.length) throw MathEvalException("表达式意外结束", pos)
        if (match('(')) {
            val value = parseAddSub()
            skipSpaces()
            if (!match(')')) throw MathEvalException("缺少右括号", pos)
            return value
        }
        if (source[pos].isDigit() || (source[pos] == '.' && pos + 1 < source.length && source[pos + 1].isDigit())) {
            return parseNumber()
        }
        if (source[pos].isLetter() || source[pos] == '_') {
            return parseIdentifier()
        }
        throw MathEvalException("无法识别的字符“${source[pos]}”", pos)
    }

    private fun parseNumber(): ComplexNumber {
        val start = pos
        while (pos < source.length && source[pos].isDigit()) pos++
        if (pos < source.length && source[pos] == '.') {
            pos++
            while (pos < source.length && source[pos].isDigit()) pos++
        }
        if (pos < source.length && (source[pos] == 'e' || source[pos] == 'E')) {
            val exponentStart = pos
            pos++
            if (pos < source.length && (source[pos] == '+' || source[pos] == '-')) pos++
            val digitsStart = pos
            while (pos < source.length && source[pos].isDigit()) pos++
            if (digitsStart == pos) pos = exponentStart
        }
        val text = source.substring(start, pos)
        val value = text.toDoubleOrNull() ?: throw MathEvalException("无效数字“$text”", start)
        return ComplexNumber(value)
    }

    private fun parseIdentifier(): ComplexNumber {
        val start = pos
        while (pos < source.length && (source[pos].isLetterOrDigit() || source[pos] == '_')) pos++
        val name = source.substring(start, pos)
        skipSpaces()
        if (match('(')) {
            val args = mutableListOf<ComplexNumber>()
            if (!peek(')')) {
                while (true) {
                    args += parseAddSub()
                    skipSpaces()
                    if (!match(',')) break
                }
            }
            skipSpaces()
            if (!match(')')) throw MathEvalException("函数 $name 缺少右括号", pos)
            return call(name, args, start)
        }
        return when (name.lowercase()) {
            "pi" -> ComplexNumber(PI)
            "e" -> ComplexNumber(Math.E)
            "i", "j" -> ComplexNumber(0.0, 1.0)
            "x" -> variableX?.let(::ComplexNumber)
                ?: throw MathEvalException("这里不允许变量 x", start)
            else -> throw MathEvalException("未知的符号“$name”", start)
        }
    }

    private fun call(name: String, args: List<ComplexNumber>, start: Int): ComplexNumber {
        if (name.lowercase() == "log" && args.size == 2) {
            return args[1].complexLn() / args[0].complexLn()
        }
        if (args.size != 1) throw MathEvalException("函数 $name 需要 1 个参数", start)
        val z = args[0]
        fun realOnly(): Double {
            if (!z.isReal()) throw MathEvalException("函数 $name 目前只支持实数", start)
            return z.re
        }
        return when (name.lowercase()) {
            "sin" -> ComplexNumber(sin(realOnly()))
            "cos" -> ComplexNumber(cos(realOnly()))
            "tan" -> ComplexNumber(tan(realOnly()))
            "asin" -> ComplexNumber(asin(realOnly()))
            "acos" -> ComplexNumber(acos(realOnly()))
            "atan" -> ComplexNumber(atan(realOnly()))
            "sinh" -> ComplexNumber(sinh(realOnly()))
            "cosh" -> ComplexNumber(cosh(realOnly()))
            "tanh" -> ComplexNumber(tanh(realOnly()))
            "sqrt" -> z.complexSqrt()
            "abs" -> ComplexNumber(z.magnitude())
            "exp" -> z.complexExp()
            "ln" -> z.complexLn()
            "log10" -> z.complexLn() / ComplexNumber(ln(10.0))
            "floor" -> ComplexNumber(floor(realOnly()))
            "ceil" -> ComplexNumber(ceil(realOnly()))
            "round" -> ComplexNumber(round(realOnly()))
            "re" -> ComplexNumber(z.re)
            "im" -> ComplexNumber(z.im)
            "conj" -> ComplexNumber(z.re, -z.im)
            "arg" -> ComplexNumber(atan2(z.im, z.re))
            else -> throw MathEvalException("未知函数“$name”", start)
        }
    }

    private fun skipSpaces() {
        while (pos < source.length && source[pos].isWhitespace()) pos++
    }

    private fun peek(ch: Char): Boolean {
        skipSpaces()
        return pos < source.length && source[pos] == ch
    }

    private fun match(ch: Char): Boolean {
        skipSpaces()
        if (pos < source.length && source[pos] == ch) {
            pos++
            return true
        }
        return false
    }
}