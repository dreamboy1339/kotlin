/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.common

import org.jetbrains.kotlin.backend.common.ir.isProperExpect
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrFunctionImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrPropertyImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrPropertySymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrPropertySymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.types.classifierOrFail
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName

object IrActualizer {
    fun actualize(mainFragment: IrModuleFragment, dependentFragments: List<IrModuleFragment>) {
        val expectActualMap = calculateExpectActualMap(mainFragment, dependentFragments)
        removeExpectDeclaration(dependentFragments)
        addMissingFakeOverrides(expectActualMap, dependentFragments)
        linkExpectToActual(expectActualMap, dependentFragments)
        mergeIrFragments(mainFragment, dependentFragments)
    }

    private fun calculateExpectActualMap(
        mainFragment: IrModuleFragment,
        dependentFragments: List<IrModuleFragment>
    ): Map<IrSymbol, IrSymbol> {
        val result = mutableMapOf<IrSymbol, IrSymbol>()
        // Collect and link classifiers at first to make it possible to expand type aliases on the callables linking
        val (allActualDeclarations, typeAliasMap) = result.appendExpectActualClassifiersMap(mainFragment, dependentFragments)
        result.appendExpectActualCallablesMap(allActualDeclarations, typeAliasMap, dependentFragments)
        return result
    }

    private fun MutableMap<IrSymbol, IrSymbol>.appendExpectActualClassifiersMap(
        mainFragment: IrModuleFragment,
        dependentFragments: List<IrModuleFragment>
    ): Pair<Set<IrDeclaration>, Map<FqName, FqName>> {
        val actualClassifiers = mutableMapOf<FqName, IrSymbol>()
        // There is no list for builtins declarations, that's why they are being collected from typealiases
        val allActualDeclarations = mutableSetOf<IrDeclaration>()
        val typeAliasMap = mutableMapOf<FqName, FqName>() // It's used to link members from expect class that have typealias actual

        collectActualClassifiers(actualClassifiers, allActualDeclarations, typeAliasMap, mainFragment)
        appendExpectActualClassifiers(actualClassifiers, dependentFragments)

        return allActualDeclarations to typeAliasMap
    }

    private fun collectActualClassifiers(
        actualClassifiers: MutableMap<FqName, IrSymbol>,
        allActualClassifiers: MutableSet<IrDeclaration>,
        typeAliasMap: MutableMap<FqName, FqName>,
        mainFragment: IrModuleFragment,
    ) {
        for (file in mainFragment.files) {
            for (declaration in file.declarations) {
                var name: FqName? = null
                var symbol: IrSymbol? = null
                when (declaration) {
                    is IrTypeAlias -> {
                        if (declaration.isActual) {
                            name = declaration.kotlinFqName
                            symbol = declaration.expandedType.classifierOrFail
                            if (symbol is IrClassSymbol) {
                                allActualClassifiers.add(symbol.owner)
                                typeAliasMap[name] = symbol.owner.kotlinFqName
                            }
                        }
                    }
                    is IrClass -> {
                        if (!declaration.isExpect) {
                            name = declaration.kotlinFqName
                            symbol = declaration.symbol
                            allActualClassifiers.add(declaration)
                        }
                    }
                    is IrEnumEntry -> {
                        if (!declaration.isProperExpect) {
                            name = FqName.fromSegments(listOf(declaration.parent.kotlinFqName.asString(), declaration.name.asString()))
                            symbol = declaration.symbol
                            allActualClassifiers.add(declaration)
                        }
                    }
                    else -> {
                        if (!declaration.isProperExpect) {
                            allActualClassifiers.add(declaration)
                        }
                    }
                }
                if (name != null && symbol != null) {
                    actualClassifiers[name] = symbol
                }
                if (declaration is IrTypeParametersContainer && !declaration.isProperExpect) {
                    for (typeParameter in declaration.typeParameters) {
                        actualClassifiers[FqName.fromSegments(
                            listOf(
                                typeParameter.parent.kotlinFqName.asString(),
                                typeParameter.name.asString()
                            )
                        )] = typeParameter.symbol
                    }
                }
            }
        }
    }

