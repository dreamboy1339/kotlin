package foo.bar.baz

object Foo {
    object Helper {
        operator fun invoke() {}
    }
}

fun test() {
    foo.bar.baz.Foo.He<caret>lper()
}
