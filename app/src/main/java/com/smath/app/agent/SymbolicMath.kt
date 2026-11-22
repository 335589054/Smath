package com.smath.app.agent

import java.math.BigInteger

/**
 * 纯 Kotlin 的符号计算 / 线性代数内核（无 Android 依赖，只用 kotlin 标准库 + java.math.BigInteger）。
 *
 * 设计要点：
 *  - 一切数值都用 [SymFrac]（BigInteger 分子/分母 + 约分）精确表示，绝不用 Double 做代数；
 *  - 多变量稀疏多项式 [SymPoly]：Map<单项式, 系数>，单项式按「总次数降序 → 变量名字典序」排序，输出稳定；
 *  - 分式 [SymRational] = 分子多项式 / 分母多项式，构造时自动规范（约整数公因数、约公共单项式、单变量精确除法）；
 *  - 行列式：数值矩阵用带主元交换的分数高斯消元（精确），符号矩阵用带记忆化的 Laplace 展开；
 *  - 因式分解：数值公因数 + 公单项式 + 有理根定理（|p|,|q| ≤ 60）+ 平方差/立方和差/完全平方恒等式。
 *
 * 说明：连续 2~4 个纯小写字母的标识符按单字母变量相乘处理（`2xy` 等价 `2*x*y`）；
 * 含数字/下划线/大写字母的标识符、以及希腊字母名（alpha、lambda、theta …）整体作为一个变量名。
 */

/** 一次命令执行的结果。字段语义见任务约定。 */
data class MathResult(
    val command: String,
    val title: String,
    val plain: String,
    val latex: String = "",
    val extra: List<String> = emptyList(),
    val error: String? = null
)

/** 计算过程中可预期的错误，message 一律为中文。 */
internal class SymException(message: String) : Exception(message)

// ============================================================================================
// 有理数
// ============================================================================================

/** 精确有理数：分子/分母均为 BigInteger，构造时约分、分母恒为正。 */
internal class SymFrac private constructor(val num: BigInteger, val den: BigInteger) : Comparable<SymFrac> {
    val isZero: Boolean get() = num.signum() == 0
    val isOne: Boolean get() = num == BigInteger.ONE && den == BigInteger.ONE
    val isInteger: Boolean get() = den == BigInteger.ONE
    val isNegative: Boolean get() = num.signum() < 0

    fun negate(): SymFrac = of(num.negate(), den)
    operator fun unaryMinus(): SymFrac = negate()
    operator fun plus(other: SymFrac): SymFrac = of(num * other.den + other.num * den, den * other.den)
    operator fun minus(other: SymFrac): SymFrac = of(num * other.den - other.num * den, den * other.den)
    operator fun times(other: SymFrac): SymFrac = of(num * other.num, den * other.den)

    operator fun div(other: SymFrac): SymFrac {
        if (other.isZero) throw SymException("除数不能为 0")
        return of(num * other.den, den * other.num)
    }

    fun reciprocal(): SymFrac {
        if (isZero) throw SymException("除数不能为 0")
        return of(den, num)
    }

    fun abs(): SymFrac = if (isNegative) negate() else this

    fun pow(exponent: Int): SymFrac {
        if (exponent < 0) throw SymException("指数必须是非负整数")
        return of(num.pow(exponent), den.pow(exponent))
    }

    override fun compareTo(other: SymFrac): Int = (num * other.den).compareTo(other.num * den)

    override fun equals(other: Any?): Boolean =
        other is SymFrac && num == other.num && den == other.den

    override fun hashCode(): Int = num.hashCode() * 31 + den.hashCode()

    fun toPlain(): String = if (den == BigInteger.ONE) num.toString() else "${num}/${den}"

    override fun toString(): String = toPlain()

    companion object {
        val ZERO: SymFrac = SymFrac(BigInteger.ZERO, BigInteger.ONE)
        val ONE: SymFrac = SymFrac(BigInteger.ONE, BigInteger.ONE)
        val MINUS_ONE: SymFrac = SymFrac(BigInteger.valueOf(-1), BigInteger.ONE)

        fun of(numerator: BigInteger, denominator: BigInteger = BigInteger.ONE): SymFrac {
            if (denominator.signum() == 0) throw SymException("分母不能为 0")
            if (numerator.signum() == 0) return ZERO
            var n = numerator
            var d = denominator
            if (d.signum() < 0) {
                n = n.negate()
                d = d.negate()
            }
            val g = n.gcd(d)
            if (g > BigInteger.ONE) {
                n = n.divide(g)
                d = d.divide(g)
            }
            return SymFrac(n, d)
        }

        fun ofInt(value: Int): SymFrac = of(BigInteger.valueOf(value.toLong()))
    }
}

// ============================================================================================
// 单项式
// ============================================================================================

/**
 * 单项式：排序后的 (变量名, 指数) 列表。data class 的 equals 基于列表内容，可直接做 Map 键。
 * 排序规则：[compareTo] 先按总次数降序，再按变量名升序、指数降序，保证输出稳定。
 */
internal data class SymMonomial(val powers: List<Pair<String, Int>>) : Comparable<SymMonomial> {
    val totalDegree: Int
        get() {
            var sum = 0
            for (p in powers) sum += p.second
            return sum
        }

    val isOne: Boolean get() = powers.isEmpty()

    fun degrees(variable: String): Int {
        for (p in powers) if (p.first == variable) return p.second
        return 0
    }

    fun multiply(other: SymMonomial): SymMonomial {
        if (isOne) return other
        if (other.isOne) return this
        val map = LinkedHashMap<String, Int>()
        for (p in powers) map[p.first] = (map[p.first] ?: 0) + p.second
        for (p in other.powers) map[p.first] = (map[p.first] ?: 0) + p.second
        return create(map)
    }

    fun pow(exponent: Int): SymMonomial {
        if (exponent == 0) return ONE
        return SymMonomial(powers.map { it.first to it.second * exponent })
    }

    /** 除以 [other]；指数不足（除不尽）返回 null。 */
    fun tryDivide(other: SymMonomial): SymMonomial? {
        if (other.isOne) return this
        val remaining = LinkedHashMap<String, Int>()
        for (p in powers) remaining[p.first] = p.second
        for (p in other.powers) {
            val have = remaining[p.first] ?: 0
            if (have < p.second) return null
            remaining[p.first] = have - p.second
        }
        return create(remaining)
    }

    /** 两个单项式的最大公共因子（逐变量取指数最小值）。 */
    fun intersect(other: SymMonomial): SymMonomial {
        val map = LinkedHashMap<String, Int>()
        for (p in powers) {
            val e = other.degrees(p.first)
            if (e > 0) map[p.first] = if (p.second < e) p.second else e
        }
        return create(map)
    }

    override fun compareTo(other: SymMonomial): Int {
        if (totalDegree != other.totalDegree) return other.totalDegree.compareTo(totalDegree)
        var i = 0
        var j = 0
        while (i < powers.size && j < other.powers.size) {
            val left = powers[i]
            val right = other.powers[j]
            val byName = left.first.compareTo(right.first)
            if (byName != 0) return byName
            if (left.second != right.second) return right.second.compareTo(left.second)
            i++
            j++
        }
        return when {
            i < powers.size -> -1
            j < other.powers.size -> 1
            else -> 0
        }
    }

    companion object {
        val ONE: SymMonomial = SymMonomial(emptyList())

        fun of(variable: String, exponent: Int = 1): SymMonomial =
            if (exponent <= 0) ONE else SymMonomial(listOf(variable to exponent))

        /** 由变量→指数表构造，丢弃 0 指数并按变量名排序。 */
        fun create(map: Map<String, Int>): SymMonomial {
            val list = map.entries
                .filter { it.value != 0 }
                .sortedBy { it.key }
                .map { it.key to it.value }
            if (list.isEmpty()) return ONE
            return SymMonomial(list)
        }
    }
}

// ============================================================================================
// 多变量多项式
// ============================================================================================

/** 稀疏多变量多项式，内部恒等于「已合并同类项、按 [SymMonomial] 顺序排列」的不可变映射。 */
internal class SymPoly private constructor(private val ordered: Map<SymMonomial, SymFrac>) {
    val terms: Map<SymMonomial, SymFrac> get() = ordered
    val isZero: Boolean get() = ordered.isEmpty()
    val termCount: Int get() = ordered.size
    val isConstant: Boolean
        get() = ordered.isEmpty() || (ordered.size == 1 && ordered.containsKey(SymMonomial.ONE))

    fun constantValue(): SymFrac {
        if (ordered.isEmpty()) return SymFrac.ZERO
        if (ordered.size > 1) throw SymException("表达式不是常数")
        return ordered[SymMonomial.ONE] ?: throw SymException("表达式不是常数")
    }

    fun variables(): List<String> {
        val set = LinkedHashSet<String>()
        for (m in ordered.keys) for (p in m.powers) set.add(p.first)
        return set.toList()
    }

    fun singleVariable(): String? {
        val vars = variables()
        return if (vars.size == 1) vars[0] else null
    }

    fun degreeIn(variable: String): Int {
        var degree = 0
        for (m in ordered.keys) {
            val e = m.degrees(variable)
            if (e > degree) degree = e
        }
        return degree
    }

    /** 首项系数（按输出顺序的第一项）。 */
    fun leadingCoefficient(): SymFrac =
        if (ordered.isEmpty()) SymFrac.ZERO else ordered.values.first()

    operator fun plus(other: SymPoly): SymPoly {
        val raw = LinkedHashMap<SymMonomial, SymFrac>()
        for ((m, c) in ordered) raw[m] = c
        for ((m, c) in other.ordered) {
            val prev = raw[m]
            raw[m] = if (prev == null) c else prev + c
        }
        return build(raw)
    }

    operator fun minus(other: SymPoly): SymPoly = this + other.negate()

    operator fun unaryMinus(): SymPoly {
        val raw = LinkedHashMap<SymMonomial, SymFrac>()
        for ((m, c) in ordered) raw[m] = c.negate()
        return build(raw)
    }

    fun negate(): SymPoly = -this

    operator fun times(other: SymPoly): SymPoly {
        if (isZero || other.isZero) return ZERO
        val raw = LinkedHashMap<SymMonomial, SymFrac>()
        for ((m1, c1) in ordered) {
            for ((m2, c2) in other.ordered) {
                val m = m1.multiply(m2)
                val prev = raw[m]
                raw[m] = if (prev == null) c1 * c2 else prev + c1 * c2
            }
        }
        return build(raw)
    }

    fun scale(factor: SymFrac): SymPoly {
        if (factor.isZero) return ZERO
        if (factor.isOne) return this
        val raw = LinkedHashMap<SymMonomial, SymFrac>()
        for ((m, c) in ordered) raw[m] = c * factor
        return build(raw)
    }

    /** 非负整数次幂，二进制快速幂。 */
    fun pow(exponent: Int): SymPoly {
        if (exponent < 0) throw SymException("指数必须是非负整数")
        if (exponent > 512) throw SymException("指数太大（最多 512）")
        var result = ONE
        var base = this
        var e = exponent
        while (e > 0) {
            if (e and 1 == 1) result *= base
            e = e shr 1
            if (e > 0) base *= base
        }
        return result
    }

    fun divideByMonomial(m: SymMonomial): SymPoly {
        if (m.isOne) return this
        val raw = LinkedHashMap<SymMonomial, SymFrac>()
        for ((term, c) in ordered) {
            val q = term.tryDivide(m) ?: throw SymException("单项式不能整除多项式")
            raw[q] = c
        }
        return build(raw)
    }

    /** 所有项共有的单项式因子。 */
    fun commonMonomial(): SymMonomial {
        if (ordered.isEmpty()) return SymMonomial.ONE
        val map = LinkedHashMap<String, Int>()
        var first = true
        for (m in ordered.keys) {
            if (first) {
                for (p in m.powers) map[p.first] = p.second
                first = false
            } else {
                for (key in map.keys.toList()) {
                    val e = m.degrees(key)
                    if (e == 0) map.remove(key) else if (e < map.getValue(key)) map[key] = e
                }
            }
        }
        return SymMonomial.create(map)
    }

