import kotlin.coroutines.*

// Auxiliary functions to imitate coroutines.
private fun <R> runCoroutine(coroutine: suspend () -> R): R {
    var coroutineResult: Result<R>? = null

    coroutine.startCoroutine(Continuation(EmptyCoroutineContext) { result ->
        coroutineResult = result
    })

    return (coroutineResult ?: error("Coroutine finished without any result")).getOrThrow()
}

fun memberOperatorsToNonOperators(vararg pairs: Pair<String, String>): String {
    check(pairs.isNotEmpty())
    val instance = OperatorsToNonOperators(Cache())
    pairs.forEach { (key, value) ->
        instance[key] = value // set
    }
    pairs.forEach { (key, value) ->
        check(instance[key] == value) // get
    }
    return "memberOperatorsToNonOperators: " + instance() // invoke
}

fun extensionOperatorsToNonOperators(vararg pairs: Pair<String, String>): String = with(OperatorsToNonOperators.Companion) {
    check(pairs.isNotEmpty())
    val cache = Cache()
    pairs.forEach { (key, value) ->
        cache[key] = value // set
    }
    pairs.forEach { (key, value) ->
        check(cache[key] == value) // get
    }
    return "extensionOperatorsToNonOperators: " + cache() // invoke
}

fun memberNonOperatorsToOperators(vararg pairs: Pair<String, String>): String {
    check(pairs.isNotEmpty())
    val instance = NonOperatorsToOperators(Cache())
    pairs.forEach { (key, value) ->
        instance.set(key, value) // set
    }
    pairs.forEach { (key, value) ->
        check(instance.get(key) == value) // get
    }
    return "memberNonOperatorsToOperators: " + instance.invoke() // invoke
}

fun extensionNonOperatorsToOperators(vararg pairs: Pair<String, String>): String = with(NonOperatorsToOperators.Companion) {
    check(pairs.isNotEmpty())
    val cache = Cache()
    pairs.forEach { (key, value) ->
        cache.set(key, value) // set
    }
    pairs.forEach { (key, value) ->
        check(cache.get(key) == value) // get
    }
    return "extensionNonOperatorsToOperators: " + cache.invoke() // invoke
}

fun memberNonInfixToInfix(a: Int, b: Int): Int = a.wrap().memberNonInfixToInfix(b.wrap()).unwrap()
fun extensionNonInfixToInfix(a: Int, b: Int): Int = with(Wrapper.Companion) { a.wrap().extensionNonInfixToInfix(b.wrap()).unwrap() }
fun memberInfixToNonInfix(a: Int, b: Int): Int = (a.wrap() memberInfixToNonInfix b.wrap()).unwrap()
fun extensionInfixToNonInfix(a: Int, b: Int): Int = with(Wrapper.Companion) { (a.wrap() extensionInfixToNonInfix b.wrap()).unwrap() }

fun nonTailrecToTailrec(n: Int): Int = Functions.nonTailrecToTailrec(n, 1)
tailrec fun tailrecToNonTailrec(n: Int): Int = Functions.tailrecToNonTailrec(n, 1)

fun removedDefaultValueInFunction(n: Int): Int = Functions.removedDefaultValue(n)
fun removedDefaultValueInConstructor(n: Int): Int = RemovedDefaultValueInConstructor(n).value

fun suspendToNonSuspendFunction1(x: Int): Int = runCoroutine { Functions.suspendToNonSuspendFunction(x) }
fun suspendToNonSuspendFunction2(x: Int): Int = runCoroutine { Functions.wrapCoroutine { Functions.suspendToNonSuspendFunction(x) } }
fun nonSuspendToSuspendFunction1(x: Int): Int = Functions.nonSuspendToSuspendFunction(x)
fun nonSuspendToSuspendFunction2(x: Int): Int = runCoroutine { Functions.nonSuspendToSuspendFunction(x) }
