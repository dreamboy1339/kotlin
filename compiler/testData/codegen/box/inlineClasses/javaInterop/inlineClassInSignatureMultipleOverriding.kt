// WITH_STDLIB
// TARGET_BACKEND: JVM_IR

// FILE: KotlinClass.kt

open class KotlinClass {
    fun foo(x: UInt) = 42
}

// FILE: JavaClassA.java

public class JavaClassA extends KotlinClass {}

// FILE: JavaClassB.java

public class JavaClassB extends JavaClassA {}

// FILE: JavaInterfaceA.java

import kotlin.UInt;

interface JavaInterfaceA {
    int bar(UInt x);
}

// FILE: JavaInterfaceB.java

import kotlin.UInt;

interface JavaInterfaceB {
    int bar(UInt x);
}

// FILE: KotlinClassChild.kt

class KotlinClassChild : JavaInterfaceA, JavaInterfaceB {
    override fun bar(x: UInt) = 24
}

// FILE: box.kt

fun box(): String {

    if (JavaClassA().foo(0.toUInt()) != 42) return "Fail 1"
    if (JavaClassB().foo(0.toUInt()) != 42) return "Fail 2"
    if (KotlinClassChild().bar(0.toUInt()) != 24) return "Fail 3"

    return "OK"
}