    fun denominatorLcm(): BigInteger {
        var l = BigInteger.ONE
        for (c in ordered.values) l = lcmBig(l, c.den)
        return l
    }

    fun integerGcd(): BigInteger {
        var g = BigInteger.ZERO
        for (c in ordered.values) g = g.gcd(c.num.abs())
        return g
    }

    /** 变量代入（代入值为有理数），返回新多项式。 */
    fun substitute(values: Map<String, SymFrac>): SymPoly =
        substitutePolys(values.mapValues { (_, value) -> constant(value) })

    /**
     * 变量代入（代入值可以是任意多项式，用于「代入后仍保留符号」的场景）。
     * 做法：把每个单项式拆成「系数 × 各变量幂」，用幂运算替换变量后再相乘累加。
     */
    fun substitutePolys(values: Map<String, SymPoly>): SymPoly {
        if (values.isEmpty()) return this
        var result = ZERO
        for ((m, c) in ordered) {
            var term = constant(c)
            for (p in m.powers) {
                val value = values[p.first]
                term *= if (value == null) {
                    monomial(SymMonomial.create(mapOf(p.first to p.second)), SymFrac.ONE)
                } else {
                    value.pow(p.second)
                }
            }
            result += term
        }
        return result
    }

    override fun equals(other: Any?): Boolean = other is SymPoly && ordered == other.ordered

    override fun hashCode(): Int = ordered.hashCode()

    override fun toString(): String = formatPolyPlain(this)

    companion object {
        val ZERO: SymPoly = SymPoly(emptyMap())
        val ONE: SymPoly = SymPoly(mapOf(SymMonomial.ONE to SymFrac.ONE))

        fun constant(value: SymFrac): SymPoly =
            if (value.isZero) ZERO else SymPoly(mapOf(SymMonomial.ONE to value))

        fun variable(name: String): SymPoly = SymPoly(mapOf(SymMonomial.of(name) to SymFrac.ONE))

        fun monomial(m: SymMonomial, coefficient: SymFrac): SymPoly =
            if (coefficient.isZero) ZERO else SymPoly(mapOf(m to coefficient))

        /** 合并同类项 + 排序。 */
        fun build(raw: Map<SymMonomial, SymFrac>): SymPoly {
            val cleaned = LinkedHashMap<SymMonomial, SymFrac>()
            for ((m, c) in raw) if (!c.isZero) cleaned[m] = c
            if (cleaned.isEmpty()) return ZERO
            val result = LinkedHashMap<SymMonomial, SymFrac>()
            for (m in cleaned.keys.sorted()) result[m] = cleaned.getValue(m)
            return SymPoly(result)
        }
    }
}

// ============================================================================================
// 有理函数（分式）：分子/分母都是多项式
// ============================================================================================

/** 分式 num/den。所有工厂方法都做规范化，保证同一表达式得到同一个规范形。 */
internal class SymRational private constructor(val num: SymPoly, val den: SymPoly) {
    fun negate(): SymRational = SymRational(num.negate(), den)

    operator fun plus(other: SymRational): SymRational =
        canonical(num * other.den + other.num * den, den * other.den)

    operator fun minus(other: SymRational): SymRational =
        canonical(num * other.den - other.num * den, den * other.den)

    operator fun times(other: SymRational): SymRational = canonical(num * other.num, den * other.den)

    operator fun div(other: SymRational): SymRational {
        if (other.num.isZero) throw SymException("除数不能为 0")
        return canonical(num * other.den, den * other.num)
    }

    fun pow(exponent: Int): SymRational {
        if (exponent < 0) throw SymException("指数必须是非负整数")
        return canonical(num.pow(exponent), den.pow(exponent))
    }

    /** 代入变量值；若分母代入后为 0 则报错。 */
    fun substitute(values: Map<String, SymFrac>): SymRational =
        canonical(num.substitute(values), den.substitute(values))

    /** 代入任意表达式（符号代入），分母不自动约分，交给 [canonical] 处理。 */
    fun substitutePolys(values: Map<String, SymPoly>): SymRational {
        if (values.isEmpty()) return this
        val newDen = den.substitutePolys(values)
        if (newDen.isZero) throw SymException("代入后分母为 0")
        return canonical(num.substitutePolys(values), newDen)
    }

    /** 分子/分母中的变量集合。 */
    fun variables(): List<String> {
        val set = LinkedHashSet<String>()
        set.addAll(num.variables())
        set.addAll(den.variables())
        return set.toList()
    }

    companion object {
        val ZERO: SymRational = SymRational(SymPoly.ZERO, SymPoly.ONE)
        val ONE: SymRational = SymRational(SymPoly.ONE, SymPoly.ONE)

        fun of(poly: SymPoly): SymRational = canonical(poly, SymPoly.ONE)

        fun ofConstant(value: SymFrac): SymRational = canonical(SymPoly.constant(value), SymPoly.ONE)

        fun variable(name: String): SymRational = canonical(SymPoly.variable(name), SymPoly.ONE)

        /**
         * 规范化：
         *  1. 分母为常数 → 直接除进分子；
         *  2. 两边同乘分母的最小公倍数，消掉「分数系数」；
         *  3. 约掉两边系数的整数公因数；
         *  4. 分母首项系数取正；
         *  5. 约掉公共单项式因子；
         *  6. 单变量时求分子分母的最大公因式后约分，再退化为精确多项式除法（能整除就化简成多项式）。
         */
        fun canonical(numerator: SymPoly, denominator: SymPoly): SymRational =
            canonicalInner(numerator, denominator, 0)

        private fun canonicalInner(numerator: SymPoly, denominator: SymPoly, depth: Int): SymRational {
            if (denominator.isZero) throw SymException("分母不能为 0")
            if (numerator.isZero) return ZERO
            var n = numerator
            var d = denominator
            if (d.isConstant) {
                val c = d.constantValue()
                return SymRational(n.scale(c.reciprocal()), SymPoly.ONE)
            }
            val l = lcmBig(n.denominatorLcm(), d.denominatorLcm())
            if (l != BigInteger.ONE) {
                val factor = SymFrac.of(l)
                n = n.scale(factor)
                d = d.scale(factor)
            }
            val g = n.integerGcd().gcd(d.integerGcd())
            if (g > BigInteger.ONE) {
                val divisor = SymFrac.of(BigInteger.ONE, g)
                n = n.scale(divisor)
                d = d.scale(divisor)
            }
            if (d.leadingCoefficient().isNegative) {
                n = n.negate()
                d = d.negate()
            }
            val common = n.commonMonomial().intersect(d.commonMonomial())
            if (!common.isOne) {
                n = n.divideByMonomial(common)
                d = d.divideByMonomial(common)
            }
            val onlyVar = commonVariable(n, d)
            if (onlyVar != null && d.degreeIn(onlyVar) > 0) {
                // 先用欧几里得算法约掉公因式（如 (a-1)/(a^2-1) → 1/(a+1)），
                // 分母整除分子时再退化为多项式。
                if (depth < 4 && n.degreeIn(onlyVar) <= 20 && d.degreeIn(onlyVar) <= 20) {
                    val g = univariateGcd(n, d, onlyVar)
                    if (g.degreeIn(onlyVar) >= 1) {
                        val nq = divideUnivariate(n, g, onlyVar)
                        val dq = divideUnivariate(d, g, onlyVar)
                        if (nq.second.isZero && dq.second.isZero) {
                            return canonicalInner(nq.first, dq.first, depth + 1)
                        }
                    }
                }
                val division = divideUnivariate(n, d, onlyVar)
                if (division.second.isZero) return SymRational(division.first, SymPoly.ONE)
            }
            return SymRational(n, d)
        }

        private fun commonVariable(a: SymPoly, b: SymPoly): String? {
            val set = LinkedHashSet<String>()
            set.addAll(a.variables())
            set.addAll(b.variables())
            return if (set.size == 1) set.first() else null
        }
    }
}

// ============================================================================================
// 格式化（纯文本）
// ============================================================================================

private fun formatMonomialPlain(m: SymMonomial): String {
    if (m.isOne) return ""
    val sb = StringBuilder()
    for ((index, p) in m.powers.withIndex()) {
        if (index > 0) sb.append('*')
        sb.append(p.first)
        if (p.second > 1) sb.append('^').append(p.second)
    }
    return sb.toString()
}

/** 单项式（系数取绝对值）的纯文本形式，如 `3/2*x^2*y`。 */
private fun formatTermPlain(coefficient: SymFrac, monomial: SymMonomial): String {
    val abs = coefficient.abs()
    if (monomial.isOne) return abs.toPlain()
    val head = if (abs.isOne) "" else abs.toPlain() + "*"
    return head + formatMonomialPlain(monomial)
}

/** 多项式纯文本，如 `x^2 + 3*x*y - 1/2`。 */
private fun formatPolyPlain(poly: SymPoly): String {
    if (poly.isZero) return "0"
    val sb = StringBuilder()
    var first = true
    for ((m, c) in poly.terms) {
        val negative = c.isNegative
        if (first) {
            if (negative) sb.append('-')
            first = false
        } else {
            sb.append(if (negative) " - " else " + ")
        }
        sb.append(formatTermPlain(c, m))
    }
    return sb.toString()
}

private fun latexNumber(value: SymFrac): String {
    if (value.den == BigInteger.ONE) return value.num.toString()
    val sign = if (value.isNegative) "-" else ""
    return "$sign\\frac{${value.num.abs()}}{${value.den}}"
}

private fun formatMonomialLatex(m: SymMonomial): String {
    if (m.isOne) return ""
    val sb = StringBuilder()
    for (p in m.powers) {
        sb.append(latexVariableName(p.first))
        if (p.second > 1) sb.append("^{").append(p.second).append('}')
    }
    return sb.toString()
}

/** 希腊字母变量名在 LaTeX 里要写成命令（λ → \lambda），其余原样输出。 */
private fun latexVariableName(name: String): String = when (name) {
    "α" -> "\\alpha"
    "β" -> "\\beta"
    "γ" -> "\\gamma"
    "δ" -> "\\delta"
    "ε" -> "\\epsilon"
    "ζ" -> "\\zeta"
    "η" -> "\\eta"
    "θ" -> "\\theta"
    "ι" -> "\\iota"
    "κ" -> "\\kappa"
    "λ" -> "\\lambda"
    "μ" -> "\\mu"
    "ν" -> "\\nu"
    "ξ" -> "\\xi"
    "π" -> "\\pi"
    "ρ" -> "\\rho"
    "σ" -> "\\sigma"
    "τ" -> "\\tau"
    "φ" -> "\\phi"
    "χ" -> "\\chi"
    "ψ" -> "\\psi"
    "ω" -> "\\omega"
    else -> name
}

private fun formatTermLatex(coefficient: SymFrac, monomial: SymMonomial): String {
    val abs = coefficient.abs()
    if (monomial.isOne) return latexNumber(abs)
    val head = if (abs.isOne) "" else latexNumber(abs)
    return head + formatMonomialLatex(monomial)
}

/** 多项式 LaTeX，如 `x^{2} + 3xy - \frac{1}{2}`。 */
private fun formatPolyLatex(poly: SymPoly): String {
    if (poly.isZero) return "0"
    val sb = StringBuilder()
    var first = true
    for ((m, c) in poly.terms) {
        val negative = c.isNegative
        if (first) {
            if (negative) sb.append('-')
            first = false
        } else {
            sb.append(if (negative) " - " else " + ")
        }
        sb.append(formatTermLatex(c, m))
    }
    return sb.toString()
}

/** 分式纯文本；分母为常数时直接折进分子。 */
private fun formatRationalPlain(value: SymRational): String {
    if (value.den.isConstant) {
        val c = value.den.constantValue()
        return if (c.isOne) formatPolyPlain(value.num) else formatPolyPlain(value.num.scale(c.reciprocal()))
    }
    val n = formatPolyPlain(value.num)
    val d = formatPolyPlain(value.den)
    val nPart = if (value.num.termCount > 1) "($n)" else n
    // 分母是 x*y 这类多变量单项式时必须加括号，否则 a / x*y 会被读成 (a/x)*y。
    val dPart = if (value.den.termCount > 1 || value.den.variables().size > 1) "($d)" else d
    return "$nPart / $dPart"
}

