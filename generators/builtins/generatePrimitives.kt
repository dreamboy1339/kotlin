/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

import org.jetbrains.kotlin.generators.builtins.PrimitiveType
import org.jetbrains.kotlin.generators.builtins.PrimitiveType.Companion.floatingPoint
import org.jetbrains.kotlin.generators.builtins.generateBuiltIns.BUILT_INS_NATIVE_DIR
import java.io.File
import java.io.PrintWriter
import java.util.*

private val END_LINE = System.lineSeparator()

private fun String.shift(): String {
    return this.split(END_LINE).joinToString(separator = END_LINE) { "\t$it" }
}

abstract class AnnotatedAndDocumented {
    protected abstract var doc: String?
    protected abstract val annotations: MutableList<String>

    fun addDoc(doc: String) {
        if (this.doc == null) {
            this.doc = doc
        } else {
            this.doc += "$END_LINE$doc"
        }
    }

    fun addAnnotation(annotation: String) {
        annotations += annotation
    }

    fun StringBuilder.printDocumentationAndAnnotations() {
        if (doc != null) {
            appendLine(doc!!.printAsDoc())
        }

        if (annotations.isNotEmpty()) {
            appendLine(annotations.joinToString(separator = END_LINE) { "@$it" })
        }
    }

    private fun String.printAsDoc(): String {
        if (this.contains(END_LINE)) {
            return this.split(END_LINE)
                .joinToString(separator = END_LINE, prefix = "/**$END_LINE", postfix = "$END_LINE */") { " * $it" }
        }
        return "/** $this */"
    }
}

data class FileDescription(
    private val suppresses: MutableList<String> = mutableListOf(),
    private val imports: MutableList<String> = mutableListOf(),
    val classes: List<ClassDescription>
) {
    fun addSuppress(suppress: String) {
        suppresses += suppress
    }

    fun addImport(newImport: String) {
        imports += newImport
    }

    override fun toString(): String {
        return buildString {
            appendLine(File("license/COPYRIGHT_HEADER.txt").readText())
            appendLine()
            appendLine("// Auto-generated file. DO NOT EDIT!")
            appendLine()

            if (suppresses.isNotEmpty()) {
                appendLine(suppresses.joinToString(separator = ", ", prefix = "@file:Suppress(", postfix = ")") { "\"$it\"" })
                appendLine()
            }

            appendLine("package kotlin")
            appendLine()

            if (imports.isNotEmpty()) {
                appendLine(imports.joinToString(separator = END_LINE) { "import $it" })
                appendLine()
            }

            append(classes.joinToString(separator = END_LINE))
        }
    }
}

data class ClassDescription(
    override var doc: String?,
    override val annotations: MutableList<String>,
    var isFinal: Boolean = false,
    val name: String,
    val companionObject: CompanionObjectDescription,
    val methods: List<MethodDescription>
) : AnnotatedAndDocumented() {
    override fun toString(): String {
        return buildString {
            this.printDocumentationAndAnnotations()

            append("public ")
            if (isFinal) append("final ")
            appendLine("class $name private constructor() : Number(), Comparable<$name> {")
            appendLine(companionObject.toString().shift())
            appendLine(methods.joinToString(separator = END_LINE + END_LINE) { it.toString().shift() })
            appendLine("}")
        }
    }
}

data class CompanionObjectDescription(
    override var doc: String? = null,
    override val annotations: MutableList<String> = mutableListOf(),
    val properties: List<PropertyDescription>
) : AnnotatedAndDocumented() {
    override fun toString(): String {
        return buildString {
            printDocumentationAndAnnotations()
            appendLine("companion object {")
            appendLine(properties.joinToString(separator = END_LINE + END_LINE) { it.toString().shift() })
            appendLine("}")
        }
    }
}

data class MethodSignature(
    var isExternal: Boolean = false,
    val visibility: String = "public",
    var isInfix: Boolean = false,
    var isInline: Boolean = false,
    var isOverride: Boolean = false,
    var isOperator: Boolean = false,
    val name: String,
    val arg: MethodParameter?,
    val returnType: String
) {
    override fun toString(): String {
        return buildString {
            if (isExternal) append("external ")
            append("$visibility ")
            if (isInfix) append("infix ")
            if (isInline) append("inline ")
            if (isOverride) append("override ")
            if (isOperator) append("operator ")
            append("fun $name(${arg ?: ""}): $returnType")
        }
    }
}

data class MethodParameter(val name: String, val type: String) {
    fun getTypeAsPrimitive(): PrimitiveType = PrimitiveType.valueOf(type.uppercase())

    override fun toString(): String {
        return "$name: $type"
    }
}

data class MethodDescription(
    override var doc: String?,
    override val annotations: MutableList<String> = mutableListOf(),
    val signature: MethodSignature,
    private var body: String? = null
) : AnnotatedAndDocumented() {
    override fun toString(): String {
        return buildString {
            printDocumentationAndAnnotations()
            append(signature)
            append(body ?: "")
        }
    }

    fun String.addAsSingleLineBody(bodyOnNewLine: Boolean = false): String {
        val skip = if (bodyOnNewLine) "$END_LINE\t" else ""
        return " = $skip$this"
    }

    fun String.addAsMultiLineBody(): String {
        return " {$END_LINE${this.shift()}$END_LINE}"
    }
}

data class PropertyDescription(
    override var doc: String?,
    override val annotations: MutableList<String> = mutableListOf(),
    val name: String,
    val type: String,
    val value: String
) : AnnotatedAndDocumented() {
    override fun toString(): String {
        return buildString {
            printDocumentationAndAnnotations()
            append("public const val $name: $type = $value")
        }
    }
}

