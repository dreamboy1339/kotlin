// WITH_STDLIB
// TARGET_BACKEND: JVM_IR
// CHECK_BYTECODE_LISTING

interface I {
    fun y(another: Example): Example
}

@JvmInline
@JvmExposeBoxed
value class Example(val s: String) : I {
    constructor(i: Int) : this(i.toString())

    init {
        println("beb")
    }

    fun x(): Unit = println(s)
    override fun y(another: Example): Example = Example(s + another.s)
}

fun y2(e: Example): Example = e.y(e)

fun box(): String {
    return "OK"
}