    private fun MutableMap<IrSymbol, IrSymbol>.appendExpectActualClassifiers(
        actualClassifiers: MutableMap<FqName, IrSymbol>,
        dependentFragments: List<IrModuleFragment>
    ) {
        fun linkActualOrReportMissing(expectElement: IrSymbolOwner, actualTypeId: FqName) {
            val actualClassifier = actualClassifiers[actualTypeId]
            if (actualClassifier != null) {
                this[expectElement.symbol] = actualClassifier
            } else {
                reportMissingActual(expectElement)
            }
        }

        for (fragment in dependentFragments) {
            for (file in fragment.files) {
                for (declaration in file.declarations) {
                    when (declaration) {
                        is IrClass -> {
                            if (declaration.isExpect) {
                                linkActualOrReportMissing(declaration, declaration.kotlinFqName)
                            }
                        }
                        is IrEnumEntry -> {
                            if (declaration.isProperExpect) {
                                linkActualOrReportMissing(
                                    declaration,
                                    FqName.fromSegments(listOf(declaration.parent.kotlinFqName.asString(), declaration.name.asString()))
                                )
                            }
                        }
                    }
                    if (declaration is IrTypeParametersContainer && declaration.isProperExpect) {
                        for (typeParameter in declaration.typeParameters) {
                            linkActualOrReportMissing(
                                typeParameter,
                                FqName.fromSegments(listOf(typeParameter.parent.kotlinFqName.asString(), typeParameter.name.asString()))
                            )
                        }
                    }
                }
            }
        }
    }

    private fun MutableMap<IrSymbol, IrSymbol>.appendExpectActualCallablesMap(
        allActualDeclarations: Set<IrDeclaration>,
        typeAliasMap: Map<FqName, FqName>,
        dependentFragments: List<IrModuleFragment>
    ) {
        val actualFunctions = mutableMapOf<CallableId, MutableList<IrFunction>>()
        val actualProperties = mutableMapOf<CallableId, IrProperty>()

        collectActualCallables(actualFunctions, actualProperties, allActualDeclarations)
        appendExpectActualCallables(actualFunctions, actualProperties, dependentFragments, typeAliasMap)
    }

    private fun collectActualCallables(
        actualFunctions: MutableMap<CallableId, MutableList<IrFunction>>,
        actualProperties: MutableMap<CallableId, IrProperty>,
        allActualDeclarations: Set<IrDeclaration>
    ) {
        fun collectActualsCallables(declaration: IrDeclaration) {
            when (declaration) {
                is IrFunction -> {
                    actualFunctions.getOrPut(CallableId(declaration.parent.kotlinFqName, declaration.name)) {
                        mutableListOf()
                    }.add(declaration)
                }
                is IrProperty -> {
                    actualProperties.getOrPut(CallableId(declaration.parent.kotlinFqName, declaration.name)) {
                        declaration
                    }
                }
                is IrClass -> {
                    for (member in declaration.declarations) {
                        collectActualsCallables(member)
                    }
                }
            }
        }

        for (actualDeclaration in allActualDeclarations) {
            collectActualsCallables(actualDeclaration)
        }
    }

