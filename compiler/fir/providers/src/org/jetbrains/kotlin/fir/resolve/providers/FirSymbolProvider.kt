/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.providers

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.FirSessionComponent
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.getSymbolByLookupTag
import org.jetbrains.kotlin.fir.scopes.FirScope
import org.jetbrains.kotlin.fir.scopes.getDeclaredConstructors
import org.jetbrains.kotlin.fir.scopes.getFunctions
import org.jetbrains.kotlin.fir.scopes.getProperties
import org.jetbrains.kotlin.fir.scopes.impl.declaredMemberScope
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.*
import org.jetbrains.kotlin.fir.types.ConeLookupTagBasedType
import org.jetbrains.kotlin.fir.types.ConeSimpleKotlinType
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.coneTypeSafe
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

@RequiresOptIn
annotation class FirSymbolProviderInternals

abstract class FirSymbolProvider(val session: FirSession) : FirSessionComponent {
    abstract fun getClassLikeSymbolByClassId(classId: ClassId): FirClassLikeSymbol<*>?

    @OptIn(FirSymbolProviderInternals::class)
    open fun getTopLevelCallableSymbols(packageFqName: FqName, name: Name): List<FirCallableSymbol<*>> {
        return buildList { getTopLevelCallableSymbolsTo(this, packageFqName, name) }
    }

    @FirSymbolProviderInternals
    abstract fun getTopLevelCallableSymbolsTo(destination: MutableList<FirCallableSymbol<*>>, packageFqName: FqName, name: Name)

    @OptIn(FirSymbolProviderInternals::class)
    open fun getTopLevelFunctionSymbols(packageFqName: FqName, name: Name): List<FirNamedFunctionSymbol> {
        return buildList { getTopLevelFunctionSymbolsTo(this, packageFqName, name) }
    }

    @FirSymbolProviderInternals
    abstract fun getTopLevelFunctionSymbolsTo(destination: MutableList<FirNamedFunctionSymbol>, packageFqName: FqName, name: Name)

    @OptIn(FirSymbolProviderInternals::class)
    open fun getTopLevelPropertySymbols(packageFqName: FqName, name: Name): List<FirPropertySymbol> {
        return buildList { getTopLevelPropertySymbolsTo(this, packageFqName, name) }
    }

    @FirSymbolProviderInternals
    abstract fun getTopLevelPropertySymbolsTo(destination: MutableList<FirPropertySymbol>, packageFqName: FqName, name: Name)

    abstract fun getPackage(fqName: FqName): FqName? // TODO: Replace to symbol sometime
    /**
     * @returns full package names that might be not empty (have some non-class declarations) in this provider
     *
     * In JVM, it's expensive to compute all the packages that might contain a Java class among dependencies.
     * But, as we have all the metadata, we may be sure about top-level callables and type aliases.
     * This method should only be used for sake of optimization to avoid having too many empty-list/null values in our caches.
     */
    abstract fun computePackageSetWithTopLevelCallables(): Set<String>?

    /**
     * TODO
     */
    abstract fun knownTopLevelClassifiersInPackage(packageFqName: FqName): Set<String>?

    abstract fun computeCallableNamesInPackage(packageFqName: FqName): Set<Name>?
}

/**
 * Works almost as regular flatMap, but returns a set and returns null if any lambda call returned null
 */
inline fun <T, R> Iterable<T>.flatMapToNullableSet(transform: (T) -> Iterable<R>?): Set<R>? =
    flatMapTo(mutableSetOf()) { transform(it) ?: return null }

private fun FirSymbolProvider.getClassDeclaredMemberScope(classId: ClassId): FirScope? {
    val classSymbol = getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol ?: return null
    return session.declaredMemberScope(classSymbol.fir)
}

fun FirSymbolProvider.getClassDeclaredConstructors(classId: ClassId): List<FirConstructorSymbol> {
    val classMemberScope = getClassDeclaredMemberScope(classId)
    return classMemberScope?.getDeclaredConstructors().orEmpty()
}

fun FirSymbolProvider.getClassDeclaredFunctionSymbols(classId: ClassId, name: Name): List<FirNamedFunctionSymbol> {
    val classMemberScope = getClassDeclaredMemberScope(classId)
    return classMemberScope?.getFunctions(name).orEmpty()
}

fun FirSymbolProvider.getClassDeclaredPropertySymbols(classId: ClassId, name: Name): List<FirVariableSymbol<*>> {
    val classMemberScope = getClassDeclaredMemberScope(classId)
    return classMemberScope?.getProperties(name).orEmpty()
}

inline fun <reified T : FirBasedSymbol<*>> FirSymbolProvider.getSymbolByTypeRef(typeRef: FirTypeRef): T? {
    val lookupTag = (typeRef.coneTypeSafe<ConeSimpleKotlinType>()?.fullyExpandedType(session) as? ConeLookupTagBasedType)?.lookupTag
        ?: return null
    return getSymbolByLookupTag(lookupTag) as? T
}

val FirSession.symbolProvider: FirSymbolProvider by FirSession.sessionComponentAccessor()

const val DEPENDENCIES_SYMBOL_PROVIDER_QUALIFIED_KEY: String = "org.jetbrains.kotlin.fir.resolve.providers.FirDependenciesSymbolProvider"

val FirSession.dependenciesSymbolProvider: FirSymbolProvider by FirSession.sessionComponentAccessor(
    DEPENDENCIES_SYMBOL_PROVIDER_QUALIFIED_KEY
)