abstract class BaseGenerator {
    companion object {
        internal val binaryOperators: List<String> = listOf(
            "plus",
            "minus",
            "times",
            "div",
            "rem",
        )
        internal val unaryPlusMinusOperators: Map<String, String> = mapOf(
            "unaryPlus" to "Returns this value.",
            "unaryMinus" to "Returns the negative of this value."
        )
        internal val shiftOperators: Map<String, String> = mapOf(
            "shl" to "Shifts this value left by the [bitCount] number of bits.",
            "shr" to "Shifts this value right by the [bitCount] number of bits, filling the leftmost bits with copies of the sign bit.",
            "ushr" to "Shifts this value right by the [bitCount] number of bits, filling the leftmost bits with zeros.")
        internal val bitwiseOperators: Map<String, String> = mapOf(
            "and" to "Performs a bitwise AND operation between the two values.",
            "or" to "Performs a bitwise OR operation between the two values.",
            "xor" to "Performs a bitwise XOR operation between the two values.")

        internal fun shiftOperatorsDocDetail(kind: PrimitiveType): String {
            val bitsUsed = when (kind) {
                PrimitiveType.INT -> "five"
                PrimitiveType.LONG -> "six"
                else -> throw IllegalArgumentException("Bit shift operation is not implemented for $kind")
            }
            return """ 
                Note that only the $bitsUsed lowest-order bits of the [bitCount] are used as the shift distance.
                The shift distance actually used is therefore always in the range `0..${kind.bitSize - 1}`.
                """.trimIndent()
        }

        internal fun incDecOperatorsDoc(name: String): String {
            val diff = if (name == "inc") "incremented" else "decremented"

            return """
                Returns this value $diff by one.

                @sample samples.misc.Builtins.$name
            """.trimIndent()
        }

        internal fun binaryOperatorDoc(operator: String, operand1: PrimitiveType, operand2: PrimitiveType): String = when (operator) {
            "plus" -> "Adds the other value to this value."
            "minus" -> "Subtracts the other value from this value."
            "times" -> "Multiplies this value by the other value."
            "div" -> {
                if (operand1.isIntegral && operand2.isIntegral)
                    "Divides this value by the other value, truncating the result to an integer that is closer to zero."
                else
                    "Divides this value by the other value."
            }
            "floorDiv" ->
                "Divides this value by the other value, flooring the result to an integer that is closer to negative infinity."
            "rem" -> {
                """
                Calculates the remainder of truncating division of this value (dividend) by the other value (divisor).
                
                The result is either zero or has the same sign as the _dividend_ and has the absolute value less than the absolute value of the divisor.
                """.trimIndent()
            }
            else -> error("No documentation for operator $operator")
        }

        private fun compareByDomainCapacity(type1: PrimitiveType, type2: PrimitiveType): Int {
            return if (type1.isIntegral && type2.isIntegral) type1.byteSize - type2.byteSize else type1.ordinal - type2.ordinal
        }

        private fun docForConversionFromFloatingToIntegral(fromFloating: PrimitiveType, toIntegral: PrimitiveType): String {
            require(fromFloating.isFloatingPoint)
            require(toIntegral.isIntegral)

            val thisName = fromFloating.capitalized
            val otherName = toIntegral.capitalized

            return if (compareByDomainCapacity(toIntegral, PrimitiveType.INT) < 0) {
                """
             The resulting `$otherName` value is equal to `this.toInt().to$otherName()`.
            """.trimIndent()
            } else {
                """
             The fractional part, if any, is rounded down towards zero.
             Returns zero if this `$thisName` value is `NaN`, [$otherName.MIN_VALUE] if it's less than `$otherName.MIN_VALUE`,
             [$otherName.MAX_VALUE] if it's bigger than `$otherName.MAX_VALUE`.
            """.trimIndent()
            }
        }

        private fun docForConversionFromFloatingToFloating(fromFloating: PrimitiveType, toFloating: PrimitiveType): String {
            require(fromFloating.isFloatingPoint)
            require(toFloating.isFloatingPoint)

            val thisName = fromFloating.capitalized
            val otherName = toFloating.capitalized

            return if (compareByDomainCapacity(toFloating, fromFloating) < 0) {
                """
             The resulting value is the closest `$otherName` to this `$thisName` value.
             In case when this `$thisName` value is exactly between two `$otherName`s,
             the one with zero at least significant bit of mantissa is selected.
            """.trimIndent()
            } else {
                """
             The resulting `$otherName` value represents the same numerical value as this `$thisName`.
            """.trimIndent()
            }
        }

        private fun docForConversionFromIntegralToIntegral(fromIntegral: PrimitiveType, toIntegral: PrimitiveType): String {
            require(fromIntegral.isIntegral)
            require(toIntegral.isIntegral)

            val thisName = fromIntegral.capitalized
            val otherName = toIntegral.capitalized

            return if (toIntegral == PrimitiveType.CHAR) {
                if (fromIntegral == PrimitiveType.SHORT) {
                    """
                The resulting `Char` code is equal to this value reinterpreted as an unsigned number,
                i.e. it has the same binary representation as this `Short`.
                """.trimIndent()
                } else if (fromIntegral == PrimitiveType.BYTE) {
                    """
                If this value is non-negative, the resulting `Char` code is equal to this value.
                
                The least significant 8 bits of the resulting `Char` code are the same as the bits of this `Byte` value,
                whereas the most significant 8 bits are filled with the sign bit of this value.
                """.trimIndent()
                } else {
                    """
                If this value is in the range of `Char` codes `Char.MIN_VALUE..Char.MAX_VALUE`,
                the resulting `Char` code is equal to this value.
                
                The resulting `Char` code is represented by the least significant 16 bits of this `$thisName` value.
                """.trimIndent()
                }
            } else if (compareByDomainCapacity(toIntegral, fromIntegral) < 0) {
                """
             If this value is in [$otherName.MIN_VALUE]..[$otherName.MAX_VALUE], the resulting `$otherName` value represents
             the same numerical value as this `$thisName`.
             
             The resulting `$otherName` value is represented by the least significant ${toIntegral.bitSize} bits of this `$thisName` value.
            """.trimIndent()
            } else {
                """
             The resulting `$otherName` value represents the same numerical value as this `$thisName`.
             
             The least significant ${fromIntegral.bitSize} bits of the resulting `$otherName` value are the same as the bits of this `$thisName` value,
             whereas the most significant ${toIntegral.bitSize - fromIntegral.bitSize} bits are filled with the sign bit of this value.
            """.trimIndent()
            }
        }

        private fun docForConversionFromIntegralToFloating(fromIntegral: PrimitiveType, toFloating: PrimitiveType): String {
            require(fromIntegral.isIntegral)
            require(toFloating.isFloatingPoint)

            val thisName = fromIntegral.capitalized
            val otherName = toFloating.capitalized

            return if (fromIntegral == PrimitiveType.LONG || fromIntegral == PrimitiveType.INT && toFloating == PrimitiveType.FLOAT) {
                """
             The resulting value is the closest `$otherName` to this `$thisName` value.
             In case when this `$thisName` value is exactly between two `$otherName`s,
             the one with zero at least significant bit of mantissa is selected.
            """.trimIndent()
            } else {
                """
             The resulting `$otherName` value represents the same numerical value as this `$thisName`.
            """.trimIndent()
            }
        }
    }

