/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.light.classes.symbol.methods

import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import org.jetbrains.kotlin.analysis.api.KtAnalysisSession
import org.jetbrains.kotlin.analysis.api.annotations.KtAnnotationApplication
import org.jetbrains.kotlin.analysis.api.symbols.KtPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KtSymbolPointer
import org.jetbrains.kotlin.analysis.api.symbols.sourcePsiSafe
import org.jetbrains.kotlin.asJava.builder.LightMemberOrigin
import org.jetbrains.kotlin.asJava.classes.METHOD_INDEX_FOR_ANNOTATIONS
import org.jetbrains.kotlin.asJava.classes.lazyPub
import org.jetbrains.kotlin.asJava.elements.KtLightIdentifier
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget
import org.jetbrains.kotlin.light.classes.symbol.*
import org.jetbrains.kotlin.light.classes.symbol.annotations.SymbolLightSimpleAnnotation
import org.jetbrains.kotlin.light.classes.symbol.annotations.allowedTargets
import org.jetbrains.kotlin.light.classes.symbol.annotations.computeAnnotations
import org.jetbrains.kotlin.light.classes.symbol.annotations.getJvmNameFromAnnotation
import org.jetbrains.kotlin.light.classes.symbol.classes.SymbolLightClassBase
import org.jetbrains.kotlin.light.classes.symbol.modifierLists.LazyModifiersBox
import org.jetbrains.kotlin.light.classes.symbol.modifierLists.SymbolLightMemberModifierList
import org.jetbrains.kotlin.light.classes.symbol.parameters.SymbolLightParameterList
import org.jetbrains.kotlin.light.classes.symbol.parameters.SymbolLightTypeParameterList
import org.jetbrains.kotlin.load.java.JvmAbi
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.utils.addToStdlib.ifTrue

