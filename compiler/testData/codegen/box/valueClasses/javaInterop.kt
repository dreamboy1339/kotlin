// WITH_STDLIB
// TARGET_BACKEND: JVM_IR
// LANGUAGE: +ValueClasses

// FILE: Point.kt

@JvmInline
value class Point(val x: Int, val y: Int)

// FILE: KotlinClass.kt

open class KotlinClass {
    fun foo(x: Point) = 42
}

// FILE: JavaClassA.java

public class JavaClassA extends KotlinClass {}

// FILE: JavaClassB.java

public class JavaClassB extends JavaClassA {}

// FILE: JavaInterfaceA.java

interface JavaInterfaceA {
    int bar(Point x);
}

// FILE: JavaInterfaceB.java

interface JavaInterfaceB {
    int bar(Point x);
}

// FILE: KotlinClassChild.kt

class KotlinClassChild : JavaInterfaceA, JavaInterfaceB {
    override fun bar(x: Point) = 24
}

// FILE: box.kt

fun box(): String {

    if (JavaClassA().foo(Point(0, 0)) != 42) return "Fail 1"
    if (JavaClassB().foo(Point(0, 0)) != 42) return "Fail 2"
    if (KotlinClassChild().bar(Point(0, 0)) != 24) return "Fail 3"

    return "OK"
}