    private val typeDescriptions: Map<PrimitiveType, String> = mapOf(
        PrimitiveType.DOUBLE to "double-precision 64-bit IEEE 754 floating point number",
        PrimitiveType.FLOAT to "single-precision 32-bit IEEE 754 floating point number",
        PrimitiveType.LONG to "64-bit signed integer",
        PrimitiveType.INT to "32-bit signed integer",
        PrimitiveType.SHORT to "16-bit signed integer",
        PrimitiveType.BYTE to "8-bit signed integer",
        PrimitiveType.CHAR to "16-bit Unicode character"
    )

    open fun primitiveConstants(type: PrimitiveType): List<Any> = when (type) {
        PrimitiveType.INT -> listOf(java.lang.Integer.MIN_VALUE, java.lang.Integer.MAX_VALUE)
        PrimitiveType.BYTE -> listOf(java.lang.Byte.MIN_VALUE, java.lang.Byte.MAX_VALUE)
        PrimitiveType.SHORT -> listOf(java.lang.Short.MIN_VALUE, java.lang.Short.MAX_VALUE)
        PrimitiveType.LONG -> listOf((java.lang.Long.MIN_VALUE + 1).toString() + "L - 1L", java.lang.Long.MAX_VALUE.toString() + "L")
        PrimitiveType.DOUBLE -> listOf(java.lang.Double.MIN_VALUE, java.lang.Double.MAX_VALUE, "1.0/0.0", "-1.0/0.0", "-(0.0/0.0)")
        PrimitiveType.FLOAT -> listOf(java.lang.Float.MIN_VALUE, java.lang.Float.MAX_VALUE, "1.0F/0.0F", "-1.0F/0.0F", "-(0.0F/0.0F)").map { it as? String ?: "${it}F" }
        else -> throw IllegalArgumentException("type: $type")
    }

    fun generate(): String {
        return FileDescription(classes = generateClasses()).apply { this.modifyGeneratedFile() }.toString()
    }

    private fun generateClasses(): List<ClassDescription> {
        return buildList {
            for (thisKind in PrimitiveType.onlyNumeric) {
                val className = thisKind.capitalized
                val doc = "Represents a ${typeDescriptions[thisKind]}."

                val methods = buildList {
                    this.addAll(generateCompareTo(thisKind))
                    this.addAll(generateBinaryOperators(thisKind))
                    this.addAll(generateUnaryOperators(thisKind))
                    this.addAll(generateRangeTo(thisKind))
                    this.addAll(generateRangeUntil(thisKind))

                    if (thisKind == PrimitiveType.INT || thisKind == PrimitiveType.LONG) {
                        this.addAll(generateBitShiftOperators(thisKind))
                    }

                    if (thisKind == PrimitiveType.INT || thisKind == PrimitiveType.LONG) {
                        this.addAll(generateBitwiseOperators(thisKind))
                    }

                    this.addAll(generateConversions(thisKind))
                    this += generateEquals(thisKind)
                    this += generateToString(thisKind)
                    this.addAll(generateAdditionalMethods(thisKind))
                }

                this += ClassDescription(
                    doc,
                    annotations = mutableListOf(),
                    name = className,
                    companionObject = generateCompanionObject(thisKind),
                    methods = methods
                ).apply { this.modifyGeneratedClass(thisKind) }
            }
        }
    }