private fun formatRationalLatex(value: SymRational): String {
    if (value.den.isConstant) {
        val c = value.den.constantValue()
        return if (c.isOne) formatPolyLatex(value.num) else formatPolyLatex(value.num.scale(c.reciprocal()))
    }
    return "\\frac{${formatPolyLatex(value.num)}}{${formatPolyLatex(value.den)}}"
}

/** `[[a, b], [c, d]]` 形式。 */
private fun matrixPlain(rows: List<List<String>>): String =
    "[[" + rows.joinToString("], [") { it.joinToString(", ") } + "]]"

/** `\begin{pmatrix} a & b \\ c & d \end{pmatrix}`。 */
private fun matrixLatex(rows: List<List<String>>): String =
    "\\begin{pmatrix} " + rows.joinToString(" \\\\ ") { it.joinToString(" & ") } + " \\end{pmatrix}"

// ============================================================================================
// 解析器（递归下降）
// ============================================================================================

/**
 * 表达式解析，直接产出分式（多项式 / 多项式）。
 *
 * 优先级：`+ -` < `* /` < 一元负号 < `^`（右结合）。
 * 支持隐式乘法：`2x`、`3(x+1)`、`(a+b)(a-b)`、`)` 后紧跟字母或括号、字母后紧跟 `(`。
 */
private class SymParser(private val src: String) {
    private var pos = 0
    private var depth = 0

    fun parse(): SymRational {
        if (src.isBlank()) throw SymException("表达式为空")
        val value = parseExpression()
        skipSpaces()
        if (pos < src.length) throw SymException("无法识别的字符「${src[pos]}」（第 ${pos + 1} 个字符附近）")
        return value
    }

    private fun skipSpaces() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    private fun peek(): Char = if (pos < src.length) src[pos] else '\u0000'

    private fun parseExpression(): SymRational {
        depth++
        if (depth > 150) throw SymException("表达式嵌套过深")
        var value = parseTerm()
        while (true) {
            skipSpaces()
            val ch = peek()
            if (ch == '+') {
                pos++
                value += parseTerm()
            } else if (ch == '-') {
                pos++
                value -= parseTerm()
            } else {
                depth--
                return value
            }
        }
    }

    private fun parseTerm(): SymRational {
        var value = parseUnary()
        while (true) {
            skipSpaces()
            val ch = peek()
            if (ch == '*') {
                pos++
                value *= parseUnary()
            } else if (ch == '/') {
                pos++
                value /= parseUnary()
            } else if (ch == '(' || ch == '_' || ch.isDigit() || ch.isLetter()) {
                // 隐式乘法
                value *= parseUnary()
            } else {
                return value
            }
        }
    }

    private fun parseUnary(): SymRational {
        skipSpaces()
        val ch = peek()
        if (ch == '+') {
            pos++
            return parseUnary()
        }
        if (ch == '-') {
            pos++
            return parseUnary().negate()
        }
        return parsePower()
    }

    private fun parsePower(): SymRational {
        val base = parsePrimary()
        skipSpaces()
        if (peek() == '^') {
            pos++
            val exponentValue = parseUnary()
            return base.pow(readExponent(exponentValue))
        }
        return base
    }

    private fun readExponent(value: SymRational): Int {
        if (!value.num.isConstant || !value.den.isConstant) throw SymException("指数必须是非负整数")
        val exponent = value.num.constantValue() / value.den.constantValue()
        if (!exponent.isInteger) throw SymException("指数必须是非负整数")
        if (exponent.isNegative) throw SymException("暂不支持负指数，请写成 1/(...)^n")
        if (exponent.num > BigInteger.valueOf(512)) throw SymException("指数太大（最多 512）")
        return exponent.num.toInt()
    }

    private fun parsePrimary(): SymRational {
        skipSpaces()
        if (pos >= src.length) throw SymException("表达式意外结束")
        val ch = src[pos]
        if (ch == '(') {
            pos++
            val value = parseExpression()
            skipSpaces()
            if (peek() != ')') throw SymException("括号不匹配：缺少右括号")
            pos++
            return value
        }
        if (ch.isDigit()) return parseNumber()
        if (ch.isLetter() || ch == '_') return parseIdentifier()
        throw SymException("无法识别的字符「$ch」")
    }

    private fun parseNumber(): SymRational {
        val start = pos
        while (pos < src.length && src[pos].isDigit()) pos++
        var fractionDigits = 0
        if (pos < src.length && src[pos] == '.') {
            pos++
            val digitStart = pos
            while (pos < src.length && src[pos].isDigit()) pos++
            fractionDigits = pos - digitStart
        }
        val text = src.substring(start, pos)
        val digits = text.replace(".", "")
        if (digits.isEmpty()) throw SymException("无效的数字「$text」")
        val numerator = BigInteger(digits)
        val denominator = if (fractionDigits > 0) BigInteger.TEN.pow(fractionDigits) else BigInteger.ONE
        return SymRational.ofConstant(SymFrac.of(numerator, denominator))
    }

    /**
     * 标识符：
     *  - 希腊字母名（alpha/lambda/theta …）映射成对应希腊字符；
     *  - 纯小写字母、长度 2~4：按单字母变量相乘（`xy` → x*y）；
     *  - 其余（含数字、下划线、大写字母、长度 ≥5 的单词）整体当一个变量名。
     */
    private fun parseIdentifier(): SymRational {
        val start = pos
        while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) pos++
        val name = src.substring(start, pos)
        val greek = GREEK_NAMES[name]
        if (greek != null) return SymRational.variable(greek)
        if (name.length in 2..4 && name.all { it in 'a'..'z' }) {
            var product = SymRational.ONE
            for (c in name) product *= SymRational.variable(c.toString())
            return product
        }
        return SymRational.variable(name)
    }

    companion object {
        /** 把「书写形式的变量名」规范成引擎内部的变量名（希腊字母名统一换成希腊字符）。 */
        fun canonicalVariableName(name: String): String = GREEK_NAMES[name] ?: name

        private val GREEK_NAMES: Map<String, String> = mapOf(
            "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
            "epsilon" to "ε", "zeta" to "ζ", "eta" to "η", "theta" to "θ",
            "iota" to "ι", "kappa" to "κ", "lambda" to "λ", "mu" to "μ",
            "nu" to "ν", "xi" to "ξ", "pi" to "π", "rho" to "ρ",
            "sigma" to "σ", "tau" to "τ", "phi" to "φ", "chi" to "χ",
            "psi" to "ψ", "omega" to "ω"
        )
    }
}

// ============================================================================================
// 矩阵
// ============================================================================================

internal class SymMatrix(val rows: Int, val cols: Int, private val cells: List<SymPoly>) {
    init {
        if (cells.size != rows * cols) throw SymException("矩阵内部维度不一致")
    }

    fun at(row: Int, col: Int): SymPoly = cells[row * cols + col]

    fun isNumeric(): Boolean = cells.all { it.isConstant }

    fun numericCells(): Array<Array<SymFrac>>? {
        if (!isNumeric()) return null
        return Array(rows) { r -> Array(cols) { c -> at(r, c).constantValue() } }
    }

    fun minor(dropRow: Int, dropCol: Int): SymMatrix {
        val list = mutableListOf<SymPoly>()
        for (r in 0 until rows) {
            if (r == dropRow) continue
            for (c in 0 until cols) {
                if (c == dropCol) continue
                list.add(at(r, c))
            }
        }
        return SymMatrix(rows - 1, cols - 1, list)
    }

    fun subMatrix(rowIndices: List<Int>, colIndices: List<Int>): SymMatrix {
        val list = mutableListOf<SymPoly>()
        for (r in rowIndices) for (c in colIndices) list.add(at(r, c))
        return SymMatrix(rowIndices.size, colIndices.size, list)
    }

    fun transposed(): SymMatrix {
        val list = mutableListOf<SymPoly>()
        for (c in 0 until cols) for (r in 0 until rows) list.add(at(r, c))
        return SymMatrix(cols, rows, list)
    }
}

/** 解析矩阵：`a,b;c,d` 或 `[[a,b],[c,d]]`。 */
private fun parseMatrix(text: String, label: String): SymMatrix {
    var source = text.trim()
    if (source.isEmpty()) throw SymException("$label 为空")
    if (source.startsWith("[")) {
        source = source.replace(" ", "").replace("\t", "")
        while (source.startsWith("[")) source = source.substring(1)
        while (source.endsWith("]")) source = source.substring(0, source.length - 1)
        source = source.replace("],[", ";")
    }
    val rowTexts = source.split(';').map { it.trim() }.filter { it.isNotEmpty() }
    if (rowTexts.isEmpty()) throw SymException("$label 为空")
    val rows = rowTexts.map { splitRow(it) }
    val cols = rows.first().size
    if (cols == 0) throw SymException("$label 为空")
    if (rows.any { it.size != cols }) throw SymException("$label 每行的元素个数必须相同，请检查分隔符")
    if (rows.size > 20 || cols > 20) throw SymException("$label 太大（最多 20×20）")
    val cells = mutableListOf<SymPoly>()
    for ((r, row) in rows.withIndex()) {
        for ((c, cell) in row.withIndex()) {
            if (cell.isBlank()) throw SymException("$label 第 ${r + 1} 行第 ${c + 1} 列缺少元素")
            cells.add(parseCell(cell, label, r, c))
        }
    }
    return SymMatrix(rows.size, cols, cells)
}

private fun splitRow(row: String): List<String> {
    val byComma = row.split(',')
    if (byComma.size > 1) return byComma.map { it.trim() }
    return row.split(' ', '\t').filter { it.isNotBlank() }.map { it.trim() }
}

private fun parseCell(text: String, label: String, row: Int, col: Int): SymPoly {
    val rational = try {
        SymParser(text).parse()
    } catch (e: SymException) {
        throw SymException("$label 第 ${row + 1} 行第 ${col + 1} 列的元素「$text」无法解析：${e.message}")
    }
    if (!rational.den.isConstant) {
        throw SymException("$label 第 ${row + 1} 行第 ${col + 1} 列的元素「$text」含有变量分母，暂不支持")
    }
    return rational.num.scale(rational.den.constantValue().reciprocal())
}

// ============================================================================================
// 通用小工具
// ============================================================================================

private fun lcmBig(a: BigInteger, b: BigInteger): BigInteger {
    if (a.signum() == 0 || b.signum() == 0) return BigInteger.ZERO
    return (a / a.gcd(b)).multiply(b).abs()
}

private fun popCount(value: Int): Int {
    var v = value
    var count = 0
    while (v != 0) {
        count += v and 1
        v = v shr 1
    }
    return count
}

/** 整数平方根；不是完全平方返回 null。 */
private fun integerSqrt(value: BigInteger): BigInteger? = integerRoot(value, 2)

/** 整数 k 次根；不是完全 k 次方返回 null。 */
private fun integerRoot(value: BigInteger, k: Int): BigInteger? {
    if (value.signum() < 0) return null
    if (value.signum() == 0) return BigInteger.ZERO
    if (value == BigInteger.ONE) return BigInteger.ONE
    var low = BigInteger.ZERO
    var high = BigInteger.ONE.shiftLeft(value.bitLength() / k + 1)
    while (low <= high) {
        val mid = low.add(high).shiftRight(1)
        val cmp = mid.pow(k).compareTo(value)
        if (cmp == 0) return mid
        if (cmp < 0) low = mid.add(BigInteger.ONE) else high = mid.subtract(BigInteger.ONE)
    }
    return null
}

private fun rationalSqrt(value: SymFrac): SymFrac? {
    if (value.isNegative) return null
    val a = integerSqrt(value.num) ?: return null
    val b = integerSqrt(value.den) ?: return null
    return SymFrac.of(a, b)
}

