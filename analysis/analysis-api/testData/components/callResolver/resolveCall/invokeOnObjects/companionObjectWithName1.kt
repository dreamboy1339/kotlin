class Foo private constructor() {
    companion object Helper {
        operator fun invoke() {}
    }
}

fun test() {
    Foo.<expr>Helper</expr>()
}