    private fun generateCompanionObject(thisKind: PrimitiveType): CompanionObjectDescription {
        val properties = buildList {
            val className = thisKind.capitalized
            if (thisKind == PrimitiveType.FLOAT || thisKind == PrimitiveType.DOUBLE) {
                val (minValue, maxValue, posInf, negInf, nan) = primitiveConstants(thisKind)
                this += PropertyDescription(
                    doc = "A constant holding the smallest *positive* nonzero value of $className.",
                    name = "MIN_VALUE",
                    type = className,
                    value = minValue.toString()
                )

                this += PropertyDescription(
                    doc = "A constant holding the largest positive finite value of $className.",
                    name = "MAX_VALUE",
                    type = className,
                    value = maxValue.toString()
                )

                this += PropertyDescription(
                    doc = "A constant holding the positive infinity value of $className.",
                    name = "POSITIVE_INFINITY",
                    type = className,
                    value = posInf.toString()
                )

                this += PropertyDescription(
                    doc = "A constant holding the negative infinity value of $className.",
                    name = "NEGATIVE_INFINITY",
                    type = className,
                    value = negInf.toString()
                )

                this += PropertyDescription(
                    doc = "A constant holding the \"not a number\" value of $className.",
                    name = "NaN",
                    type = className,
                    value = nan.toString()
                )
            }

            if (thisKind == PrimitiveType.INT || thisKind == PrimitiveType.LONG || thisKind == PrimitiveType.SHORT || thisKind == PrimitiveType.BYTE) {
                val (minValue, maxValue) = primitiveConstants(thisKind)
                this += PropertyDescription(
                    doc = "A constant holding the minimum value an instance of $className can have.",
                    name = "MIN_VALUE",
                    type = className,
                    value = minValue.toString()
                )

                this += PropertyDescription(
                    doc = "A constant holding the maximum value an instance of $className can have.",
                    name = "MAX_VALUE",
                    type = className,
                    value = maxValue.toString()
                )
            }

            val sizeSince = if (thisKind.isFloatingPoint) "1.4" else "1.3"
            this += PropertyDescription(
                doc = "The number of bytes used to represent an instance of $className in a binary form.",
                annotations = mutableListOf("SinceKotlin(\"$sizeSince\")"),
                name = "SIZE_BYTES",
                type = "Int",
                value = thisKind.byteSize.toString()
            )

            this += PropertyDescription(
                doc = "The number of bits used to represent an instance of $className in a binary form.",
                annotations = mutableListOf("SinceKotlin(\"$sizeSince\")"),
                name = "SIZE_BITS",
                type = "Int",
                value = thisKind.bitSize.toString()
            )
        }

        properties.forEach { it.modifyGeneratedCompanionObjectProperty(thisKind) }
        return CompanionObjectDescription(properties = properties).apply { this.modifyGeneratedCompanionObject(thisKind) }
    }

    private fun generateCompareTo(thisKind: PrimitiveType): List<MethodDescription> {
        return buildList {
            for (otherKind in PrimitiveType.onlyNumeric) {
                val doc =
                    "Compares this value with the specified value for order. $END_LINE" +
                            "Returns zero if this value is equal to the specified other value, a negative number if it's less than other, $END_LINE" +
                            "or a positive number if it's greater than other."

                val signature = MethodSignature(
                    isOverride = otherKind == thisKind,
                    isOperator = true,
                    name = "compareTo",
                    arg = MethodParameter("other", otherKind.capitalized),
                    returnType = PrimitiveType.INT.capitalized
                )

                this += MethodDescription(
                    doc = doc,
                    annotations = mutableListOf("kotlin.internal.IntrinsicConstEvaluation"),
                    signature = signature
                ).apply { this.modifyGeneratedCompareTo(thisKind, otherKind) }
            }
        }
    }

    private fun generateBinaryOperators(thisKind: PrimitiveType): List<MethodDescription> {
        return buildList {
            for (name in binaryOperators) {
                this += generateOperator(name, thisKind)
            }
        }
    }

    private fun generateOperator(name: String, thisKind: PrimitiveType): List<MethodDescription> {
        return buildList {
            for (otherKind in PrimitiveType.onlyNumeric) {
                val returnType = getOperatorReturnType(thisKind, otherKind)

                val annotations = buildList {
                    if (name == "rem") add("SinceKotlin(\"1.1\")")
                    add("kotlin.internal.IntrinsicConstEvaluation")
                }

                this += MethodDescription(
                    doc = binaryOperatorDoc(name, thisKind, otherKind),
                    annotations = annotations.toMutableList(),
                    signature = MethodSignature(
                        isOperator = true,
                        name = name,
                        arg = MethodParameter("other", otherKind.capitalized),
                        returnType = returnType.capitalized
                    )
                ).apply { this.modifyGeneratedBinaryOperation(thisKind, otherKind) }
            }
        }
    }

    private fun generateUnaryOperators(thisKind: PrimitiveType): List<MethodDescription> {
        return buildList {
            for (name in listOf("inc", "dec")) {
                this += MethodDescription(
                    doc = incDecOperatorsDoc(name),
                    signature = MethodSignature(isOperator = true, name = name, arg = null, returnType = thisKind.capitalized)
                ).apply { this.modifyGeneratedUnaryOperation(thisKind) }
            }

            for ((name, doc) in unaryPlusMinusOperators) {
                val returnType = when (thisKind) {
                    in listOf(PrimitiveType.SHORT, PrimitiveType.BYTE, PrimitiveType.CHAR) -> PrimitiveType.INT.capitalized
                    else -> thisKind.capitalized
                }
                this += MethodDescription(
                    doc = doc,
                    annotations = mutableListOf("kotlin.internal.IntrinsicConstEvaluation"),
                    signature = MethodSignature(isOperator = true, name = name, arg = null, returnType = returnType)
                ).apply { this.modifyGeneratedUnaryOperation(thisKind) }
            }
        }
    }