internal class SymbolLightAnnotationsMethod private constructor(
    lightMemberOrigin: LightMemberOrigin?,
    containingClass: SymbolLightClassBase,
    private val containingPropertyDeclaration: KtCallableDeclaration?,
    private val containingPropertySymbolPointer: KtSymbolPointer<KtPropertySymbol>,
) : SymbolLightMethodBase(
    lightMemberOrigin,
    containingClass,
    METHOD_INDEX_FOR_ANNOTATIONS,
) {
    internal constructor(
        ktAnalysisSession: KtAnalysisSession,
        containingPropertySymbol: KtPropertySymbol,
        lightMemberOrigin: LightMemberOrigin?,
        containingClass: SymbolLightClassBase,
    ) : this(
        lightMemberOrigin,
        containingClass,
        containingPropertyDeclaration = containingPropertySymbol.sourcePsiSafe(),
        containingPropertySymbolPointer = with(ktAnalysisSession) { containingPropertySymbol.createPointer() },
    )

    context(KtAnalysisSession)
    private fun propertySymbol(): KtPropertySymbol {
        return containingPropertySymbolPointer.restoreSymbolOrThrowIfDisposed()
    }

    private fun String.abiName(): String {
        return JvmAbi.getSyntheticMethodNameForAnnotatedProperty(JvmAbi.getterName(this))
    }

    private val _name: String by lazyPub {
        analyzeForLightClasses(ktModule) {
            val symbol = propertySymbol()
            symbol.getJvmNameFromAnnotation(AnnotationUseSiteTarget.PROPERTY) ?: run {
                val defaultName = symbol.name.identifier.let {
                    if (containingClass.isAnnotationType) it else it.abiName()
                }
                symbol.computeJvmMethodName(defaultName, containingClass, AnnotationUseSiteTarget.PROPERTY)
            }
        }
    }

    override fun getName(): String = _name

    override fun isVarArgs(): Boolean = false

    override val kotlinOrigin: KtDeclaration? get() = containingPropertyDeclaration

    private fun computeModifiers(modifier: String): Map<String, Boolean>? {
        return when (modifier) {
            in LazyModifiersBox.VISIBILITY_MODIFIERS ->
                LazyModifiersBox.computeVisibilityForMember(ktModule, containingPropertySymbolPointer)
            PsiModifier.FINAL, PsiModifier.ABSTRACT ->
                mapOf(modifier to false)
            PsiModifier.STATIC ->
                mapOf(modifier to true)
            else -> null
        }
    }

    private val _modifierList: PsiModifierList by lazyPub {
        SymbolLightMemberModifierList(
            containingDeclaration = this,
            initialValue = emptyMap(),
            lazyModifiersComputer = ::computeModifiers,
        ) { modifierList ->
            containingPropertySymbolPointer.withSymbol(ktModule) { propertySymbol ->
                val isFromPrimaryConstructor = propertySymbol.isFromPrimaryConstructor
                fun KtAnnotationApplication.isApplicable(): Boolean {
                    val targets = this.allowedTargets

                    if (targets != null) {
                        if (AnnotationTarget.PROPERTY !in targets) return false
                        if (useSiteTarget == null) return !(isFromPrimaryConstructor && AnnotationTarget.VALUE_PARAMETER in targets)
                    }

                    return useSiteTarget == AnnotationUseSiteTarget.PROPERTY || useSiteTarget == null && !isFromPrimaryConstructor
                }

                val computed = propertySymbol.computeAnnotations(
                    modifierList = modifierList,
                    nullability = NullabilityType.Unknown,
                ) {
                    if (it.classId?.asFqNameString() == "kotlin.jvm.JvmStatic") return@computeAnnotations false

                    it.allowedTargets?.let { targets ->
                        if (AnnotationTarget.PROPERTY !in targets) return@computeAnnotations false
                        if (it.useSiteTarget == null) return@computeAnnotations !(isFromPrimaryConstructor && AnnotationTarget.VALUE_PARAMETER in targets)
                    }

                    return@computeAnnotations it.useSiteTarget == AnnotationUseSiteTarget.PROPERTY || it.useSiteTarget == null && !isFromPrimaryConstructor
                }
                return@withSymbol if (computed.isNotEmpty())
                    (computed + SymbolLightSimpleAnnotation("java.lang.Deprecated", modifierList))
                else emptyList()
            }
        }
    }

    override fun getModifierList(): PsiModifierList = _modifierList

    override fun isConstructor(): Boolean = false

    override fun isDeprecated(): Boolean = true

    private val _identifier: PsiIdentifier by lazyPub {
        KtLightIdentifier(this, containingPropertyDeclaration)
    }

    override fun getNameIdentifier(): PsiIdentifier = _identifier

    override fun getReturnType(): PsiType = PsiType.VOID

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SymbolLightAnnotationsMethod) return false
        return other.ktModule == ktModule && containingPropertyDeclaration == other.containingPropertyDeclaration
    }

    override fun hashCode(): Int = containingPropertyDeclaration.hashCode()

    private val _typeParameterList: PsiTypeParameterList? by lazyPub {
        hasTypeParameters().ifTrue {
            SymbolLightTypeParameterList(
                owner = this,
                symbolWithTypeParameterPointer = containingPropertySymbolPointer,
                ktModule = ktModule,
                ktDeclaration = containingPropertyDeclaration,
            )
        }
    }

    override fun hasTypeParameters(): Boolean = hasTypeParameters(ktModule, containingPropertyDeclaration, containingPropertySymbolPointer)
    override fun getTypeParameterList(): PsiTypeParameterList? = _typeParameterList
    override fun getTypeParameters(): Array<PsiTypeParameter> = _typeParameterList?.typeParameters ?: PsiTypeParameter.EMPTY_ARRAY

    private val _parametersList by lazyPub {
        SymbolLightParameterList(
            parent = this@SymbolLightAnnotationsMethod,
            callableWithReceiverSymbolPointer = containingPropertySymbolPointer,
            parameterPopulator = {},
        )
    }

    override fun getParameterList(): PsiParameterList = _parametersList

    override fun isValid(): Boolean =
        super.isValid() && containingPropertySymbolPointer.isValid(ktModule)

    override fun isOverride(): Boolean = false

    override fun getText(): String {
        return lightMemberOrigin?.auxiliaryOriginalElement?.text ?: super.getText()
    }

    override fun getTextOffset(): Int {
        return lightMemberOrigin?.auxiliaryOriginalElement?.textOffset ?: super.getTextOffset()
    }

    override fun getTextRange(): TextRange {
        return lightMemberOrigin?.auxiliaryOriginalElement?.textRange ?: super.getTextRange()
    }
}