private fun rationalCubeRoot(value: SymFrac): SymFrac? {
    val negative = value.isNegative
    val a = integerRoot(value.num.abs(), 3) ?: return null
    val b = integerRoot(value.den, 3) ?: return null
    val result = SymFrac.of(a, b)
    return if (negative) result.negate() else result
}

/** 有理数四次方根；不是完全四次方返回 null。 */
private fun rationalFourthRoot(value: SymFrac): SymFrac? {
    if (value.isNegative) return null
    val a = integerRoot(value.num, 4) ?: return null
    val b = integerRoot(value.den, 4) ?: return null
    return SymFrac.of(a, b)
}

/** 单变量多项式的系数（升幂），调用方保证 p 只含 [variable]。 */
private fun univariateCoeffs(p: SymPoly, variable: String): MutableList<SymFrac> {
    val degree = p.degreeIn(variable)
    val result = MutableList(degree + 1) { SymFrac.ZERO }
    for ((m, c) in p.terms) result[m.degrees(variable)] = c
    return result
}

private fun polyFromUnivariate(coeffs: List<SymFrac>, variable: String): SymPoly {
    var result = SymPoly.ZERO
    for (i in coeffs.indices) {
        val c = coeffs[i]
        if (!c.isZero) result += SymPoly.monomial(SymMonomial.of(variable, i), c)
    }
    return result
}

/** 单变量多项式带余除法：返回 (商, 余式)。 */
private fun divideUnivariate(numerator: SymPoly, denominator: SymPoly, variable: String): Pair<SymPoly, SymPoly> {
    val a = univariateCoeffs(numerator, variable)
    val b = univariateCoeffs(denominator, variable)
    while (a.isNotEmpty() && a.last().isZero) a.removeAt(a.size - 1)
    while (b.isNotEmpty() && b.last().isZero) b.removeAt(b.size - 1)
    if (b.isEmpty()) throw SymException("除式为零")
    val q = MutableList((a.size - b.size + 1).coerceAtLeast(1)) { SymFrac.ZERO }
    while (a.size >= b.size && a.isNotEmpty()) {
        val shift = a.size - b.size
        val factor = a.last() / b.last()
        q[shift] = factor
        for (i in b.indices) a[i + shift] = a[i + shift] - factor * b[i]
        while (a.isNotEmpty() && a.last().isZero) a.removeAt(a.size - 1)
    }
    return Pair(polyFromUnivariate(q, variable), polyFromUnivariate(a, variable))
}

/** 单变量多项式最大公因式（欧几里得算法），结果首项系数归一为 1。 */
private fun univariateGcd(a: SymPoly, b: SymPoly, variable: String): SymPoly {
    var x = a
    var y = b
    var guard = 0
    while (!y.isZero && guard < 64) {
        guard++
        val remainder = divideUnivariate(x, y, variable).second
        x = y
        y = remainder
    }
    if (x.isZero) return SymPoly.ZERO
    val lead = x.leadingCoefficient()
    return if (lead.isZero) x else x.scale(lead.reciprocal())
}

private fun evaluateUnivariate(coeffs: List<SymFrac>, value: SymFrac): SymFrac {
    var result = SymFrac.ZERO
    for (i in coeffs.indices.reversed()) result = result * value + coeffs[i]
    return result
}

private fun divisorsUpTo(value: BigInteger, limit: Int): List<BigInteger> {
    val result = mutableListOf<BigInteger>()
    var i = 1
    while (i <= limit) {
        val candidate = BigInteger.valueOf(i.toLong())
        if (value.mod(candidate).signum() == 0) result.add(candidate)
        i++
    }
    if (result.isEmpty()) result.add(BigInteger.ONE)
    return result
}

// ============================================================================================
// 行列式 / 秩 / 逆 / 行最简形 / 解方程组（数值）
// ============================================================================================

/** 数值行列式：带主元交换的分数高斯消元，det = 主元乘积 × 交换符号。 */
private fun detNumeric(matrix: Array<Array<SymFrac>>): SymFrac {
    val n = matrix.size
    val m = Array(n) { r -> Array(n) { c -> matrix[r][c] } }
    var det = SymFrac.ONE
    for (k in 0 until n) {
        var pivot = -1
        for (i in k until n) {
            if (!m[i][k].isZero) {
                pivot = i
                break
            }
        }
        if (pivot < 0) return SymFrac.ZERO
        if (pivot != k) {
            val swap = m[pivot]
            m[pivot] = m[k]
            m[k] = swap
            det = det.negate()
        }
        val p = m[k][k]
        det *= p
        for (i in k + 1 until n) {
            if (m[i][k].isZero) continue
            val factor = m[i][k] / p
            for (j in k until n) m[i][j] = m[i][j] - factor * m[k][j]
        }
    }
    return det
}

/** 数值矩阵的秩（行阶梯形非零行个数）。 */
private fun rankNumeric(matrix: Array<Array<SymFrac>>): Int {
    val rows = matrix.size
    val cols = matrix[0].size
    val m = Array(rows) { r -> Array(cols) { c -> matrix[r][c] } }
    var rank = 0
    var r = 0
    for (c in 0 until cols) {
        var pivot = -1
        for (i in r until rows) {
            if (!m[i][c].isZero) {
                pivot = i
                break
            }
        }
        if (pivot < 0) continue
        if (pivot != r) {
            val swap = m[pivot]
            m[pivot] = m[r]
            m[r] = swap
        }
        val p = m[r][c]
        for (j in 0 until cols) m[r][j] = m[r][j] / p
        for (i in r + 1 until rows) {
            if (m[i][c].isZero) continue
            val factor = m[i][c]
            for (j in 0 until cols) m[i][j] = m[i][j] - factor * m[r][j]
        }
        rank++
        r++
        if (r == rows) break
    }
    return rank
}

/** 数值矩阵求逆（Gauss-Jordan）；不可逆返回 null。 */
private fun invNumeric(matrix: Array<Array<SymFrac>>): Array<Array<SymFrac>>? {
    val n = matrix.size
    val m = Array(n) { r ->
        Array(2 * n) { c ->
            when {
                c < n -> matrix[r][c]
                c - n == r -> SymFrac.ONE
                else -> SymFrac.ZERO
            }
        }
    }
    for (c in 0 until n) {
        var pivot = -1
        for (i in c until n) {
            if (!m[i][c].isZero) {
                pivot = i
                break
            }
        }
        if (pivot < 0) return null
        if (pivot != c) {
            val swap = m[pivot]
            m[pivot] = m[c]
            m[c] = swap
        }
        val p = m[c][c]
        if (!p.isOne) for (j in 0 until 2 * n) m[c][j] = m[c][j] / p
        for (i in 0 until n) {
            if (i == c || m[i][c].isZero) continue
            val factor = m[i][c]
            for (j in 0 until 2 * n) m[i][j] = m[i][j] - factor * m[c][j]
        }
    }
    return Array(n) { r -> Array(n) { c -> m[r][n + c] } }
}

/** 数值矩阵行最简形，返回 (主元列列表, 主元行列表)。 */
private fun rrefNumeric(matrix: Array<Array<SymFrac>>): Pair<List<Int>, List<Int>> {
    val rows = matrix.size
    val cols = matrix[0].size
    val pivotCols = mutableListOf<Int>()
    val pivotRows = mutableListOf<Int>()
    var r = 0
    for (c in 0 until cols) {
        var pivot = -1
        for (i in r until rows) {
            if (!matrix[i][c].isZero) {
                pivot = i
                break
            }
        }
        if (pivot < 0) continue
        if (pivot != r) {
            val swap = matrix[pivot]
            matrix[pivot] = matrix[r]
            matrix[r] = swap
        }
        val p = matrix[r][c]
        if (!p.isOne) for (j in 0 until cols) matrix[r][j] = matrix[r][j] / p
        for (i in 0 until rows) {
            if (i == r || matrix[i][c].isZero) continue
            val factor = matrix[i][c]
            for (j in 0 until cols) matrix[i][j] = matrix[i][j] - factor * matrix[r][j]
        }
        pivotCols.add(c)
        pivotRows.add(r)
        r++
        if (r == rows) break
    }
    return Pair(pivotCols, pivotRows)
}

/** 数值方程组求解：返回每个未知数的多项式表达式（自由变量用 t1,t2… 表示）。 */
private fun solveNumeric(
    coefficients: Array<Array<SymFrac>>,
    rightSide: Array<SymFrac>
): Pair<List<SymPoly>, List<String>> {
    val rows = coefficients.size
    val cols = coefficients[0].size
    val aug = Array(rows) { r -> Array(cols + 1) { c -> if (c < cols) coefficients[r][c] else rightSide[r] } }
    val pivotCols = mutableListOf<Int>()
    val pivotRows = mutableListOf<Int>()
    var r = 0
    for (c in 0 until cols) {
        var pivot = -1
        for (i in r until rows) {
            if (!aug[i][c].isZero) {
                pivot = i
                break
            }
        }
        if (pivot < 0) continue
        if (pivot != r) {
            val swap = aug[pivot]
            aug[pivot] = aug[r]
            aug[r] = swap
        }
        val p = aug[r][c]
        if (!p.isOne) for (j in 0 until cols + 1) aug[r][j] = aug[r][j] / p
        for (i in 0 until rows) {
            if (i == r || aug[i][c].isZero) continue
            val factor = aug[i][c]
            for (j in 0 until cols + 1) aug[i][j] = aug[i][j] - factor * aug[r][j]
        }
        pivotCols.add(c)
        pivotRows.add(r)
        r++
        if (r == rows) break
    }
    // 无解：某行系数全 0 但常数项非 0
    for (i in 0 until rows) {
        var allZero = true
        for (c in 0 until cols) if (!aug[i][c].isZero) {
            allZero = false
            break
        }
        if (allZero && !aug[i][cols].isZero) throw SymException("方程组无解")
    }
    val freeCols = (0 until cols).filter { !pivotCols.contains(it) }
    val paramNames = freeCols.indices.map { "t${it + 1}" }
    val expressions = mutableListOf<SymPoly>()
    for (c in 0 until cols) {
        val pivotIndex = pivotCols.indexOf(c)
        if (pivotIndex >= 0) {
            val row = pivotRows[pivotIndex]
            var expression = SymPoly.constant(aug[row][cols])
            for ((index, freeCol) in freeCols.withIndex()) {
                val coefficient = aug[row][freeCol]
                if (!coefficient.isZero) {
                    expression -= SymPoly.variable(paramNames[index]).scale(coefficient)
                }
            }
            expressions.add(expression)
        } else {
            val index = freeCols.indexOf(c)
            expressions.add(SymPoly.variable(paramNames[index]))
        }
    }
    return Pair(expressions, paramNames)
}

// ============================================================================================
// 行列式 / 秩（符号）
// ============================================================================================

/**
 * 符号行列式：Laplace 按行展开 + 记忆化。
 * 状态为「已用列集合 mask」，当前展开行为 mask 中 1 的个数，复杂度 O(n·2^n)。
 */
private fun detSymbolic(matrix: SymMatrix): SymPoly {
    val n = matrix.rows
    if (n != matrix.cols) throw SymException("行列式要求方阵")
    if (n == 0) return SymPoly.ONE
    if (n == 1) return matrix.at(0, 0)
    if (n == 2) return matrix.at(0, 0) * matrix.at(1, 1) - matrix.at(0, 1) * matrix.at(1, 0)
    val memo = HashMap<Int, SymPoly>()

    fun expand(mask: Int): SymPoly {
        val row = popCount(mask)
        if (row == n) return SymPoly.ONE
        val cached = memo[mask]
        if (cached != null) return cached
        var acc = SymPoly.ZERO
        var sign = 1
        for (col in 0 until n) {
            if (mask and (1 shl col) != 0) continue
            val entry = matrix.at(row, col)
            if (entry.isZero) {
                sign = -sign
                continue
            }
            val term = entry * expand(mask or (1 shl col))
            acc = if (sign > 0) acc + term else acc - term
            sign = -sign
        }
        memo[mask] = acc
        return acc
    }

    return expand(0)
}