    private fun generateRangeTo(thisKind: PrimitiveType): List<MethodDescription> {
        return buildList {
            for (otherKind in PrimitiveType.onlyNumeric) {
                val returnType = maxByDomainCapacity(maxByDomainCapacity(thisKind, otherKind), PrimitiveType.INT)

                if (returnType == PrimitiveType.DOUBLE || returnType == PrimitiveType.FLOAT) {
                    continue
                }

                this += MethodDescription(
                    doc = "Creates a range from this value to the specified [other] value.",
                    signature = MethodSignature(
                        isOperator = true,
                        name = "rangeTo",
                        arg = MethodParameter("other", otherKind.capitalized),
                        returnType = "${returnType.capitalized}Range"
                    )
                ).apply { this.modifyGeneratedRangeTo(thisKind) }
            }
        }
    }

    private fun generateRangeUntil(thisKind: PrimitiveType): List<MethodDescription> {
        return buildList {
            for (otherKind in PrimitiveType.onlyNumeric) {
                val returnType = maxByDomainCapacity(maxByDomainCapacity(thisKind, otherKind), PrimitiveType.INT)

                if (returnType == PrimitiveType.DOUBLE || returnType == PrimitiveType.FLOAT) {
                    continue
                }

                this += MethodDescription(
                    doc = """
                        Creates a range from this value up to but excluding the specified [other] value.
                        
                        If the [other] value is less than or equal to `this` value, then the returned range is empty.
                    """.trimIndent(),
                    annotations = mutableListOf("SinceKotlin(\"1.7\")", "ExperimentalStdlibApi"),
                    signature = MethodSignature(
                        isOperator = true,
                        name = "rangeUntil",
                        arg = MethodParameter("other", otherKind.capitalized),
                        returnType = "${returnType.capitalized}Range"
                    )
                ).apply { this.modifyGeneratedRangeUntil(thisKind) }
            }
        }
    }

    private fun generateBitShiftOperators(thisKind: PrimitiveType): List<MethodDescription> {
        return buildList {
            val className = thisKind.capitalized
            val detail = shiftOperatorsDocDetail(thisKind)
            for ((name, doc) in shiftOperators) {
                this += MethodDescription(
                    doc = doc + END_LINE + END_LINE + detail,
                    annotations = mutableListOf("kotlin.internal.IntrinsicConstEvaluation"),
                    signature = MethodSignature(
                        isInfix = true,
                        name = name,
                        arg = MethodParameter("bitCount", PrimitiveType.INT.capitalized),
                        returnType = className
                    )
                ).apply { this.modifyGeneratedBitShiftOperators(thisKind) }
            }
        }
    }

    private fun generateBitwiseOperators(thisKind: PrimitiveType): List<MethodDescription> {
        return buildList {
            for ((name, doc) in bitwiseOperators) {
                this += MethodDescription(
                    doc = doc,
                    annotations = mutableListOf("kotlin.internal.IntrinsicConstEvaluation"),
                    signature = MethodSignature(
                        isInfix = true,
                        name = name,
                        arg = MethodParameter("other", thisKind.capitalized),
                        returnType = thisKind.capitalized
                    )
                ).apply { this.modifyGeneratedBitwiseOperators(thisKind) }
            }

            this += MethodDescription(
                doc = "Inverts the bits in this value.",
                annotations = mutableListOf("kotlin.internal.IntrinsicConstEvaluation"),
                signature = MethodSignature(
                    name = "inv",
                    arg = null,
                    returnType = thisKind.capitalized
                )
            ).apply { this.modifyGeneratedBitwiseOperators(thisKind) }
        }
    }

    private fun generateConversions(thisKind: PrimitiveType): List<MethodDescription> {
        fun isFpToIntConversionDeprecated(otherKind: PrimitiveType): Boolean {
            return thisKind in PrimitiveType.floatingPoint && otherKind in listOf(PrimitiveType.BYTE, PrimitiveType.SHORT)
        }

        fun isCharConversionDeprecated(otherKind: PrimitiveType): Boolean {
            return thisKind != PrimitiveType.INT && otherKind == PrimitiveType.CHAR
        }

        return buildList {
            val thisName = thisKind.capitalized
            for (otherKind in PrimitiveType.exceptBoolean) {
                val otherName = otherKind.capitalized
                val doc = if (thisKind == otherKind) {
                    "Returns this value."
                } else {
                    val detail = if (thisKind in PrimitiveType.integral) {
                        if (otherKind.isIntegral) {
                            docForConversionFromIntegralToIntegral(thisKind, otherKind)
                        } else {
                            docForConversionFromIntegralToFloating(thisKind, otherKind)
                        }
                    } else {
                        if (otherKind.isIntegral) {
                            docForConversionFromFloatingToIntegral(thisKind, otherKind)
                        } else {
                            docForConversionFromFloatingToFloating(thisKind, otherKind)
                        }
                    }

                    "Converts this [$thisName] value to [$otherName].$END_LINE$END_LINE" + detail
                }

                val annotations = mutableListOf<String>()
                if (isFpToIntConversionDeprecated(otherKind)) {
                    annotations += "Deprecated(\"Unclear conversion. To achieve the same result convert to Int explicitly and then to $otherName.\", ReplaceWith(\"toInt().to$otherName()\"))"
                    annotations += "DeprecatedSinceKotlin(warningSince = \"1.3\", errorSince = \"1.5\")"
                }
                if (isCharConversionDeprecated(otherKind)) {
                    annotations += "Deprecated(\"Direct conversion to Char is deprecated. Use toInt().toChar() or Char constructor instead.\", ReplaceWith(\"this.toInt().toChar()\"))"
                    annotations += "DeprecatedSinceKotlin(warningSince = \"1.5\")"
                }

                annotations += "kotlin.internal.IntrinsicConstEvaluation"
                this += MethodDescription(
                    doc = doc,
                    annotations = annotations,
                    signature = MethodSignature(
                        isOverride = true,
                        name = "to$otherName",
                        arg = null,
                        returnType = otherName
                    )
                ).apply { this.modifyGeneratedConversions(thisKind) }
            }
        }
    }

