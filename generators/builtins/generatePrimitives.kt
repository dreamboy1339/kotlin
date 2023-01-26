/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

import org.jetbrains.kotlin.generators.builtins.PrimitiveType
import org.jetbrains.kotlin.generators.builtins.generateBuiltIns.BUILT_INS_NATIVE_DIR
import java.io.File
import java.io.PrintWriter
import java.util.*

private val END_LINE = System.lineSeparator()

private fun String.shift(): String {
    return this.split(END_LINE).joinToString(separator = END_LINE) { "\t$it" }
}

private fun String.printAsDoc(): String {
    if (this.contains(END_LINE)) {
        return this.split(END_LINE)
            .joinToString(separator = END_LINE, prefix = "/**$END_LINE", postfix = "$END_LINE */") { " * $it" }
    }
    return "/** $this */"
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

            appendLine(classes.joinToString(separator = END_LINE))
        }
    }
}

data class ClassDescription(
    private var doc: String,
    private val annotations: MutableList<String>,
    var isFinal: Boolean = false,
    val name: String,
    val companionObject: CompanionObjectDescription, val methods: List<MethodDescription>
) {
    fun addDoc(doc: String) {
        this.doc += "$END_LINE$doc"
    }

    fun addAnnotation(annotation: String) {
        annotations += annotation
    }

    override fun toString(): String {
        return buildString {
            appendLine(doc.printAsDoc())
            if (annotations.isNotEmpty()) {
                appendLine(annotations.joinToString(separator = END_LINE) { "@$it" })
            }

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
    private val annotations: MutableList<String> = mutableListOf(), val properties: List<PropertyDescription>
) {
    fun addAnnotation(annotation: String) {
        annotations += annotation
    }

    override fun toString(): String {
        return buildString {
            if (annotations.isNotEmpty()) {
                appendLine(annotations.joinToString(separator = END_LINE) { "@$it" })
            }

            appendLine("companion object {")
            appendLine(properties.joinToString(separator = END_LINE + END_LINE) { it.toString().shift() })
            appendLine("}")
        }
    }
}

data class MethodSignature(
    var isExternal: Boolean = false,
    val visibility: String = "public",
    var isOverride: Boolean = false,
    var isInline: Boolean = false,
    var isOperator: Boolean = true,
    val name: String, val arg: MethodParameter?, val returnType: String
) {
    override fun toString(): String {
        return buildString {
            if (isExternal) append("external ")
            append("$visibility ")
            if (isOverride) append("override ")
            if (isInline) append("inline ")
            if (isOperator) append("operator ")
            append("fun $name(${arg ?: ""}): $returnType")
        }
    }
}

data class MethodParameter(val name: String, val type: PrimitiveType) {
    override fun toString(): String {
        return "$name: ${type.capitalized}"
    }
}

data class MethodDescription(
    private var doc: String,
    private val annotations: MutableList<String> = mutableListOf(),
    val signature: MethodSignature,
    var body: String? = null
) {
    fun addDoc(doc: String) {
        this.doc += doc
    }

    fun addAnnotation(annotation: String) {
        annotations += annotation
    }

    override fun toString(): String {
        return buildString {
            appendLine(doc.printAsDoc())
            if (annotations.isNotEmpty()) {
                appendLine(annotations.joinToString(separator = END_LINE) { "@$it" })
            }
            append(signature)
            append(body ?: "") // TODO multi/single line body
        }
    }
}

data class PropertyDescription(
    val doc: String, val annotations: List<String> = emptyList(), val name: String, val type: String, val value: String
) {
    override fun toString(): String {
        return buildString {
            appendLine(doc.printAsDoc())
            if (annotations.isNotEmpty()) {
                appendLine(annotations.joinToString(separator = END_LINE) { "@$it" })
            }
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
            "unaryMinus" to "Returns the negative of this value.")
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
                * Note that only the $bitsUsed lowest-order bits of the [bitCount] are used as the shift distance.
                * The shift distance actually used is therefore always in the range `0..${kind.bitSize - 1}`.
                """
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

    private fun primitiveConstants(type: PrimitiveType): List<Any> = when (type) {
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
            for (kind in PrimitiveType.onlyNumeric) {
                val className = kind.capitalized
                val doc = generateDoc(kind)

                val properties = buildList {
                    if (kind == PrimitiveType.FLOAT || kind == PrimitiveType.DOUBLE) {
                        val (minValue, maxValue, posInf, negInf, nan) = primitiveConstants(kind)
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

                    if (kind == PrimitiveType.INT || kind == PrimitiveType.LONG || kind == PrimitiveType.SHORT || kind == PrimitiveType.BYTE) {
                        val (minValue, maxValue) = primitiveConstants(kind)
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

                    val sizeSince = if (kind.isFloatingPoint) "1.4" else "1.3"
                    this += PropertyDescription(
                        doc = "The number of bytes used to represent an instance of $className in a binary form.",
                        listOf("SinceKotlin(\"$sizeSince\")"),
                        name = "SIZE_BYTES",
                        type = "Int",
                        value = kind.byteSize.toString()
                    )

                    this += PropertyDescription(
                        doc = "The number of bits used to represent an instance of $className in a binary form.",
                        listOf("SinceKotlin(\"$sizeSince\")"),
                        name = "SIZE_BITS",
                        type = "Int",
                        value = kind.bitSize.toString()
                    )
                }

                val methods = buildList {
                    this.addAll(generateCompareTo(kind))
                    this.addAll(generateBinaryOperators(kind))
                    this.addAll(generateUnaryOperators(kind))
                }

//                generateRangeTo(kind)
//                generateRangeUntil(kind)

//            if (kind == PrimitiveType.INT || kind == PrimitiveType.LONG) {
//                generateBitShiftOperators(kind)
//            }
//            if (kind == PrimitiveType.INT || kind == PrimitiveType.LONG /* || kind == PrimitiveType.BYTE || kind == PrimitiveType.SHORT */) {
//                generateBitwiseOperators(className, since = if (kind == PrimitiveType.BYTE || kind == PrimitiveType.SHORT) "1.1" else null)
//            }
//
//            generateConversions(kind)
//            generateEquals()
//            generateToString()

                this += ClassDescription(
                    doc,
                    annotations = mutableListOf(),
                    name = className,
                    companionObject = CompanionObjectDescription(properties = properties),
                    methods = methods
                ).apply { this.modifyGeneratedClass(kind) }
            }
        }
    }

    private fun generateDoc(kind: PrimitiveType): String {
        return "Represents a ${typeDescriptions[kind]}."
    }

    private fun generateCompareTo(kind: PrimitiveType): List<MethodDescription> {
        return buildList {
            for (otherKind in PrimitiveType.onlyNumeric) {
                val doc =
                    "Compares this value with the specified value for order. $END_LINE" +
                            "Returns zero if this value is equal to the specified other value, a negative number if it's less than other, $END_LINE" +
                            "or a positive number if it's greater than other."

                val signature = MethodSignature(
                    isOverride = otherKind == kind,
                    name = "compareTo",
                    arg = MethodParameter("other", otherKind),
                    returnType = "Int"
                )

                this += MethodDescription(
                    doc = doc,
                    annotations = mutableListOf("kotlin.internal.IntrinsicConstEvaluation"),
                    signature = signature
                ).apply { this.modifyGeneratedCompareTo(kind, otherKind) }
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
                        name = name,
                        arg = MethodParameter("other", otherKind),
                        returnType = returnType.capitalized
                    )
                ).apply { this.modifyGeneratedBinaryOperation(thisKind, otherKind) }
            }
        }
    }

    private fun generateUnaryOperators(kind: PrimitiveType): List<MethodDescription> {
        return buildList {
            for (name in listOf("inc", "dec")) {
                this += MethodDescription(
                    doc = incDecOperatorsDoc(name),
                    signature = MethodSignature(name = name, arg = null, returnType = kind.capitalized)
                )
            }

            for ((name, doc) in unaryPlusMinusOperators) {
                val returnType = if (kind in listOf(PrimitiveType.SHORT, PrimitiveType.BYTE, PrimitiveType.CHAR)) "Int" else kind.capitalized
                this += MethodDescription(
                    doc = doc,
                    annotations = mutableListOf("kotlin.internal.IntrinsicConstEvaluation"),
                    signature = MethodSignature(name = name, arg = null, returnType = returnType)
                )
            }
        }
    }

    fun generateRangeTo(kind: PrimitiveType) {}
    fun generateRangeUntil(kind: PrimitiveType) {}

    open fun FileDescription.modifyGeneratedFile() {}
    open fun ClassDescription.modifyGeneratedClass(kind: PrimitiveType) {}
    open fun CompanionObjectDescription.modifyGeneratedCompanionObject(kind: PrimitiveType) {}
    open fun MethodDescription.modifyGeneratedCompareTo(kind: PrimitiveType, otherType: PrimitiveType) {}
    open fun MethodDescription.modifyGeneratedBinaryOperation(kind: PrimitiveType, otherType: PrimitiveType) {}

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
    override fun ClassDescription.modifyGeneratedClass(kind: PrimitiveType) {
        this.addDoc("On the JVM, non-nullable values of this type are represented as values of the primitive type `${kind.name.lowercase()}`.")
    }
}

class NativeGenerator : BaseGenerator() {
    override fun FileDescription.modifyGeneratedFile() {
        this.addSuppress("OVERRIDE_BY_INLINE")
        this.addSuppress("NOTHING_TO_INLINE")
        this.addImport("kotlin.native.internal.*")
    }

    override fun ClassDescription.modifyGeneratedClass(kind: PrimitiveType) {
        this.isFinal = true
    }

    override fun CompanionObjectDescription.modifyGeneratedCompanionObject(kind: PrimitiveType) {
        this.addAnnotation("CanBePrecreated")
    }

    override fun MethodDescription.modifyGeneratedCompareTo(kind: PrimitiveType, otherType: PrimitiveType) {
        if (otherType == kind) {
            addAnnotation("TypedIntrinsic(IntrinsicType.SIGNED_COMPARE_TO)")
            this.signature.isExternal = true
        } else {
            this.signature.isInline = true
            val thisCasted = "this" + kind.castToIfNecessary(otherType)
            val otherCasted = this.signature.arg!!.name + otherType.castToIfNecessary(kind)
            this.body = " = $END_LINE\t$thisCasted.compareTo($otherCasted)"
        }
    }

    override fun MethodDescription.modifyGeneratedBinaryOperation(kind: PrimitiveType, otherType: PrimitiveType) {
        val sign = when (this.signature.name) {
            "plus" -> "+"
            "minus" -> "-"
            "times" -> "*"
            "div" -> "/"
            "rem" -> "%"
            else -> throw IllegalArgumentException("Unsupported binary operation: ${this.signature.name}")
        }

        if (kind != PrimitiveType.BYTE && kind != PrimitiveType.SHORT && kind == otherType) {
            this.signature.isExternal = true
            addAnnotation("TypedIntrinsic(IntrinsicType.${this.signature.name.toNativeOperator()})")
            return
        }

        this.signature.isInline = true
        val returnTypeAsPrimitive = PrimitiveType.valueOf(this.signature.returnType.uppercase())
        val thisCasted = "this" + kind.castToIfNecessary(returnTypeAsPrimitive)
        val otherCasted = this.signature.arg!!.name + this.signature.arg.type.castToIfNecessary(returnTypeAsPrimitive)
        this.body = " = $END_LINE\t$thisCasted $sign $otherCasted"
    }

    companion object {
        private fun String.toNativeOperator(): String {
            if (this == "div" || this == "rem") return "SIGNED_${this.uppercase(Locale.getDefault())}"
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