    private fun MutableMap<IrSymbol, IrSymbol>.appendExpectActualCallables(
        actualFunctions: MutableMap<CallableId, MutableList<IrFunction>>,
        actualProperties: MutableMap<CallableId, IrProperty>,
        dependentFragments: List<IrModuleFragment>,
        typeAliasMap: Map<FqName, FqName>
    ) {
        fun actualizeCallable(declaration: IrDeclarationWithName): CallableId {
            val fullName = declaration.parent.kotlinFqName
            return CallableId(typeAliasMap[fullName] ?: fullName, declaration.name)
        }

        fun linkExpectToActual(declarationContainer: IrDeclarationContainer) {
            for (declaration in declarationContainer.declarations) {
                if (!declaration.isProperExpect) continue
                when (declaration) {
                    is IrFunction -> {
                        val functions = actualFunctions[actualizeCallable(declaration)]
                        var isActualFunctionFound = false
                        if (functions != null) {
                            for (actualFunction in functions) {
                                if (checkParameters(declaration, actualFunction, this)) {
                                    this[declaration.symbol] = actualFunction.symbol
                                    isActualFunctionFound = true
                                    break
                                }
                            }
                        }
                        if (!isActualFunctionFound) {
                            reportMissingActual(declaration)
                        }
                    }
                    is IrProperty -> {
                        val properties = actualProperties[actualizeCallable(declaration)]
                        if (properties != null) {
                            this[declaration.symbol] = properties.symbol
                            declaration.getter?.symbol?.let {
                                this[it] = properties.getter!!.symbol
                            }
                            declaration.setter?.symbol?.let {
                                this[it] = properties.setter!!.symbol
                            }
                        } else {
                            reportMissingActual(declaration)
                        }
                    }
                    is IrClass -> {
                        linkExpectToActual(declaration)
                    }
                }
            }
        }

        for (fragment in dependentFragments) {
            for (file in fragment.files) {
                linkExpectToActual(file)
            }
        }
    }

    private fun addMissingFakeOverrides(expectActualMap: Map<IrSymbol, IrSymbol>, dependentFragments: List<IrModuleFragment>) {
        for (fragment in dependentFragments) {
            for (file in fragment.files) {
                for (declaration in file.declarations) {
                    if (declaration is IrClass && !declaration.isExpect) {
                        processSupertypes(declaration, expectActualMap)
                    }
                }
            }
        }
    }

    private fun processSupertypes(declaration: IrClass, expectActualMap: Map<IrSymbol, IrSymbol>) {
        val members by lazy(LazyThreadSafetyMode.NONE) {
            // No need to collect builtin members
            declaration.declarations.filter { it.startOffset != UNDEFINED_OFFSET }.filterIsInstance<IrDeclarationWithName>().groupBy { it.name }
        }

        for (superType in declaration.superTypes) {
            val actualClass = expectActualMap[superType.classifierOrFail]?.owner as? IrClass ?: continue
            for (actualMember in actualClass.declarations) {
                if (actualMember.startOffset == UNDEFINED_OFFSET) continue
                when (actualMember) {
                    is IrFunctionImpl -> {
                        val existingMembers = members[actualMember.name]

                        var isActualFunctionFound = false
                        if (existingMembers != null) {
                            for (existingMember in existingMembers) {
                                if (existingMember is IrFunction) {
                                    if (checkParameters(existingMember, actualMember, expectActualMap)) {
                                        isActualFunctionFound = true
                                        break
                                    }
                                }
                            }
                        }

                        if (isActualFunctionFound) {
                            reportManyInterfacesMembersNotImplemented(declaration, actualMember)
                            continue
                        }

                        declaration.declarations.add(createFakeOverrideFunction(actualMember, declaration))
                    }
                    is IrPropertyImpl -> {
                        if (members[actualMember.name] != null) {
                            reportManyInterfacesMembersNotImplemented(declaration, actualMember)
                            continue
                        }

                        declaration.declarations.add(createFakeOverrideProperty(actualMember, declaration))
                    }
                }
            }
        }
    }

    private fun createFakeOverrideProperty(actualMember: IrPropertyImpl, declaration: IrClass) =
        IrPropertyImpl(
            actualMember.startOffset,
            actualMember.endOffset,
            IrDeclarationOrigin.FAKE_OVERRIDE,
            IrPropertySymbolImpl(),
            actualMember.name,
            actualMember.visibility,
            actualMember.modality,
            actualMember.isVar,
            actualMember.isConst,
            actualMember.isLateinit,
            actualMember.isDelegated,
            isExternal = actualMember.isExternal
        ).also {
            it.parent = declaration
            it.annotations = actualMember.annotations
            it.backingField = actualMember.backingField
            it.getter = (actualMember.getter as? IrFunctionImpl)?.let { getter ->
                createFakeOverrideFunction(getter, declaration, it.symbol)
            }
            it.setter = (actualMember.setter as? IrFunctionImpl)?.let { setter ->
                createFakeOverrideFunction(setter, declaration, it.symbol)
            }
            it.overriddenSymbols = listOf(actualMember.symbol)
            it.metadata = actualMember.metadata
            it.attributeOwnerId = it
        }