    private fun generateEquals(thisKind: PrimitiveType): MethodDescription {
        return MethodDescription(
            doc = null,
            annotations = mutableListOf("kotlin.internal.IntrinsicConstEvaluation"),
            signature = MethodSignature(
                isOverride = true,
                name = "equals",
                arg = MethodParameter("other", "Any?"),
                returnType = "Boolean"
            )
        ).apply { this.modifyGeneratedEquals(thisKind) }
    }

    private fun generateToString(thisKind: PrimitiveType): MethodDescription {
        return MethodDescription(
            doc = null,
            annotations = mutableListOf("kotlin.internal.IntrinsicConstEvaluation"),
            signature = MethodSignature(
                isOverride = true,
                name = "toString",
                arg = null,
                returnType = "String"
            )
        ).apply { modifyGeneratedToString(thisKind) }
    }

    open fun FileDescription.modifyGeneratedFile() {}
    open fun ClassDescription.modifyGeneratedClass(thisKind: PrimitiveType) {}
    open fun CompanionObjectDescription.modifyGeneratedCompanionObject(thisKind: PrimitiveType) {}
    open fun PropertyDescription.modifyGeneratedCompanionObjectProperty(thisKind: PrimitiveType) {}
    open fun MethodDescription.modifyGeneratedCompareTo(thisKind: PrimitiveType, otherKind: PrimitiveType) {}
    open fun MethodDescription.modifyGeneratedBinaryOperation(thisKind: PrimitiveType, otherKind: PrimitiveType) {}
    open fun MethodDescription.modifyGeneratedUnaryOperation(thisKind: PrimitiveType) {}
    open fun MethodDescription.modifyGeneratedRangeTo(thisKind: PrimitiveType) {}
    open fun MethodDescription.modifyGeneratedRangeUntil(thisKind: PrimitiveType) {}
    open fun MethodDescription.modifyGeneratedBitShiftOperators(thisKind: PrimitiveType) {}
    open fun MethodDescription.modifyGeneratedBitwiseOperators(thisKind: PrimitiveType) {}
    open fun MethodDescription.modifyGeneratedConversions(thisKind: PrimitiveType) {}
    open fun MethodDescription.modifyGeneratedEquals(thisKind: PrimitiveType) {}
    open fun MethodDescription.modifyGeneratedToString(thisKind: PrimitiveType) {}
    open fun generateAdditionalMethods(thisKind: PrimitiveType): List<MethodDescription> = emptyList()

    // --- Utils ---
    private fun maxByDomainCapacity(type1: PrimitiveType, type2: PrimitiveType): PrimitiveType {
        return if (type1.ordinal > type2.ordinal) type1 else type2
    }

    private fun getOperatorReturnType(kind1: PrimitiveType, kind2: PrimitiveType): PrimitiveType {
        require(kind1 != PrimitiveType.BOOLEAN) { "kind1 must not be BOOLEAN" }
        require(kind2 != PrimitiveType.BOOLEAN) { "kind2 must not be BOOLEAN" }
        return maxByDomainCapacity(maxByDomainCapacity(kind1, kind2), PrimitiveType.INT)
    }
}

class JvmGenerator : BaseGenerator() {
    override fun ClassDescription.modifyGeneratedClass(thisKind: PrimitiveType) {
        this.addDoc("On the JVM, non-nullable values of this type are represented as values of the primitive type `${thisKind.name.lowercase()}`.")
    }
}

class NativeGenerator : BaseGenerator() {
    override fun FileDescription.modifyGeneratedFile() {
        this.addSuppress("OVERRIDE_BY_INLINE")
        this.addSuppress("NOTHING_TO_INLINE")
        this.addImport("kotlin.native.internal.*")
    }

    override fun ClassDescription.modifyGeneratedClass(thisKind: PrimitiveType) {
        this.isFinal = true
    }

    override fun CompanionObjectDescription.modifyGeneratedCompanionObject(thisKind: PrimitiveType) {
        if (thisKind !in floatingPoint) {
            this.addAnnotation("CanBePrecreated")
        }
    }

    override fun primitiveConstants(type: PrimitiveType): List<Any> {
        return when (type) {
            PrimitiveType.FLOAT -> listOf(
                String.format("%.17eF", java.lang.Float.MIN_VALUE), String.format("%.17eF", java.lang.Float.MAX_VALUE),
                "1.0F/0.0F", "-1.0F/0.0F", "-(0.0F/0.0F)"
            )
            else -> super.primitiveConstants(type)
        }
    }

    override fun PropertyDescription.modifyGeneratedCompanionObjectProperty(thisKind: PrimitiveType) {
        if (this.name in setOf("POSITIVE_INFINITY", "NEGATIVE_INFINITY", "NaN")) {
            this.addAnnotation("Suppress(\"DIVISION_BY_ZERO\")")
        }
    }