private fun cofactorSymbolic(matrix: SymMatrix, row: Int, col: Int): SymPoly {
    val minor = matrix.minor(row, col)
    val det = if (minor.rows == 0) SymPoly.ONE else detSymbolic(minor)
    return if ((row + col) % 2 == 0) det else det.negate()
}

/** 符号矩阵求秩：从高阶往低阶枚举子式，找第一个非零行列式。 */
private fun symbolicRank(matrix: SymMatrix): Int {
    val maximum = minOf(matrix.rows, matrix.cols)
    for (size in maximum downTo 1) {
        val rowSets = combinations(matrix.rows, size)
        val colSets = combinations(matrix.cols, size)
        for (rows in rowSets) {
            for (cols in colSets) {
                if (!detSymbolic(matrix.subMatrix(rows, cols)).isZero) return size
            }
        }
    }
    return 0
}

private fun combinations(total: Int, choose: Int): List<List<Int>> {
    val result = mutableListOf<List<Int>>()
    val current = mutableListOf<Int>()

    fun walk(start: Int) {
        if (current.size == choose) {
            result.add(current.toList())
            return
        }
        for (i in start until total) {
            current.add(i)
            walk(i + 1)
            current.removeAt(current.size - 1)
        }
    }

    walk(0)
    return result
}

// ============================================================================================
// 因式分解
// ============================================================================================

internal class FactorResult(
    val constant: SymFrac,
    val factors: List<SymPoly>,
    val complete: Boolean
)

/**
 * 因式分解入口：数值公因数 + 公单项式 + 单变量有理根 + 常见恒等式。
 * 说明：多变量只做「提公因式」和平方差/立方和差/完全平方，不做一般多变量分解。
 */
private fun factorPoly(poly: SymPoly): FactorResult {
    if (poly.isZero) return FactorResult(SymFrac.ZERO, emptyList(), true)
    return factorRecursive(poly, 0)
}

private fun factorRecursive(poly: SymPoly, depth: Int): FactorResult {
    if (poly.isConstant) return FactorResult(poly.constantValue(), emptyList(), true)
    if (depth > 6) return FactorResult(SymFrac.ONE, listOf(poly), false)
    val contentAndPrimitive = numericContent(poly)
    var content = contentAndPrimitive.first
    var rest = contentAndPrimitive.second
    val factors = mutableListOf<SymPoly>()
    var complete = true
    val common = rest.commonMonomial()
    if (!common.isOne) {
        factors.add(SymPoly.monomial(common, SymFrac.ONE))
        rest = rest.divideByMonomial(common)
    }
    if (!rest.isConstant) {
        // 先统一尝试常见恒等式（平方差 / 立方和差 / 完全平方 / 索菲·热尔曼）：
        // 恒等式给出的都是真因子，因此递归一定能终止；单变量多项式也要先走这里，
        // 否则 x^4 + 4 这类没有有理根的四次式永远进不了恒等式分支。
        val identity = tryIdentityFactors(rest)
        if (identity != null) {
            for (part in identity) {
                val sub = factorRecursive(part, depth + 1)
                content *= sub.constant
                factors.addAll(sub.factors)
                complete = complete && sub.complete
            }
        } else {
            val vars = rest.variables()
            if (vars.size == 1) {
                val uni = factorUnivariate(rest, vars[0])
                factors.addAll(uni.factors)
                complete = complete && uni.complete
            } else {
                factors.add(rest)
                // 低次（总次数 ≤2 且项数 ≤3）的多变量多项式在有理数上一般已不可约，
                // 不再附「未能完全分解」，避免对 a^2-a*b+b^2 这类因子误报提示。
                val totalDegree = rest.terms.keys.maxOf { it.totalDegree }
                if (totalDegree > 2 || rest.termCount > 3) complete = false
            }
        }
    }
    return FactorResult(content, factors, complete)
}

/** 返回 (数值公因数, 本原多项式)。本原多项式的系数为整数且首项系数为正。 */
private fun numericContent(poly: SymPoly): Pair<SymFrac, SymPoly> {
    val l = poly.denominatorLcm()
    val scaled = poly.scale(SymFrac.of(l))
    var g = BigInteger.ZERO
    for (c in scaled.terms.values) g = g.gcd(c.num.abs())
    if (g.signum() == 0) return Pair(SymFrac.ZERO, SymPoly.ZERO)
    val primitive = scaled.scale(SymFrac.of(BigInteger.ONE, g))
    return if (primitive.leadingCoefficient().isNegative) {
        Pair(SymFrac.of(g.negate(), l), primitive.negate())
    } else {
        Pair(SymFrac.of(g, l), primitive)
    }
}

private class UnivariateFactorisation(val factors: List<SymPoly>, val complete: Boolean)

/**
 * 单变量因式分解：反复用有理根定理找一次因式并降次。
 * 有理根定理：既约分数 p/q 若是整系数多项式的根，则 p 整除常数项、q 整除首项系数。
 * 次数 ≤3 且无有理根 ⇒ 在有理数域上不可约，视为分解完成。
 */
private fun factorUnivariate(poly: SymPoly, variable: String): UnivariateFactorisation {
    var current = poly
    val factors = mutableListOf<SymPoly>()
    var guard = 0
    while (current.degreeIn(variable) >= 1 && guard < 64) {
        guard++
        val root = findRationalRoot(current, variable) ?: break
        // 用整系数一次因式 (q*x - p) 而不是 (x - p/q)：除以它之后商自动多出 1/q，
        // 等式不变，但输出不会出现 (x - 3/2) 这种分数形式。
        val linear = SymPoly.variable(variable).scale(SymFrac.of(root.den)) -
            SymPoly.constant(SymFrac.of(root.num))
        val division = divideUnivariate(current, linear, variable)
        if (!division.second.isZero) break
        factors.add(linear)
        current = division.first
        if (current.isConstant) break
    }
    if (current.isConstant) {
        if (factors.isEmpty()) factors.add(poly)
        return UnivariateFactorisation(factors, true)
    }
    // 四次且无有理根：试拆成两个二次因式，这是「在有理数上不可约」的完整判别
    // （四次式的分解只有 1+3 与 2+2 两种形状，1+3 等价于存在有理根）。
    if (current.degreeIn(variable) == 4) {
        val split = tryQuarticSplit(current, variable)
        if (split != null) {
            var complete = true
            for (part in split) {
                val sub = factorUnivariate(part, variable)
                factors.addAll(sub.factors)
                complete = complete && sub.complete
            }
            return UnivariateFactorisation(factors, complete)
        }
        factors.add(current)
        return UnivariateFactorisation(factors, true)
    }
    factors.add(current)
    val complete = current.degreeIn(variable) <= 3
    return UnivariateFactorisation(factors, complete)
}

/**
 * 四次多项式试拆成两个有理系数二次因式。
 * 首项系数归一后 p = x^4 + A x^3 + B x^2 + C x + D；若 p = (x^2 + a x + b)(x^2 + c x + d)，则
 * a + c = A、b + d + a c = B、a d + b c = C、b d = D。枚举整数对 (b, d) 后解二次方程即可得 a、c。
 * 返回 null 表示在有理数域上不可约（或常数项不是整数、放弃判定）。
 */
private fun tryQuarticSplit(poly: SymPoly, variable: String): List<SymPoly>? {
    val coeffs = univariateCoeffs(poly, variable)
    if (coeffs.size != 5) return null
    val leading = coeffs[4]
    if (leading.isZero) return null
    val monic = coeffs.map { it / leading }
    if (!monic[0].isInteger) return null
    val constant = monic[0].num
    if (constant.signum() == 0) return null
    val a3 = monic[3]
    val a2 = monic[2]
    val a1 = monic[1]
    val two = SymFrac.ofInt(2)
    val four = SymFrac.ofInt(4)
    for (divisor in divisorsUpTo(constant.abs(), 2000)) {
        for (candidate in listOf(divisor, divisor.negate())) {
            if (constant.rem(candidate).signum() != 0) continue
            val d = constant.divide(candidate)
            val bf = SymFrac.of(candidate)
            val df = SymFrac.of(d)
            val ac = a2 - bf - df
            val disc = a3 * a3 - ac * four
            val s = rationalSqrt(disc) ?: continue
            for (a in listOf((a3 + s) / two, (a3 - s) / two)) {
                val c = a3 - a
                if (a * df + bf * c != a1) continue
                val square = SymPoly.variable(variable).pow(2)
                val first = square + SymPoly.variable(variable).scale(a) + SymPoly.constant(bf)
                val second = square + SymPoly.variable(variable).scale(c) + SymPoly.constant(df)
                if (first * second == poly) return listOf(first, second)
            }
        }
    }
    return null
}

private fun findRationalRoot(poly: SymPoly, variable: String): SymFrac? {
    val coeffs = univariateCoeffs(poly, variable)
    if (coeffs.size < 2) return null
    var l = BigInteger.ONE
    for (c in coeffs) l = lcmBig(l, c.den)
    val ints = coeffs.map { c -> c.num.multiply(l.divide(c.den)) }
    val constant = ints.first()
    val leading = ints.last()
    if (constant.signum() == 0) return SymFrac.ZERO
    val pDivs = divisorsUpTo(constant.abs(), 60)
    val qDivs = divisorsUpTo(leading.abs(), 60)
    for (p in pDivs) {
        for (q in qDivs) {
            val positive = SymFrac.of(p, q)
            if (evaluateUnivariate(coeffs, positive).isZero) return positive
            val negative = positive.negate()
            if (evaluateUnivariate(coeffs, negative).isZero) return negative
        }
    }
    return null
}

/** 常见恒等式尝试：平方差、立方和/差、完全平方。 */
private fun tryIdentityFactors(poly: SymPoly): List<SymPoly>? {
    if (poly.termCount == 2) {
        val entries = poly.terms.entries.toList()
        val first = entries[0]
        val second = entries[1]
        // 平方差 A^2 - B^2
        if (first.value.isNegative != second.value.isNegative) {
            val positive = if (first.value.isNegative) second else first
            val negative = if (first.value.isNegative) first else second
            val a = sqrtTerm(positive.key, positive.value.abs())
            val b = sqrtTerm(negative.key, negative.value.abs())
            if (a != null && b != null) return listOf(a - b, a + b)
        }
        // 索菲·热尔曼恒等式：A^4 + 4*B^4 = (A^2 - 2AB + 2B^2)(A^2 + 2AB + 2B^2)
        if (!first.value.isNegative && !second.value.isNegative) {
            val a = fourthRootTerm(first.key, first.value)
            val b = fourthRootTerm(second.key, second.value / SymFrac.ofInt(4))
            if (a != null && b != null) {
                val twoAB = (a * b).scale(SymFrac.ofInt(2))
                val aSquared = a * a
                val twoBSquared = (b * b).scale(SymFrac.ofInt(2))
                val left = aSquared - twoAB + twoBSquared
                val right = aSquared + twoAB + twoBSquared
                if (left * right == poly) return listOf(left, right)
            }
        }
        // 立方和/差 A^3 ± B^3 = (A ± B)(A^2 ∓ AB + B^2)
        val a = cubeTerm(first.key, first.value)
        val b = cubeTerm(second.key, second.value)
        if (a != null && b != null) {
            return listOf(a + b, a * a - a * b + b * b)
        }
    }
    if (poly.termCount == 3) {
        val entries = poly.terms.entries.toList()
        for (i in entries.indices) {
            for (j in entries.indices) {
                if (i == j) continue
                val a = sqrtTerm(entries[i].key, entries[i].value) ?: continue
                val b = sqrtTerm(entries[j].key, entries[j].value) ?: continue
                val squareSum = a * a + (a * b).scale(SymFrac.ofInt(2)) + b * b
                if (squareSum == poly) return listOf(a + b, a + b)
                val squareDiff = a * a - (a * b).scale(SymFrac.ofInt(2)) + b * b
                if (squareDiff == poly) return listOf(a - b, a - b)
            }
        }
    }
    return null
}