    private fun createFakeOverrideFunction(
        actualFunction: IrFunctionImpl,
        parent: IrDeclarationParent,
        correspondingPropertySymbol: IrPropertySymbol? = null
    ) =
        IrFunctionImpl(
            actualFunction.startOffset,
            actualFunction.endOffset,
            IrDeclarationOrigin.FAKE_OVERRIDE,
            IrSimpleFunctionSymbolImpl(),
            actualFunction.name,
            actualFunction.visibility,
            actualFunction.modality,
            actualFunction.returnType,
            actualFunction.isInline,
            actualFunction.isExternal,
            actualFunction.isTailrec,
            actualFunction.isSuspend,
            actualFunction.isOperator,
            actualFunction.isInfix,
            isExpect = false
        ).also {
            it.parent = parent
            it.annotations = actualFunction.annotations.map { p -> p.deepCopyWithSymbols(it) }
            it.typeParameters = actualFunction.typeParameters.map { p -> p.deepCopyWithSymbols(it) }
            it.dispatchReceiverParameter = actualFunction.dispatchReceiverParameter?.deepCopyWithSymbols(it)
            it.extensionReceiverParameter = actualFunction.extensionReceiverParameter?.deepCopyWithSymbols(it)
            it.valueParameters = actualFunction.valueParameters.map { p -> p.deepCopyWithSymbols(it) }
            it.contextReceiverParametersCount = actualFunction.contextReceiverParametersCount
            it.metadata = actualFunction.metadata
            it.overriddenSymbols = listOf(actualFunction.symbol)
            it.attributeOwnerId = it
            it.correspondingPropertySymbol = correspondingPropertySymbol
        }

    private fun checkParameters(
        expectFunction: IrFunction,
        actualFunction: IrFunction,
        expectActualTypesMap: Map<IrSymbol, IrSymbol>
    ): Boolean {
        if (expectFunction.valueParameters.size != actualFunction.valueParameters.size) return false
        for ((expectParameter, actualParameter) in expectFunction.valueParameters.zip(actualFunction.valueParameters)) {
            val expectParameterTypeSymbol = expectParameter.type.classifierOrFail
            val actualizedParameterTypeSymbol = expectActualTypesMap[expectParameterTypeSymbol] ?: expectParameterTypeSymbol
            if (actualizedParameterTypeSymbol != actualParameter.type.classifierOrFail) {
                return false
            }
        }
        return true
    }

    private fun reportMissingActual(irElement: IrElement) {
        // TODO: set up diagnostics reporting
        throw AssertionError("Missing actual for ${irElement.render()}")
    }

    private fun reportManyInterfacesMembersNotImplemented(declaration: IrClass, actualMember: IrDeclarationWithName) {
        // TODO: set up diagnostics reporting
        throw AssertionError("${declaration.name} must override ${actualMember.name} because it inherits multiple interface methods of it")
    }

    private fun removeExpectDeclaration(dependentFragments: List<IrModuleFragment>) {
        for (fragment in dependentFragments) {
            for (file in fragment.files) {
                file.declarations.removeAll { it.isProperExpect }
            }
        }
    }

    private fun linkExpectToActual(expectActualMap: Map<IrSymbol, IrSymbol>, dependentFragments: List<IrModuleFragment>) {
        val actualizer = IrActualizerTransformer(expectActualMap)
        for (dependentFragment in dependentFragments) {
            actualizer.actualize(dependentFragment)
        }
    }

    private fun mergeIrFragments(mainFragment: IrModuleFragment, dependentFragments: List<IrModuleFragment>) {
        mainFragment.files.addAll(dependentFragments.flatMap { it.files })
    }
}