    override fun MethodDescription.modifyGeneratedCompareTo(thisKind: PrimitiveType, otherKind: PrimitiveType) {
        if (otherKind == thisKind) {
            if (thisKind in floatingPoint) {
                val argName = this.signature.arg!!.name
                """
                    // if any of values in NaN both comparisons return false
                    if (this > $argName) return 1
                    if (this < $argName) return -1
            
                    val thisBits = this.toBits()
                    val otherBits = $argName.toBits()
            
                    // Canonical NaN bits representation higher than any other bit represent value
                    return thisBits.compareTo(otherBits)
                """.trimIndent().addAsMultiLineBody()
            } else {
                addAnnotation("TypedIntrinsic(IntrinsicType.SIGNED_COMPARE_TO)")
                this.signature.isExternal = true
            }
        } else {
            this.signature.isInline = thisKind !in floatingPoint
            val thisCasted = "this" + thisKind.castToIfNecessary(otherKind)
            val otherCasted = this.signature.arg!!.name + otherKind.castToIfNecessary(thisKind)
            if (thisKind == PrimitiveType.FLOAT && otherKind == PrimitiveType.DOUBLE) {
                "- ${otherCasted}.compareTo(this)".addAsSingleLineBody(bodyOnNewLine = true)
            } else {
                "$thisCasted.compareTo($otherCasted)".addAsSingleLineBody(bodyOnNewLine = true)
            }
        }
    }

    override fun MethodDescription.modifyGeneratedBinaryOperation(thisKind: PrimitiveType, otherKind: PrimitiveType) {
        val sign = when (this.signature.name) {
            "plus" -> "+"
            "minus" -> "-"
            "times" -> "*"
            "div" -> "/"
            "rem" -> "%"
            else -> throw IllegalArgumentException("Unsupported binary operation: ${this.signature.name}")
        }

        if (thisKind != PrimitiveType.BYTE && thisKind != PrimitiveType.SHORT && thisKind == otherKind) {
            this.signature.isExternal = true
            addAnnotation("TypedIntrinsic(IntrinsicType.${this.signature.name.toNativeOperator()})")
            return
        }

        this.signature.isInline = true
        val returnTypeAsPrimitive = PrimitiveType.valueOf(this.signature.returnType.uppercase())
        val thisCasted = "this" + thisKind.castToIfNecessary(returnTypeAsPrimitive)
        val otherCasted = this.signature.arg!!.name + this.signature.arg.getTypeAsPrimitive().castToIfNecessary(returnTypeAsPrimitive)
        "$thisCasted $sign $otherCasted".addAsSingleLineBody(bodyOnNewLine = true)
    }

    override fun MethodDescription.modifyGeneratedUnaryOperation(thisKind: PrimitiveType) {
        if (this.signature.name in setOf("inc", "dec") || thisKind == PrimitiveType.INT ||
            thisKind in floatingPoint || (this.signature.name == "unaryMinus" && thisKind == PrimitiveType.LONG)
        ) {
            this.signature.isExternal = true
            this.addAnnotation("TypedIntrinsic(IntrinsicType.${this.signature.name.toNativeOperator()})")
        } else {
            this.signature.isInline = true
            val returnTypeAsPrimitive = PrimitiveType.valueOf(this.signature.returnType.uppercase())
            val thisCasted = "this" + thisKind.castToIfNecessary(returnTypeAsPrimitive)
            val sign = if (this.signature.name == "unaryMinus") "-" else ""
            "$sign$thisCasted".addAsSingleLineBody(bodyOnNewLine = true)
        }
    }

    override fun MethodDescription.modifyGeneratedRangeTo(thisKind: PrimitiveType) {
        val rangeType = PrimitiveType.valueOf(this.signature.returnType.replace("Range", "").uppercase())
        val thisCasted = "this" + thisKind.castToIfNecessary(rangeType)
        val otherCasted = this.signature.arg!!.name + this.signature.arg.getTypeAsPrimitive().castToIfNecessary(rangeType)
        "return ${this.signature.returnType}($thisCasted, $otherCasted)".addAsMultiLineBody()
    }

    override fun MethodDescription.modifyGeneratedRangeUntil(thisKind: PrimitiveType) {
        "this until ${this.signature.arg!!.name}".addAsSingleLineBody(bodyOnNewLine = false)
    }

    override fun MethodDescription.modifyGeneratedBitShiftOperators(thisKind: PrimitiveType) {
        this.signature.isExternal = true
        this.addAnnotation("TypedIntrinsic(IntrinsicType.${this.signature.name.toNativeOperator()})")
    }

    override fun MethodDescription.modifyGeneratedBitwiseOperators(thisKind: PrimitiveType) {
        this.signature.isExternal = true
        this.addAnnotation("TypedIntrinsic(IntrinsicType.${this.signature.name.toNativeOperator()})")
    }

