/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.fir.scopes

import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.utils.isSynthetic
import org.jetbrains.kotlin.fir.resolve.substitution.ConeSubstitutor
import org.jetbrains.kotlin.fir.scopes.FirContainingNamesAwareScope
import org.jetbrains.kotlin.fir.symbols.impl.*
import org.jetbrains.kotlin.fir.types.ConeLookupTagBasedType
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames

class JavaClassDeclaredMembersEnhancementScope(
    private val owner: FirRegularClassSymbol,
    private val useSiteMembersEnhancementScope: FirContainingNamesAwareScope
) : FirContainingNamesAwareScope() {
    private val callableNames = run {
        owner.fir.declarations.filter { it is FirCallableDeclaration
                    && (it.dispatchReceiverType as? ConeLookupTagBasedType)?.lookupTag == owner.toLookupTag()
                    && it.origin != FirDeclarationOrigin.SubstitutionOverride
                    && it.origin != FirDeclarationOrigin.IntersectionOverride
        }.mapNotNull {
            when(it) {
                is FirConstructor -> SpecialNames.INIT
                is FirVariable -> if (it.isSynthetic) null else it.name
                is FirSimpleFunction -> it.name
                else -> null
            }
        }.toSet()
    }

    override fun getCallableNames(): Set<Name> {
        return callableNames
    }

    override fun getClassifierNames(): Set<Name> {
        return useSiteMembersEnhancementScope.getClassifierNames()
    }

    override fun processFunctionsByName(name: Name, processor: (FirNamedFunctionSymbol) -> Unit) {
        useSiteMembersEnhancementScope.processFunctionsByName(name, processor)
    }

    override fun processClassifiersByNameWithSubstitution(
        name: Name,
        processor: (FirClassifierSymbol<*>, ConeSubstitutor) -> Unit
    ) {
        useSiteMembersEnhancementScope.processClassifiersByNameWithSubstitution(name, processor)
    }

    override fun processDeclaredConstructors(processor: (FirConstructorSymbol) -> Unit) {
        useSiteMembersEnhancementScope.processDeclaredConstructors(processor)
    }

    override fun processPropertiesByName(name: Name, processor: (FirVariableSymbol<*>) -> Unit) {
        useSiteMembersEnhancementScope.processPropertiesByName(name, processor)
    }

    override fun toString(): String {
        return "Java enhancement declared member scope for ${owner.classId}"
    }
}