/** 单项式的平方根（系数必须为正的完全平方、指数全为偶数）。 */
private fun sqrtTerm(monomial: SymMonomial, coefficient: SymFrac): SymPoly? {
    if (coefficient.isNegative || coefficient.isZero) return null
    val root = rationalSqrt(coefficient) ?: return null
    val half = LinkedHashMap<String, Int>()
    for (p in monomial.powers) {
        if (p.second % 2 != 0) return null
        half[p.first] = p.second / 2
    }
    return SymPoly.monomial(SymMonomial.create(half), root)
}

/** 单项式的四次方根（系数必须为正的完全四次方、指数全为 4 的倍数）。 */
private fun fourthRootTerm(monomial: SymMonomial, coefficient: SymFrac): SymPoly? {
    if (coefficient.isNegative || coefficient.isZero) return null
    val root = rationalFourthRoot(coefficient) ?: return null
    val quarter = LinkedHashMap<String, Int>()
    for (p in monomial.powers) {
        if (p.second % 4 != 0) return null
        quarter[p.first] = p.second / 4
    }
    return SymPoly.monomial(SymMonomial.create(quarter), root)
}

/** 单项式的立方根（符号可以由系数吸收，指数必须全为 3 的倍数）。 */
private fun cubeTerm(monomial: SymMonomial, coefficient: SymFrac): SymPoly? {
    if (coefficient.isZero) return null
    val root = rationalCubeRoot(coefficient) ?: return null
    val third = LinkedHashMap<String, Int>()
    for (p in monomial.powers) {
        if (p.second % 3 != 0) return null
        third[p.first] = p.second / 3
    }
    return SymPoly.monomial(SymMonomial.create(third), root)
}

/** 因式分解结果的纯文本形式，如 `2*(x - 1)*(x + 1)`，重复因子合并成幂。 */
private fun formatFactoredPlain(result: FactorResult): String {
    if (result.factors.isEmpty()) return result.constant.toPlain()
    // 只有一个因子且无额外系数，说明没能分解，直接给展开形式（避免多加一层括号）。
    if (result.factors.size == 1 && result.constant.isOne) return formatPolyPlain(result.factors[0])
    val counts = LinkedHashMap<SymPoly, Int>()
    for (factor in result.factors) counts[factor] = (counts[factor] ?: 0) + 1
    val parts = counts.map { (factor, count) ->
        val text = formatPolyPlain(factor)
        val base = if (factor.termCount > 1) "($text)" else text
        if (count > 1) "$base^$count" else base
    }
    val body = parts.joinToString("*")
    val constant = result.constant
    return when {
        constant.isOne -> body
        constant == SymFrac.MINUS_ONE -> "-$body"
        constant.isInteger -> "${constant.toPlain()}*$body"
        else -> "(${constant.toPlain()})*$body"
    }
}

private fun formatFactoredLatex(result: FactorResult): String {
    if (result.factors.isEmpty()) return latexNumber(result.constant)
    if (result.factors.size == 1 && result.constant.isOne) return formatPolyLatex(result.factors[0])
    val counts = LinkedHashMap<SymPoly, Int>()
    for (factor in result.factors) counts[factor] = (counts[factor] ?: 0) + 1
    val parts = counts.map { (factor, count) ->
        val text = formatPolyLatex(factor)
        val base = if (factor.termCount > 1) "\\left($text\\right)" else text
        if (count > 1) "$base^{$count}" else base
    }
    val body = parts.joinToString("")
    val constant = result.constant
    return when {
        constant.isOne -> body
        constant == SymFrac.MINUS_ONE -> "-$body"
        else -> latexNumber(constant) + body
    }
}

// ============================================================================================
// 文本归一化与命令拆分
// ============================================================================================

/** 全角/中文符号归一化。 */
private fun normalizeSymbols(source: String): String {
    val sb = StringBuilder(source.length)
    for (ch in source) {
        val mapped = when (ch) {
            '×', '·', '⋅', '∗', '＊' -> '*'
            '÷', '／' -> '/'
            '−', '–', '—', '－', '‒' -> '-'
            '（' -> '('
            '）' -> ')'
            '，' -> ','
            '；' -> ';'
            '：' -> ':'
            '［' -> '['
            '］' -> ']'
            '＋' -> '+'
            '＝' -> '='
            '｜' -> '|'
            '　' -> ' '
            in '０'..'９' -> '0' + (ch - '０')
            else -> ch
        }
        sb.append(mapped)
    }
    return sb.toString()
}

/** 按分隔符切分，忽略括号/方括号内部的同名字符。 */
private fun splitTopLevel(text: String, separator: Char): List<String> {
    val result = mutableListOf<String>()
    val sb = StringBuilder()
    var depth = 0
    for (ch in text) {
        when (ch) {
            '(', '[', '{' -> {
                depth++
                sb.append(ch)
            }
            ')', ']', '}' -> {
                depth--
                sb.append(ch)
            }
            separator -> {
                if (depth == 0) {
                    result.add(sb.toString())
                    sb.setLength(0)
                } else {
                    sb.append(ch)
                }
            }
            else -> sb.append(ch)
        }
    }
    result.add(sb.toString())
    return result.map { it.trim() }
}

/** 去掉 `A=` 之类的前缀。 */
private fun stripLabel(text: String): String {
    val index = text.indexOf('=')
    return if (index >= 0) text.substring(index + 1).trim() else text.trim()
}

// ============================================================================================
// 对外 API
// ============================================================================================

/** 单条命令的执行结果（还没带上原始命令行）。 */
private data class Outcome(
    val title: String,
    val plain: String,
    val latex: String = "",
    val extra: List<String> = emptyList()
)

object LinearAlgebraTool {

    private val SUPPORTED = listOf(
        "det", "rank", "inv", "rref", "charpoly", "trace",
        "transpose", "solve", "mul", "factor", "expand", "simplify", "subst"
    )

    /** 解析整段命令块并逐条执行；空行与 `#` 注释行被忽略。 */
    fun runBlock(source: String): List<MathResult> {
        val results = mutableListOf<MathResult>()
        val normalized = normalizeSymbols(source)
        for (rawLine in normalized.split('\n')) {
            // 去掉行尾 `#` 注释：`#` 不是合法的数学字符，出现即视为注释起点。
            val hash = rawLine.indexOf('#')
            val line = (if (hash >= 0) rawLine.substring(0, hash) else rawLine).trim()
            if (line.isEmpty()) continue
            results.add(runLine(line))
        }
        return results
    }

