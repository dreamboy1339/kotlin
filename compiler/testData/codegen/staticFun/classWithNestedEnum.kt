// DONT_TARGET_EXACT_BACKEND: JS JS_IR JS_IR_ES6 WASM NATIVE
// MODULE: lib
// FILE: test/JavaClass.java

package test;

public class JavaClass {
    public enum E { ENTRY }
    
    public static String foo() { return "OK"; }
}

// MODULE: main(lib)
// FILE: 1.kt

package test

fun box(): String {
    return JavaClass.foo()!!
}
