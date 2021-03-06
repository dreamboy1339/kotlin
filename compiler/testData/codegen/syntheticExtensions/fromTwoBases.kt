// DONT_TARGET_EXACT_BACKEND: JS JS_IR JS_IR_ES6 WASM NATIVE
// MODULE: lib
// FILE: C.java

interface A {
    String getOk();
}

interface B {
    String getOk();
}

interface C extends A, B {
}

// FILE: JavaClass.java

class JavaClass implements C {
    public String getOk() { return "OK"; }
}

// MODULE: main(lib)
// FILE: 1.kt

fun box(): String {
    return f(JavaClass())
}

internal fun f(c: C) = c.ok