    override fun MethodDescription.modifyGeneratedConversions(thisKind: PrimitiveType) {
        val returnTypeAsPrimitive = PrimitiveType.valueOf(this.signature.returnType.uppercase())
        if (returnTypeAsPrimitive == thisKind) {
            this.signature.isInline = true
            "this".addAsSingleLineBody(bodyOnNewLine = true)
        } else if (thisKind !in floatingPoint) {
            this.signature.isExternal = true
            val intrinsicType = when {
                returnTypeAsPrimitive in floatingPoint -> "SIGNED_TO_FLOAT"
                returnTypeAsPrimitive.byteSize < thisKind.byteSize -> "INT_TRUNCATE"
                returnTypeAsPrimitive.byteSize > thisKind.byteSize -> "SIGN_EXTEND"
                else -> "ZERO_EXTEND"
            }
            this.addAnnotation("TypedIntrinsic(IntrinsicType.$intrinsicType)")
        } else {
            if (returnTypeAsPrimitive in setOf(PrimitiveType.BYTE, PrimitiveType.SHORT, PrimitiveType.CHAR)) {
                "this.toInt().to${this.signature.returnType}()".addAsSingleLineBody(bodyOnNewLine = false)
                return
            }

            this.signature.isExternal = true
            if (returnTypeAsPrimitive in setOf(PrimitiveType.INT, PrimitiveType.LONG)) {
                this.addAnnotation("GCUnsafeCall(\"Kotlin_${thisKind.capitalized}_to${this.signature.returnType}\")")
            } else if (thisKind.byteSize > returnTypeAsPrimitive.byteSize) {
                this.addAnnotation("TypedIntrinsic(IntrinsicType.FLOAT_TRUNCATE)")
            } else if (thisKind.byteSize < returnTypeAsPrimitive.byteSize) {
                this.addAnnotation("TypedIntrinsic(IntrinsicType.FLOAT_EXTEND)")
            }
        }
    }

    override fun MethodDescription.modifyGeneratedEquals(thisKind: PrimitiveType) {
        val argName = this.signature.arg!!.name
        val additionalCheck = if (thisKind in floatingPoint) {
            "this.equals(other)"
        } else {
            "kotlin.native.internal.areEqualByValue(this, $argName)"
        }
        "\t$argName is ${thisKind.capitalized} && $additionalCheck".addAsSingleLineBody(bodyOnNewLine = true)
    }

    override fun MethodDescription.modifyGeneratedToString(thisKind: PrimitiveType) {
        if (thisKind in floatingPoint) {
            "NumberConverter.convert(this)".addAsSingleLineBody(bodyOnNewLine = false)
        } else {
            this.signature.isExternal = true
            this.addAnnotation("GCUnsafeCall(\"Kotlin_${thisKind.capitalized}_toString\")")
        }
    }

    override fun generateAdditionalMethods(thisKind: PrimitiveType): List<MethodDescription> {
        val hashCode = MethodDescription(
            doc = null,
            signature = MethodSignature(
                isOverride = true,
                name = "hashCode",
                arg = null,
                returnType = PrimitiveType.INT.capitalized
            )
        ).apply {
            val calculation = when (thisKind) {
                PrimitiveType.LONG -> "((this ushr 32) xor this).toInt()"
                PrimitiveType.FLOAT -> "toBits()"
                PrimitiveType.DOUBLE -> "toBits().hashCode()"
                else -> "this" + thisKind.castToIfNecessary(PrimitiveType.INT)
            }
            "return $calculation".addAsMultiLineBody()
        }

        val customEquals = MethodDescription(
            doc = null,
            signature = MethodSignature(
                name = "equals",
                arg = MethodParameter("other", thisKind.capitalized),
                returnType = PrimitiveType.BOOLEAN.capitalized
            )
        ).apply {
            when (thisKind) {
                in floatingPoint -> "toBits() == other.toBits()".addAsSingleLineBody(bodyOnNewLine = false)
                else -> "kotlin.native.internal.areEqualByValue(this, other)".addAsSingleLineBody(bodyOnNewLine = false)
            }
        }

        val bits = MethodDescription(
            doc = null,
            signature = MethodSignature(
                isExternal = true,
                visibility = "internal",
                name = "bits",
                arg = null,
                returnType = if (thisKind == PrimitiveType.FLOAT) PrimitiveType.INT.capitalized else PrimitiveType.LONG.capitalized
            )
        ).apply {
            this.addAnnotation("TypedIntrinsic(IntrinsicType.REINTERPRET)")
            this.addAnnotation("PublishedApi")
        }

        return if (thisKind in floatingPoint) {
            listOf(customEquals, hashCode, bits)
        } else {
            listOf(customEquals, hashCode)
        }
    }

    companion object {
        private fun String.toNativeOperator(): String {
            if (this == "div" || this == "rem") return "SIGNED_${this.uppercase(Locale.getDefault())}"
            if (this.startsWith("unary")) return "UNARY_${this.replace("unary", "").uppercase(Locale.getDefault())}"
            return this.uppercase(Locale.getDefault())
        }

        private fun PrimitiveType.castToIfNecessary(otherType: PrimitiveType): String {
            if (this !in PrimitiveType.onlyNumeric || otherType !in PrimitiveType.onlyNumeric) {
                throw IllegalArgumentException("Cannot cast to non-numeric type")
            }

            if (this == otherType) return ""

            if (this.ordinal < otherType.ordinal) {
                return ".to${otherType.capitalized}()"
            }

            return ""
        }
    }
}

fun main() {
    val primitivesFile = File(BUILT_INS_NATIVE_DIR, "kotlin/Primitives_new.kt")
    primitivesFile.parentFile?.mkdirs()
    PrintWriter(primitivesFile).use {
        it.print(JvmGenerator().generate())
    }

    val nativePrimitivesFile = File("kotlin-native/runtime/src/main/kotlin/kotlin/Primitives_new_native.kt")
    nativePrimitivesFile.parentFile?.mkdirs()
    PrintWriter(nativePrimitivesFile).use {
        it.print(NativeGenerator().generate())
    }
}