    private fun runLine(line: String): MathResult {
        val colon = line.indexOf(':')
        if (colon < 0) {
            return MathResult(line, "命令", "", error = "命令格式应为「命令: 参数」，例如 det: 1,2;3,4")
        }
        val name = line.substring(0, colon).trim()
        val parameter = line.substring(colon + 1).trim()
        val key = name.lowercase()
        if (key !in SUPPORTED) {
            val supported = SUPPORTED.joinToString("/")
            return MathResult(line, "未知命令", "", error = "不支持的命令: $name（支持 $supported）")
        }
        if (parameter.isEmpty()) {
            return MathResult(line, titleOf(key), "", error = "命令 $key 缺少参数")
        }
        return try {
            val outcome = execute(key, parameter)
            MathResult(line, outcome.title, outcome.plain, outcome.latex, outcome.extra)
        } catch (e: SymException) {
            MathResult(line, titleOf(key), "", error = e.message ?: "计算失败")
        } catch (e: Exception) {
            MathResult(line, titleOf(key), "", error = "计算失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun titleOf(key: String): String = when (key) {
        "det" -> "行列式"
        "rank" -> "秩"
        "inv" -> "逆矩阵"
        "rref" -> "行最简形"
        "charpoly" -> "特征多项式"
        "trace" -> "迹"
        "transpose" -> "转置"
        "solve" -> "解方程组"
        "mul" -> "矩阵乘法"
        "factor" -> "因式分解"
        "expand" -> "展开"
        "simplify" -> "化简"
        "subst" -> "代入求值"
        else -> "计算"
    }

    private fun execute(key: String, parameter: String): Outcome = when (key) {
        "det" -> commandDet(parameter)
        "rank" -> commandRank(parameter)
        "inv" -> commandInv(parameter)
        "rref" -> commandRref(parameter)
        "charpoly" -> commandCharpoly(parameter)
        "trace" -> commandTrace(parameter)
        "transpose" -> commandTranspose(parameter)
        "solve" -> commandSolve(parameter)
        "mul" -> commandMul(parameter)
        "factor" -> commandFactor(parameter)
        "expand" -> commandExpand(parameter)
        "simplify" -> commandSimplify(parameter)
        else -> commandSubst(parameter)
    }

    // ------------------------------------------------------------------ det

    private fun commandDet(parameter: String): Outcome {
        val matrix = parseMatrix(parameter, "矩阵")
        if (matrix.rows != matrix.cols) {
            throw SymException("行列式要求方阵，当前矩阵为 ${matrix.rows}×${matrix.cols}")
        }
        val n = matrix.rows
        if (n == 0) throw SymException("矩阵为空")
        val extras = mutableListOf<String>()
        extras.add("${n}×${n} 矩阵")
        val numeric = matrix.numericCells()
        val determinant: SymPoly = if (numeric != null) {
            if (n > 20) throw SymException("数值行列式最多支持 20 阶，当前为 $n 阶")
            SymPoly.constant(detNumeric(numeric))
        } else {
            if (n > 6) throw SymException("符号行列式最多支持 6 阶，请先代入数值")
            detSymbolic(matrix)
        }
        val plain = formatPolyPlain(determinant)
        val factored = factorPoly(determinant)
        val factoredText = formatFactoredPlain(factored)
        // 只有因式形式确实更短/更清晰时才附上，避免高阶符号行列式给出两倍长度的冗余信息。
        val showFactorization = !determinant.isConstant && !determinant.isZero &&
            factoredText != plain && factored.factors.size >= 2
        if (showFactorization) {
            extras.add("因式分解: $factoredText")
            if (!factored.complete) extras.add("（在有理数范围内未能完全分解）")
        }
        return Outcome("行列式", plain, formatPolyLatex(determinant), extras)
    }

    // ------------------------------------------------------------------ rank

    private fun commandRank(parameter: String): Outcome {
        val matrix = parseMatrix(parameter, "矩阵")
        val extras = mutableListOf("${matrix.rows}×${matrix.cols} 矩阵")
        val numeric = matrix.numericCells()
        val rank = if (numeric != null) {
            rankNumeric(numeric)
        } else {
            if (minOf(matrix.rows, matrix.cols) > 4) {
                throw SymException("符号矩阵求秩最多支持 4 阶，请先代入数值")
            }
            symbolicRank(matrix)
        }
        extras.add(if (rank == minOf(matrix.rows, matrix.cols)) "满秩" else "不满秩")
        if (numeric == null) extras.add("按参数一般取值计算（某些参数取值下秩会变小）")
        return Outcome("秩", rank.toString(), "", extras)
    }

    // ------------------------------------------------------------------ inv

    private fun commandInv(parameter: String): Outcome {
        val matrix = parseMatrix(parameter, "矩阵")
        if (matrix.rows != matrix.cols) throw SymException("逆矩阵要求方阵，当前矩阵为 ${matrix.rows}×${matrix.cols}")
        val n = matrix.rows
        val numeric = matrix.numericCells()
        if (numeric != null) {
            val inverse = invNumeric(numeric)
                ?: throw SymException("矩阵不可逆（行列式为 0）")
            val plainRows = inverse.map { row -> row.map { it.toPlain() } }
            val latexRows = inverse.map { row -> row.map { latexNumber(it) } }
            return Outcome(
                "逆矩阵",
                matrixPlain(plainRows),
                matrixLatex(latexRows),
                listOf("${n}×${n} 矩阵", "行列式: ${detNumeric(numeric).toPlain()}")
            )
        }
        if (n > 4) throw SymException("符号矩阵求逆最多支持 4 阶，请先代入数值")
        val determinant = detSymbolic(matrix)
        if (determinant.isZero) throw SymException("矩阵不可逆（行列式为 0）")
        val plainRows = mutableListOf<List<String>>()
        val latexRows = mutableListOf<List<String>>()
        for (i in 0 until n) {
            val plainCells = mutableListOf<String>()
            val latexCells = mutableListOf<String>()
            for (j in 0 until n) {
                // 伴随矩阵第 (i,j) 项是代数余子式 C_{j,i}
                val cofactor = cofactorSymbolic(matrix, j, i)
                val entry = SymRational.canonical(cofactor, determinant)
                plainCells.add(formatRationalPlain(entry))
                latexCells.add(formatRationalLatex(entry))
            }
            plainRows.add(plainCells)
            latexRows.add(latexCells)
        }
        return Outcome(
            "逆矩阵",
            matrixPlain(plainRows),
            matrixLatex(latexRows),
            listOf("${n}×${n} 矩阵", "行列式: ${formatPolyPlain(determinant)}")
        )
    }

    // ------------------------------------------------------------------ rref

    private fun commandRref(parameter: String): Outcome {
        val matrix = parseMatrix(parameter, "矩阵")
        val numeric = matrix.numericCells() ?: throw SymException("行最简形需要数值矩阵")
        val pivotCols = rrefNumeric(numeric).first
        val plainRows = numeric.map { row -> row.map { it.toPlain() } }
        val latexRows = numeric.map { row -> row.map { latexNumber(it) } }
        return Outcome(
            "行最简形",
            matrixPlain(plainRows),
            matrixLatex(latexRows),
            listOf("${matrix.rows}×${matrix.cols} 矩阵", "秩: ${pivotCols.size}")
        )
    }

    // ------------------------------------------------------------------ charpoly

    private fun commandCharpoly(parameter: String): Outcome {
        val matrix = parseMatrix(parameter, "矩阵")
        if (matrix.rows != matrix.cols) throw SymException("特征多项式要求方阵，当前矩阵为 ${matrix.rows}×${matrix.cols}")
        val n = matrix.rows
        if (n == 0) throw SymException("矩阵为空")
        if (n > 6) throw SymException("特征多项式最多支持 6 阶")
        if (!matrix.isNumeric() && n > 5) throw SymException("符号矩阵的特征多项式最多支持 5 阶，请先代入数值")
        val lambda = SymPoly.variable("λ")
        val cells = mutableListOf<SymPoly>()
        for (r in 0 until n) {
            for (c in 0 until n) {
                cells.add(if (r == c) lambda - matrix.at(r, c) else matrix.at(r, c).negate())
            }
        }
        val characteristic = detSymbolic(SymMatrix(n, n, cells))
        var trace = SymPoly.ZERO
        for (i in 0 until n) trace += matrix.at(i, i)
        val numeric = matrix.numericCells()
        val determinant = if (numeric != null) SymPoly.constant(detNumeric(numeric)) else detSymbolic(matrix)
        val extras = mutableListOf<String>()
        extras.add("迹: ${formatPolyPlain(trace)}")
        extras.add("行列式: ${formatPolyPlain(determinant)}")
        val factored = factorPoly(characteristic)
        val factoredText = formatFactoredPlain(factored)
        val plain = formatPolyPlain(characteristic)
        val showFactorization = !characteristic.isZero && factoredText != plain && factored.factors.size >= 2
        if (showFactorization) {
            extras.add("因式分解: $factoredText")
            if (!factored.complete) extras.add("（在有理数范围内未能完全分解）")
        }
        return Outcome("特征多项式", plain, formatPolyLatex(characteristic), extras)
    }

    // ------------------------------------------------------------------ trace / transpose

    private fun commandTrace(parameter: String): Outcome {
        val matrix = parseMatrix(parameter, "矩阵")
        if (matrix.rows != matrix.cols) throw SymException("迹要求方阵，当前矩阵为 ${matrix.rows}×${matrix.cols}")
        var sum = SymPoly.ZERO
        for (i in 0 until matrix.rows) sum += matrix.at(i, i)
        return Outcome("迹", formatPolyPlain(sum), formatPolyLatex(sum), listOf("${matrix.rows}×${matrix.cols} 矩阵"))
    }

    private fun commandTranspose(parameter: String): Outcome {
        val matrix = parseMatrix(parameter, "矩阵")
        val transposed = matrix.transposed()
        val plainRows = (0 until transposed.rows).map { r ->
            (0 until transposed.cols).map { c -> formatPolyPlain(transposed.at(r, c)) }
        }
        val latexRows = (0 until transposed.rows).map { r ->
            (0 until transposed.cols).map { c -> formatPolyLatex(transposed.at(r, c)) }
        }
        return Outcome(
            "转置",
            matrixPlain(plainRows),
            matrixLatex(latexRows),
            listOf("原矩阵 ${matrix.rows}×${matrix.cols}", "转置后 ${transposed.rows}×${transposed.cols}")
        )
    }

    // ------------------------------------------------------------------ solve

    private fun commandSolve(parameter: String): Outcome {
        val parts = splitTopLevel(parameter, '|')
        if (parts.size < 2) throw SymException("solve 参数应为 A=... | b=...，例如 solve: A=1,2;3,4 | b=5;6")
        val a = parseMatrix(stripLabel(parts[0]), "系数矩阵 A")
        var b = parseMatrix(stripLabel(parts[1]), "常数向量 b")
        if (b.cols != 1) {
            if (b.rows == 1) {
                b = b.transposed()
            } else {
                throw SymException("b 必须是列向量（形如 b=5;6）")
            }
        }
        if (b.rows != a.rows) throw SymException("b 的行数（${b.rows}）必须等于 A 的行数（${a.rows}）")
        val numericA = a.numericCells()
        val numericB = b.numericCells()
        if (numericA != null && numericB != null) {
            val rightSide = Array(b.rows) { r -> numericB[r][0] }
            val solution = solveNumeric(numericA, rightSide)
            val expressions = solution.first
            val parameters = solution.second
            val plain = expressions.withIndex().joinToString(", ") { (index, expression) ->
                "x${index + 1} = ${formatPolyPlain(expression)}"
            }
            val extras = mutableListOf<String>()
            if (parameters.isEmpty()) {
                extras.add("唯一解")
                extras.add("向量形式: (" + expressions.joinToString(", ") { formatPolyPlain(it) } + ")")
            } else {
                extras.add("有无穷多解，自由变量 ${parameters.joinToString(", ")} 可任取有理数")
            }
            val latex = expressions.withIndex().joinToString(", \\; ") { (index, expression) ->
                "x_{${index + 1}} = ${formatPolyLatex(expression)}"
            }
            return Outcome("解方程组", plain, latex, extras)
        }
        // 符号矩阵：克莱姆法则 / 伴随矩阵，阶数 ≤4
        if (a.rows != a.cols) throw SymException("符号方程组需要方阵系数矩阵（当前 ${a.rows}×${a.cols}）")
        if (a.rows > 4) throw SymException("符号方程组最多支持 4 个未知数，请先代入数值")
        val determinant = detSymbolic(a)
        if (determinant.isZero) {
            throw SymException("符号方程组无法用克莱姆法则求解：系数行列式为 0（可能无解或有无穷多解）")
        }
        val entries = mutableListOf<SymRational>()
        for (i in 0 until a.rows) {
            var numerator = SymPoly.ZERO
            for (k in 0 until a.rows) {
                numerator += cofactorSymbolic(a, k, i) * b.at(k, 0)
            }
            entries.add(SymRational.canonical(numerator, determinant))
        }
        val plain = entries.withIndex().joinToString(", ") { (index, entry) ->
            "x${index + 1} = ${formatRationalPlain(entry)}"
        }
        val latex = entries.withIndex().joinToString(", \\; ") { (index, entry) ->
            "x_{${index + 1}} = ${formatRationalLatex(entry)}"
        }
        return Outcome("解方程组", plain, latex, listOf("唯一解（符号形式）"))
    }

    // ------------------------------------------------------------------ mul

    private fun commandMul(parameter: String): Outcome {
        val parts = splitTopLevel(parameter, '|')
        if (parts.size < 2) throw SymException("mul 参数应为 A=... | B=...，例如 mul: A=1,2;3,4 | B=2,0;0,2")
        val left = parseMatrix(stripLabel(parts[0]), "矩阵 A")
        val right = parseMatrix(stripLabel(parts[1]), "矩阵 B")
        if (left.cols != right.rows) {
            throw SymException("矩阵维度不匹配：A 为 ${left.rows}×${left.cols}，B 为 ${right.rows}×${right.cols}")
        }
        val cells = mutableListOf<SymPoly>()
        for (i in 0 until left.rows) {
            for (j in 0 until right.cols) {
                var sum = SymPoly.ZERO
                for (k in 0 until left.cols) sum += left.at(i, k) * right.at(k, j)
                cells.add(sum)
            }
        }
        val product = SymMatrix(left.rows, right.cols, cells)
        val plainRows = (0 until product.rows).map { r ->
            (0 until product.cols).map { c -> formatPolyPlain(product.at(r, c)) }
        }
        val latexRows = (0 until product.rows).map { r ->
            (0 until product.cols).map { c -> formatPolyLatex(product.at(r, c)) }
        }
        return Outcome(
            "矩阵乘法",
            matrixPlain(plainRows),
            matrixLatex(latexRows),
            listOf("${left.rows}×${left.cols} 乘 ${right.rows}×${right.cols} = ${product.rows}×${product.cols}")
        )
    }

    // ------------------------------------------------------------------ factor / expand / simplify / subst

    private fun commandFactor(parameter: String): Outcome {
        val value = SymParser(parameter).parse()
        if (!value.den.isConstant) throw SymException("因式分解需要多项式；分式请先用 simplify 化简")
        val poly = value.num.scale(value.den.constantValue().reciprocal())
        val factored = factorPoly(poly)
        val plain = formatFactoredPlain(factored)
        val extras = mutableListOf<String>()
        val expanded = formatPolyPlain(poly)
        if (expanded != plain) extras.add("展开形式: $expanded")
        if (!factored.complete) extras.add("（在有理数范围内未能完全分解）")
        return Outcome("因式分解", plain, formatFactoredLatex(factored), extras)
    }

    private fun commandExpand(parameter: String): Outcome {
        val value = SymParser(parameter).parse()
        return Outcome("展开", formatRationalPlain(value), formatRationalLatex(value))
    }

    private fun commandSimplify(parameter: String): Outcome {
        var value = SymParser(parameter).parse()
        val extras = mutableListOf<String>()
        val cancelled = cancelCommonFactors(value)
        if (cancelled != null) {
            value = cancelled
            extras.add("已约去分子分母的公因式")
        }
        if (!value.den.isConstant) {
            if (hasReducibleFactor(value)) {
                extras.add("未能完全约分（分子分母仍有公因式的情形超出当前算法能力）")
            } else if (value.variables().size == 1) {
                extras.add("已化为最简分式（分子分母在有理数范围内互质）")
            }
        }
        return Outcome("化简", formatRationalPlain(value), formatRationalLatex(value), extras)
    }

    /**
     * 把分子分母各自因式分解后约去公共因式，主要用于多变量分式
     * （例如 (a^2-b^2)/(a+b) → a - b）。重建校验不过就放弃，返回 null。
     */
    private fun cancelCommonFactors(value: SymRational): SymRational? {
        if (value.den.isConstant || value.num.isConstant) return null
        val numFactors = factorPoly(value.num)
        val denFactors = factorPoly(value.den)
        if (numFactors.factors.isEmpty() || denFactors.factors.isEmpty()) return null
        // 分解结果必须能重建出原式，否则不可信。
        var checkNum = SymPoly.constant(numFactors.constant)
        for (f in numFactors.factors) checkNum *= f
        if (checkNum != value.num) return null
        var checkDen = SymPoly.constant(denFactors.constant)
        for (f in denFactors.factors) checkDen *= f
        if (checkDen != value.den) return null
        val remainingNum = numFactors.factors.toMutableList()
        val remainingDen = denFactors.factors.toMutableList()
        var cancelled = false
        val iterator = remainingDen.iterator()
        while (iterator.hasNext()) {
            val candidate = iterator.next()
            val index = remainingNum.indexOfFirst { it == candidate }
            if (index >= 0) {
                remainingNum.removeAt(index)
                iterator.remove()
                cancelled = true
            }
        }
        if (!cancelled) return null
        var newNum = SymPoly.constant(numFactors.constant)
        for (f in remainingNum) newNum *= f
        var newDen = SymPoly.constant(denFactors.constant)
        for (f in remainingDen) newDen *= f
        return SymRational.canonical(newNum, newDen)
    }

    /** 单变量时判断分子分母是否还有一次以上的公因式（多变量暂按「不可判定」处理，返回 true）。 */
    private fun hasReducibleFactor(value: SymRational): Boolean {
        val variables = value.variables()
        if (variables.size != 1) return false
        val variable = variables[0]
        if (value.den.degreeIn(variable) <= 0) return false
        if (value.num.degreeIn(variable) <= 0) return false
        val gcd = univariateGcd(value.num, value.den, variable)
        return gcd.degreeIn(variable) >= 1
    }

    private fun commandSubst(parameter: String): Outcome {
        val parts = splitTopLevel(parameter, '|')
        if (parts.size < 2) throw SymException("subst 参数应为 表达式 | x=2，例如 subst: x^3 - x | x=2")
        val expression = SymParser(parts[0]).parse()
        val assignments = LinkedHashMap<String, SymPoly>()
        val assignmentTexts = splitTopLevel(parts[1], ',').flatMap { splitTopLevel(it, ';') }
        for (text in assignmentTexts) {
            if (text.isBlank()) continue
            val index = text.indexOf('=')
            if (index < 0) throw SymException("代入值应写成「变量=数值」，例如 x=2")
            val name = SymParser.canonicalVariableName(text.substring(0, index).trim())
            if (name.isEmpty()) throw SymException("代入值缺少变量名")
            val rawValue = SymParser(text.substring(index + 1).trim()).parse()
            if (!rawValue.den.isConstant) throw SymException("变量 $name 的代入值不能含有变量作分母")
            // 允许代入表达式（如 y=x+1）：代入后未消去的变量保留符号。
            assignments[name] = if (rawValue.den.constantValue() == SymFrac.ONE) {
                rawValue.num
            } else {
                rawValue.num.scale(rawValue.den.constantValue().reciprocal())
            }
        }
        if (assignments.isEmpty()) throw SymException("subst 缺少代入值，例如 x=2")
        val result = expression.substitutePolys(assignments)
        val plain = formatRationalPlain(result)
        val extras = mutableListOf<String>()
        val remaining = result.variables()
        if (remaining.isNotEmpty()) extras.add("代入后仍含变量: ${remaining.joinToString(", ")}")
        if (result.den.isConstant) {
            val poly = result.num.scale(result.den.constantValue().reciprocal())
            val factored = factorPoly(poly)
            val factoredText = formatFactoredPlain(factored)
            if (!poly.isZero && factoredText != formatPolyPlain(poly)) extras.add("因式分解: $factoredText")
        }
        return Outcome("代入求值", plain, formatRationalLatex(result), extras)
    }

    // ------------------------------------------------------------------ summarize / sample / selfTest

    /** 把结果压成给模型阅读的中文摘要，总长度不超过 [maxChars]。 */
    fun summarize(results: List<MathResult>, maxChars: Int = 1400): String {
        if (results.isEmpty()) return "没有读到有效命令。"
        val limit = if (maxChars > 40) maxChars - 12 else maxChars
        val success = results.count { it.error == null }
        val sb = StringBuilder()
        sb.append("计算结果（共 ${results.size} 条，成功 $success 条，失败 ${results.size - success} 条）")
        var truncated = false
        for ((index, item) in results.withIndex()) {
            val head = "\n${index + 1}. ${item.title}（${item.command.take(40)}）"
            val body = if (item.error != null) {
                "\n   失败: ${item.error}"
            } else {
                val text = if (item.plain.length > 220) item.plain.take(220) + "…" else item.plain
                "\n   结果: ${if (text.isEmpty()) "（无）" else text}"
            }
            val extras = item.extra.take(2).joinToString("") { "\n   · $it" }
            val block = head + body + extras
            if (sb.length + block.length > limit) {
                truncated = true
                break
            }
            sb.append(block)
        }
        if (truncated) sb.append("\n…（结果较多，已截断）")
        return sb.toString()
    }

    /** 内置示例命令块。 */
    fun sample(): String = """
        # 线性代数计算器示例：每行一条命令
        det: x,1,0; 1,x,1; 0,1,x
        rank: 1,2,3; 2,4,6
        inv: 4,7; 2,6
        charpoly: 2,1; 1,2
        solve: A=1,2;3,4 | b=5;6
        factor: a^2 - b^2
        expand: (a+b)^3
        simplify: (x^2-1)/(x-1)
        subst: x^3 - x | x=2
    """.trimIndent()

    /**
     * 自检：只断言已经实现并手工验证过的行为，返回失败说明列表（全部通过则返回空列表）。
     * 比较纯文本结果时先去掉所有空格。
     */
    fun selfTest(): List<String> {
        val failures = mutableListOf<String>()

        fun noSpace(text: String): String = text.filter { it != ' ' && it != '\n' && it != '\t' }

        fun exec(line: String): MathResult? = runBlock(line).firstOrNull()

        fun expectPlain(name: String, line: String, expected: String) {
            val result = exec(line)
            if (result == null) {
                failures.add("$name：没有返回结果")
                return
            }
            if (result.error != null) {
                failures.add("$name：执行失败（${result.error}）")
                return
            }
            if (noSpace(result.plain) != noSpace(expected)) {
                failures.add("$name：期望「$expected」，实际「${result.plain}」")
            }
        }

        fun expectContains(name: String, line: String, vararg needles: String) {
            val result = exec(line)
            if (result == null) {
                failures.add("$name：没有返回结果")
                return
            }
            if (result.error != null) {
                failures.add("$name：执行失败（${result.error}）")
                return
            }
            for (needle in needles) {
                if (!noSpace(result.plain).contains(noSpace(needle))) {
                    failures.add("$name：结果「${result.plain}」中缺少「$needle」")
                }
            }
        }

        fun expectError(name: String, line: String, keyword: String) {
            val result = exec(line)
            if (result == null) {
                failures.add("$name：没有返回结果")
                return
            }
            val error = result.error
            if (error == null) {
                failures.add("$name：本应报错，实际返回「${result.plain}」")
            } else if (!error.contains(keyword)) {
                failures.add("$name：错误信息「$error」中缺少「$keyword」")
            }
        }

        /** 把结果文本重新解析并展开成规范多项式文本，用于验证「因式分解后展开等于原式」。 */
        fun expandedOf(text: String): String? = try {
            val value = SymParser(text).parse()
            if (!value.den.isConstant) null
            else formatPolyPlain(value.num.scale(value.den.constantValue().reciprocal()))
        } catch (e: Exception) {
            null
        }

        // 1. 平方差因式分解（多变量恒等式）
        val factorDifference = exec("factor: a^2 - b^2")
        if (factorDifference == null) {
            failures.add("因式分解平方差：没有返回结果")
        } else if (factorDifference.error != null) {
            failures.add("因式分解平方差：执行失败（${factorDifference.error}）")
        } else {
            if (factorDifference.plain.count { it == '(' } < 2) {
                failures.add("因式分解平方差：应含两个因子，实际「${factorDifference.plain}」")
            }
            val expanded = expandedOf(factorDifference.plain)
            if (expanded == null || noSpace(expanded) != "a^2-b^2") {
                failures.add("因式分解平方差：展开后不等于 a^2 - b^2（实际「$expanded」）")
            }
        }

        // 2. 展开三次方
        expectPlain("展开三次方", "expand: (a+b)^3", "a^3 + 3*a^2*b + 3*a*b^2 + b^3")

        // 3. 二阶符号行列式
        expectPlain("二阶符号行列式", "det: a,b;c,d", "a*d - b*c")

        // 4. 三阶符号行列式
        expectPlain("三阶符号行列式", "det: x,1,0;1,x,1;0,1,x", "x^3 - 2*x")

        // 5. 二阶数值行列式
        expectPlain("二阶数值行列式", "det: 1,2;3,4", "-2")

        // 6. 秩
        expectPlain("矩阵的秩", "rank: 1,2,3;2,4,6", "1")

        // 7. 逆矩阵（逐项断言）
        expectContains("二阶数值逆矩阵", "inv: 4,7;2,6", "3/5", "-7/10", "-1/5", "2/5")

        // 8. 解线性方程组
        expectContains("解方程组", "solve: A=1,2;3,4 | b=5;6", "-4", "9/2")

        // 9. 特征多项式
        expectPlain("特征多项式", "charpoly: 2,1;1,2", "λ^2 - 4*λ + 3")

        // 10. 约分
        expectPlain("分式化简", "simplify: (x^2-1)/(x-1)", "x + 1")

        // 11. 全角符号归一化
        expectPlain("全角乘号", "expand: (x+1)×(x-1)", "x^2 - 1")
        expectPlain("全角括号与除号", "simplify: （x^2－1）÷（x－1）", "x + 1")
        expectPlain("全角逗号与分号", "det: 1，2；3，4", "-2")

        // 12. 错误处理
        expectError("行列数不一致", "det: 1,2;3", "元素个数")
        expectError("不支持的命令", "foo: 1", "不支持的命令")

        // 额外覆盖：转置 / 迹 / 矩阵乘法 / 代入
        expectPlain("转置", "transpose: 1,2;3,4", "[[1, 3], [2, 4]]")
        expectPlain("迹", "trace: 1,2;3,4", "5")
        expectPlain("矩阵乘法", "mul: A=1,2;3,4 | B=2,0;0,2", "[[2, 4], [6, 8]]")
        expectPlain("代入求值", "subst: x^3 - x | x=2", "6")

        // 额外覆盖：完全平方 / 立方和 / 符号代入 / 行尾注释 / 有理数行列式
        expectPlain("完全平方三项式", "factor: x^2 + 2*x*y + y^2", "(x + y)^2")
        expectPlain("立方和", "factor: a^3 + b^3", "(a + b)*(a^2 - a*b + b^2)")
        expectPlain("索菲热尔曼恒等式", "factor: x^4 + 4", "(x^2 - 2*x + 2)*(x^2 + 2*x + 2)")
        expectPlain("符号代入", "subst: x^2 + y | x=1, y=a", "a + 1")
        expectPlain("行尾注释", "det: 1,2;3,4 # 注释", "-2")
        expectPlain("分数行列式", "det: 1/2,1/3;1/4,1/5", "1/60")
        expectPlain("三阶数值逆矩阵", "inv: 1,0,0;0,1,0;0,0,1", "[[1, 0, 0], [0, 1, 0], [0, 0, 1]]")
        expectPlain("行最简形", "rref: 1,2,3;4,5,6", "[[1, 0, -1], [0, 1, 2]]")
        expectError("符号矩阵无法求行最简形", "rref: a,b;c,d", "数值矩阵")
        expectError("不可逆矩阵", "inv: 1,2;2,4", "不可逆")
        expectError("方程组无解", "solve: A=1,2;2,4 | b=3;7", "无解")
        expectContains("无穷多解", "solve: A=1,2;2,4 | b=3;6", "t1")
        expectPlain("多变量分式约分", "simplify: (a^2-b^2)/(a+b)", "a - b")
        expectPlain("单变量分式约分", "simplify: (x^2-1)/(x^2+2*x+1)", "(x - 1) / (x + 1)")
        expectPlain("多项式公因式约分", "simplify: (x^2*y+x*y^2)/(x*y)", "x + y")
        expectPlain("四次式二次拆分", "factor: 4*x^2 - 12*x + 9", "(2*x - 3)^2")
        expectPlain("四次式无有理根", "factor: x^8 - 1", "(x - 1)*(x + 1)*(x^2 + 1)*(x^4 + 1)")
        expectPlain("符号方程组唯一解", "solve: A=a,1;1,a | b=1;1", "x1 = 1 / (a + 1), x2 = 1 / (a + 1)")
        expectPlain("矩阵多变量行列式", "det: a,b;b,a", "a^2 - b^2")

        return failures
    }
}