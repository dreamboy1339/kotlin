import abitestutils.abiTest
import abitestutils.TestMode.NATIVE_CACHE_STATIC_EVERYWHERE

fun box() = abiTest {
    /**
     * `fun foo(): A.B`: In older version of the library `A` and `B` were classes. In newer version `B` is an entry of enum `A`.
     *
     * When no lazy IR is used, [IrCall.symbol.owner] is a function (most likely [IrFunctionImpl]) which has the stub [IrClass] (the one
     * generated by [MissingDeclarationStubGenerator]) with "/A.B|null[0]" signature in the return type. So the function is formally
     * detected as "referecing an unbound CLASS symbol" and the appropriate error message is constructed.
     *
     * When lazy IR is used, [IrCall.symbol.owner] is a lazy-IR function with the return type holding the lazy-IR class
     * generated from [EnumEntrySyntheticClassDescriptor] for the enum entry and having a different signature: "/A.B.<EEC>|null[0]".
     * This class is not detected as unbound, and the whole function is not considered as partially linked. But the [IrCall]
     * still has its own expression type recorded that is deserialized without lazy-IR-distortion, so the [IrCall] expression
     * is detected as "using an unbound CLASS symbol" with "/A.B|null[0]" signature.
     *
     * The [adjustForLazyIr] function is used to adjust tested error messages depending on whether lazy IR is used or not.
     */
    fun adjustForLazyIr(declaration: String) = if (testMode == NATIVE_CACHE_STATIC_EVERYWHERE) "Expression" else declaration

    expectFailure(linkage("Can not get instance of singleton 'EnumToClass.Foo': No enum entry found for symbol '/EnumToClass.Foo'")) { getEnumToClassFoo() }
    expectFailure(linkage("Can not get instance of singleton 'EnumToClass.Foo': No enum entry found for symbol '/EnumToClass.Foo'")) { getEnumToClassFooInline() }
    expectFailure(linkage("Can not get instance of singleton 'EnumToClass.Foo': No enum entry found for symbol '/EnumToClass.Foo'")) { getEnumToClassFooAsAny() }
    expectFailure(linkage("Can not get instance of singleton 'EnumToClass.Foo': No enum entry found for symbol '/EnumToClass.Foo'")) { getEnumToClassFooAsAnyInline() }
    expectFailure(linkage("Can not get instance of singleton 'EnumToClass.Bar': No enum entry found for symbol '/EnumToClass.Bar'")) { getEnumToClassBar() }
    expectFailure(linkage("Can not get instance of singleton 'EnumToClass.Bar': No enum entry found for symbol '/EnumToClass.Bar'")) { getEnumToClassBarInline() }
    expectFailure(linkage("Can not get instance of singleton 'EnumToClass.Bar': No enum entry found for symbol '/EnumToClass.Bar'")) { getEnumToClassBarAsAny() }
    expectFailure(linkage("Can not get instance of singleton 'EnumToClass.Bar': No enum entry found for symbol '/EnumToClass.Bar'")) { getEnumToClassBarAsAnyInline() }
    expectFailure(linkage("Function 'getObjectToEnumFoo' can not be called: ${adjustForLazyIr("Function")} uses unlinked class symbol '/ObjectToEnum.Foo'")) { getObjectToEnumFoo() }
    expectFailure(linkage("Function 'getObjectToEnumFooInline' can not be called: ${adjustForLazyIr("Function")} uses unlinked class symbol '/ObjectToEnum.Foo'")) { getObjectToEnumFooInline() }
    expectFailure(linkage("Constructor 'Foo.<init>' can not be called: No constructor found for symbol '/ObjectToEnum.Foo.<init>'")) { getObjectToEnumFooAsAny() }
    expectFailure(linkage("Constructor 'Foo.<init>' can not be called: No constructor found for symbol '/ObjectToEnum.Foo.<init>'")) { getObjectToEnumFooAsAnyInline() }
    expectFailure(linkage("Function 'getObjectToEnumBar' can not be called: ${adjustForLazyIr("Function")} uses unlinked class symbol '/ObjectToEnum.Bar'")) { getObjectToEnumBar() }
    expectFailure(linkage("Function 'getObjectToEnumBarInline' can not be called: ${adjustForLazyIr("Function")} uses unlinked class symbol '/ObjectToEnum.Bar'")) { getObjectToEnumBarInline() }
    expectFailure(linkage("Can not get instance of singleton 'Bar': No class found for symbol '/ObjectToEnum.Bar'")) { getObjectToEnumBarAsAny() }
    expectFailure(linkage("Can not get instance of singleton 'Bar': No class found for symbol '/ObjectToEnum.Bar'")) { getObjectToEnumBarAsAnyInline() }
    expectFailure(linkage("Function 'getClassToEnumFoo' can not be called: ${adjustForLazyIr("Function")} uses unlinked class symbol '/ClassToEnum.Foo'")) { getClassToEnumFoo() }
    expectFailure(linkage("Function 'getClassToEnumFooInline' can not be called: ${adjustForLazyIr("Function")} uses unlinked class symbol '/ClassToEnum.Foo'")) { getClassToEnumFooInline() }
    expectFailure(linkage("Constructor 'Foo.<init>' can not be called: No constructor found for symbol '/ClassToEnum.Foo.<init>'")) { getClassToEnumFooAsAny() }
    expectFailure(linkage("Constructor 'Foo.<init>' can not be called: No constructor found for symbol '/ClassToEnum.Foo.<init>'")) { getClassToEnumFooAsAnyInline() }
    expectFailure(linkage("Function 'getClassToEnumBar' can not be called: ${adjustForLazyIr("Function")} uses unlinked class symbol '/ClassToEnum.Bar'")) { getClassToEnumBar() }
    expectFailure(linkage("Function 'getClassToEnumBarInline' can not be called: ${adjustForLazyIr("Function")} uses unlinked class symbol '/ClassToEnum.Bar'")) { getClassToEnumBarInline() }
    expectFailure(linkage("Can not get instance of singleton 'Bar': No class found for symbol '/ClassToEnum.Bar'")) { getClassToEnumBarAsAny() }
    expectFailure(linkage("Can not get instance of singleton 'Bar': No class found for symbol '/ClassToEnum.Bar'")) { getClassToEnumBarAsAnyInline() }
    expectFailure(linkage("Function 'getClassToEnumBaz' can not be called: ${adjustForLazyIr("Function")} uses unlinked class symbol '/ClassToEnum.Baz'")) { getClassToEnumBaz() }
    expectFailure(linkage("Function 'getClassToEnumBazInline' can not be called: ${adjustForLazyIr("Function")} uses unlinked class symbol '/ClassToEnum.Baz'")) { getClassToEnumBazInline() }
    expectFailure(linkage("Constructor 'ClassToEnum.<init>' can not be called: Private constructor declared in module <lib1> can not be accessed in module <lib2>")) { getClassToEnumBazAsAny() }
    expectFailure(linkage("Constructor 'ClassToEnum.<init>' can not be called: Private constructor declared in module <lib1> can not be accessed in module <lib2>")) { getClassToEnumBazAsAnyInline() }
    expectFailure(linkage("Constructor 'ClassToObject.<init>' can not be called: Private constructor declared in module <lib1> can not be accessed in module <lib2>")) { getClassToObject() }
    expectFailure(linkage("Constructor 'ClassToObject.<init>' can not be called: Private constructor declared in module <lib1> can not be accessed in module <lib2>")) { getClassToObjectInline() }
    expectFailure(linkage("Constructor 'ClassToObject.<init>' can not be called: Private constructor declared in module <lib1> can not be accessed in module <lib2>")) { getClassToObjectAsAny() }
    expectFailure(linkage("Constructor 'ClassToObject.<init>' can not be called: Private constructor declared in module <lib1> can not be accessed in module <lib2>")) { getClassToObjectAsAnyInline() }
    expectFailure(linkage("Can not get instance of singleton 'ObjectToClass': 'ObjectToClass' is class while object is expected")) { getObjectToClass() }
    expectFailure(linkage("Can not get instance of singleton 'ObjectToClass': 'ObjectToClass' is class while object is expected")) { getObjectToClassInline() }
    expectFailure(linkage("Can not get instance of singleton 'ObjectToClass': 'ObjectToClass' is class while object is expected")) { getObjectToClassAsAny() }
    expectFailure(linkage("Can not get instance of singleton 'ObjectToClass': 'ObjectToClass' is class while object is expected")) { getObjectToClassAsAnyInline() }
}
