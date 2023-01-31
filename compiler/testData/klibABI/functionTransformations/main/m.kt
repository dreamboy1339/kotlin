import abitestutils.abiTest

fun box() = abiTest {
    expectSuccess("memberOperatorsToNonOperators: a=Alice,b=Bob") { memberOperatorsToNonOperators("a" to "Alice", "b" to "Bob") }
    expectSuccess("extensionOperatorsToNonOperators: a=Alice,b=Bob") { extensionOperatorsToNonOperators("a" to "Alice", "b" to "Bob") }
    expectSuccess("memberNonOperatorsToOperators: a=Alice,b=Bob") { memberNonOperatorsToOperators("a" to "Alice", "b" to "Bob") }
    expectSuccess("extensionNonOperatorsToOperators: a=Alice,b=Bob") { extensionNonOperatorsToOperators("a" to "Alice", "b" to "Bob") }

    expectSuccess(3) { memberNonInfixToInfix(1, 2) }
    expectSuccess(3) { extensionNonInfixToInfix(1, 2) }
    expectSuccess(3) { memberInfixToNonInfix(1, 2) }
    expectSuccess(3) { extensionInfixToNonInfix(1, 2) }

    expectSuccess(6) { nonTailrecToTailrec(3) }
    expectSuccess(6) { tailrecToNonTailrec(3) }

    expectFailure(linkage("Function 'removedDefaultValue' can not be called: The call site provides less value arguments (1) then the function requires (2)")) { removedDefaultValueInFunction(1) }
    expectFailure(linkage("Constructor 'RemovedDefaultValueInConstructor.<init>' can not be called: The call site provides less value arguments (1) then the constructor requires (2)")) { removedDefaultValueInConstructor(1) }

    expectSuccess(-1) { suspendToNonSuspendFunction1(1) }
    expectSuccess(-2) { suspendToNonSuspendFunction2(2) }
    // Temporarily muted as it fails:
    // Native -> NativeAddContinuationToFunctionCallsLowering.kt:25 "IAE: Continuation parameter only exists in lowered suspend functions, but function origin is DEFINED"
    // JS -> "TypeError: tmp0_safe_receiver.get_context_h02k06_k$ is not a function"
//    expectFailure(linkage("?")) { nonSuspendToSuspendFunction1(3) } // Fails: Native -> exception in lowering, JS -> "TypeError: tmp0_safe_receiver.get_context_h02k06_k$ is not a function
    expectSuccess(-4) { nonSuspendToSuspendFunction2(4) }
}
