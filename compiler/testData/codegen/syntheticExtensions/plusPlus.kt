// DONT_TARGET_EXACT_BACKEND: JS JS_IR JS_IR_ES6 WASM NATIVE
// MODULE: lib
// FILE: JavaClass.java

class JavaClass {
    private int myX = 0;

    public int getX() {
        return myX;
    }

    public void setX(int x) {
        myX = x;
    }
}

// MODULE: main(lib)
// FILE: 1.kt

fun box(): String {
    val javaClass = JavaClass()
    javaClass.x++
    return if (javaClass.x == 1) "OK" else "ERROR"
}
