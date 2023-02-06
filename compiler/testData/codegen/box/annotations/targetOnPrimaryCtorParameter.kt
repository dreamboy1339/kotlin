// WITH_STDLIB
// WITH_REFLECT
// TARGET_BACKEND: JVM_IR
// FIR_DUMP
// DUMP_IR

import kotlin.reflect.full.declaredMemberProperties

annotation class NoTarget

@Target(AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD)
annotation class PropValueField

@Target(AnnotationTarget.PROPERTY)
annotation class PropertyOnly

@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class ParameterOnly

@Target(AnnotationTarget.FIELD)
annotation class FieldOnly

class Foo(
    @NoTarget
    @PropValueField
    @PropertyOnly
    @ParameterOnly
    @FieldOnly
    var param: Int
)

fun box(): String {
    val clazz = Foo::class

    val parameterAnnotations = clazz.constructors.single().parameters.single().annotations.joinToString()
    val propertyAnnotations = clazz.declaredMemberProperties.single().annotations.joinToString()
    val fieldAnnotations = Foo::class.java.getDeclaredField("param").annotations.joinToString()

    if (parameterAnnotations != "@NoTarget(), @PropValueField(), @ParameterOnly()") return "Parameters:" + parameterAnnotations
    if (propertyAnnotations != "@PropertyOnly()") return "Property:" + propertyAnnotations
    if (fieldAnnotations != "@FieldOnly()") return "Field:" + fieldAnnotations

    return "OK"
}
