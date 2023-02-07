// WITH_STDLIB
// TARGET_BACKEND: JVM_IR

// FILE: KotlinParent.kt
open class KotlinParent {
    fun printSomething(type: InlineType) {
        println("Printed: $type")
    }

    @JvmInline
    value class InlineType(val id: Int)
}

// FILE: JavaChild.java

class JavaChild extends KotlinParent {}


// FILE: box.kt
fun box(): String {
    JavaChild().printSomething(KotlinParent.InlineType(1))
    return "OK"
}