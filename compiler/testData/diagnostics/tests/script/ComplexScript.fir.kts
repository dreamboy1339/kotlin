// !DIAGNOSICS: +UNUSED_PARAMETER

fun foo(x: Int) = 1

val y = 2

<!INFERENCE_ERROR!>foo(<!INFERENCE_ERROR!>y<!>)<!>
