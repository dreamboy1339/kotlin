/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.resolve

import com.google.common.collect.ImmutableSet
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.builtins.UnsignedTypes
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.AnnotationDescriptor
import org.jetbrains.kotlin.diagnostics.DiagnosticFactory0
import org.jetbrains.kotlin.diagnostics.Errors.*
import org.jetbrains.kotlin.incremental.KotlinLookupLocation
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getChildOfType
import org.jetbrains.kotlin.psi.psiUtil.hasActualModifier
import org.jetbrains.kotlin.psi.psiUtil.visibilityModifier
import org.jetbrains.kotlin.resolve.BindingContext.*
import org.jetbrains.kotlin.resolve.DescriptorUtils.classCanHaveAbstractDeclaration
import org.jetbrains.kotlin.resolve.DescriptorUtils.classCanHaveOpenMembers
import org.jetbrains.kotlin.resolve.calls.results.TypeSpecificityComparator
import org.jetbrains.kotlin.resolve.checkers.PlatformDiagnosticSuppressor
import org.jetbrains.kotlin.resolve.descriptorUtil.builtIns
import org.jetbrains.kotlin.resolve.descriptorUtil.isEffectivelyExternal
import org.jetbrains.kotlin.resolve.inline.isInlineOnly
import org.jetbrains.kotlin.resolve.source.KotlinSourceElement
import org.jetbrains.kotlin.types.*
import org.jetbrains.kotlin.types.checker.KotlinTypeChecker
import org.jetbrains.kotlin.types.typeUtil.constituentTypes
import org.jetbrains.kotlin.types.typeUtil.contains
import org.jetbrains.kotlin.types.typeUtil.isArrayOfNothing
import org.jetbrains.kotlin.types.typeUtil.isNothing

internal class DeclarationsCheckerBuilder(
    private val descriptorResolver: DescriptorResolver,
    private val originalModifiersChecker: ModifiersChecker,
    private val annotationChecker: AnnotationChecker,
    private val identifierChecker: IdentifierChecker,
    private val languageVersionSettings: LanguageVersionSettings,
    private val typeSpecificityComparator: TypeSpecificityComparator,
    private val diagnosticSuppressor: PlatformDiagnosticSuppressor,
    private val upperBoundChecker: UpperBoundChecker
) {
    fun withTrace(trace: BindingTrace) = DeclarationsChecker(
        descriptorResolver, originalModifiersChecker, annotationChecker, identifierChecker, trace, languageVersionSettings,
        typeSpecificityComparator, diagnosticSuppressor, upperBoundChecker
    )
}

class DeclarationsChecker(
    private val descriptorResolver: DescriptorResolver,
    modifiersChecker: ModifiersChecker,
    private val annotationChecker: AnnotationChecker,
    private val identifierChecker: IdentifierChecker,
    private val trace: BindingTrace,
    private val languageVersionSettings: LanguageVersionSettings,
    typeSpecificityComparator: TypeSpecificityComparator,
    private val diagnosticSuppressor: PlatformDiagnosticSuppressor,
    private val upperBoundChecker: UpperBoundChecker
) {

    private val modifiersChecker = modifiersChecker.withTrace(trace)

    private val exposedChecker = ExposedVisibilityChecker(languageVersionSettings, trace)

    private val shadowedExtensionChecker = ShadowedExtensionChecker(typeSpecificityComparator, trace)

    fun process(bodiesResolveContext: BodiesResolveContext) {
        for (file in bodiesResolveContext.files) {
            checkModifiersAndAnnotationsInPackageDirective(file)
            annotationChecker.check(file, trace, null)
        }

        for ((classOrObject, classDescriptor) in bodiesResolveContext.declaredClasses.entries) {
            checkClass(classDescriptor, classOrObject)
            modifiersChecker.checkModifiersForDeclaration(classOrObject, classDescriptor)
            identifierChecker.checkDeclaration(classOrObject, trace)
            exposedChecker.checkClassHeader(classOrObject, classDescriptor)
        }

        for ((function, functionDescriptor) in bodiesResolveContext.functions.entries) {
            checkFunction(function, functionDescriptor)
            modifiersChecker.checkModifiersForDeclaration(function, functionDescriptor)
            identifierChecker.checkDeclaration(function, trace)
        }

        for ((property, propertyDescriptor) in bodiesResolveContext.properties.entries) {
            checkProperty(property, propertyDescriptor)
            modifiersChecker.checkModifiersForDeclaration(property, propertyDescriptor)
            identifierChecker.checkDeclaration(property, trace)
        }

        val destructuringDeclarations = bodiesResolveContext.destructuringDeclarationEntries.entries
            .map { (entry, _) -> entry.parent }
            .filterIsInstance<KtDestructuringDeclaration>()
            .distinct()

        for (multiDeclaration in destructuringDeclarations) {
            modifiersChecker.checkModifiersForDestructuringDeclaration(multiDeclaration)
            identifierChecker.checkDeclaration(multiDeclaration, trace)
        }

        for ((declaration, constructorDescriptor) in bodiesResolveContext.secondaryConstructors.entries) {
            checkConstructorDeclaration(constructorDescriptor, declaration)
            exposedChecker.checkFunction(declaration, constructorDescriptor)
        }

        for ((declaration, typeAliasDescriptor) in bodiesResolveContext.typeAliases.entries) {
            checkTypeAliasDeclaration(declaration, typeAliasDescriptor)
            modifiersChecker.checkModifiersForDeclaration(declaration, typeAliasDescriptor)
            exposedChecker.checkTypeAlias(declaration, typeAliasDescriptor)
        }
    }

    fun checkLocalTypeAliasDeclaration(declaration: KtTypeAlias, typeAliasDescriptor: TypeAliasDescriptor) {
        checkTypeAliasDeclaration(declaration, typeAliasDescriptor)
        modifiersChecker.checkModifiersForDeclaration(declaration, typeAliasDescriptor)
        exposedChecker.checkTypeAlias(declaration, typeAliasDescriptor)
    }

    private fun checkTypeAliasDeclaration(declaration: KtTypeAlias, typeAliasDescriptor: TypeAliasDescriptor) {
        val typeReference = declaration.getTypeReference() ?: return

        checkTypeAliasExpansion(declaration, typeAliasDescriptor)

        val expandedType = typeAliasDescriptor.expandedType
        if (expandedType.isError) return

        val expandedClassifier = expandedType.constructor.declarationDescriptor

        if (expandedType.isDynamic() || expandedClassifier is TypeParameterDescriptor) {
            trace.report(TYPEALIAS_SHOULD_EXPAND_TO_CLASS.on(typeReference, expandedType))
        }

        if (TypeUtils.contains(expandedType) { it.isArrayOfNothing() }) {
            trace.report(TYPEALIAS_EXPANDED_TO_MALFORMED_TYPE.on(typeReference, expandedType, "Array<Nothing> is illegal"))
        }

        val usedTypeAliasParameters: Set<TypeParameterDescriptor> = getUsedTypeAliasParameters(expandedType, typeAliasDescriptor)
        for (typeParameter in typeAliasDescriptor.declaredTypeParameters) {
            if (typeParameter !in usedTypeAliasParameters) {
                val source = DescriptorToSourceUtils.descriptorToDeclaration(typeParameter) as? KtTypeParameter
                    ?: throw AssertionError("No source element for type parameter $typeParameter of $typeAliasDescriptor")
                trace.report(UNUSED_TYPEALIAS_PARAMETER.on(source, typeParameter, expandedType))
            }
        }

        if (declaration.hasActualModifier()) {
            checkActualTypeAlias(declaration, typeAliasDescriptor)
        }
    }

    private fun checkActualTypeAlias(declaration: KtTypeAlias, typeAliasDescriptor: TypeAliasDescriptor) {
        val rhs = typeAliasDescriptor.underlyingType
        val classDescriptor = rhs.constructor.declarationDescriptor
        if (classDescriptor !is ClassDescriptor) {
            trace.report(ACTUAL_TYPE_ALIAS_NOT_TO_CLASS.on(declaration))
            return
        }

        if (classDescriptor.declaredTypeParameters.any { it.variance != Variance.INVARIANT }) {
            trace.report(ACTUAL_TYPE_ALIAS_TO_CLASS_WITH_DECLARATION_SITE_VARIANCE.on(declaration))
            return
        }

        if (rhs.arguments.any { it.projectionKind != Variance.INVARIANT || it.isStarProjection }) {
            trace.report(ACTUAL_TYPE_ALIAS_WITH_USE_SITE_VARIANCE.on(declaration))
            return
        }

        if (rhs.arguments.map { it.type.constructor.declarationDescriptor as? TypeParameterDescriptor } !=
            typeAliasDescriptor.declaredTypeParameters) {
            trace.report(ACTUAL_TYPE_ALIAS_WITH_COMPLEX_SUBSTITUTION.on(declaration))
            return
        }

        if (languageVersionSettings.supportsFeature(LanguageFeature.MultiplatformRestrictions)) {
            if (rhs.isNothing()) {
                trace.report(ACTUAL_TYPE_ALIAS_TO_NOTHING.on(declaration))
                return
            }

            if (rhs.isMarkedNullable) {
                trace.report(ACTUAL_TYPE_ALIAS_TO_NULLABLE_TYPE.on(declaration))
                return
            }
        }
    }

    private fun getUsedTypeAliasParameters(type: KotlinType, typeAlias: TypeAliasDescriptor): Set<TypeParameterDescriptor> =
        type.constituentTypes().mapNotNullTo(HashSet()) {
            val descriptor = it.constructor.declarationDescriptor as? TypeParameterDescriptor
            descriptor?.takeIf { it.containingDeclaration == typeAlias }
        }

    private class TypeAliasDeclarationCheckingReportStrategy(
        private val trace: BindingTrace,
        typeAliasDescriptor: TypeAliasDescriptor,
        declaration: KtTypeAlias,
        val upperBoundChecker: UpperBoundChecker
    ) : TypeAliasExpansionReportStrategy {
        private val typeReference = declaration.getTypeReference()
            ?: throw AssertionError("Incorrect type alias declaration for $typeAliasDescriptor")

        override fun wrongNumberOfTypeArguments(typeAlias: TypeAliasDescriptor, numberOfParameters: Int) {
            // Do nothing: this should've been reported during type resolution.
        }

        override fun conflictingProjection(
            typeAlias: TypeAliasDescriptor,
            typeParameter: TypeParameterDescriptor?,
            substitutedArgument: KotlinType
        ) {
            trace.report(CONFLICTING_PROJECTION_IN_TYPEALIAS_EXPANSION.on(typeReference, substitutedArgument))
        }

        override fun recursiveTypeAlias(typeAlias: TypeAliasDescriptor) {
            trace.report(RECURSIVE_TYPEALIAS_EXPANSION.on(typeReference, typeAlias))
        }

        override fun boundsViolationInSubstitution(
            substitutor: TypeSubstitutor,
            unsubstitutedArgument: KotlinType,
            argument: KotlinType,
            typeParameter: TypeParameterDescriptor
        ) {
            upperBoundChecker.checkBounds(null, argument, typeParameter, substitutor, trace, typeReference)
        }

        override fun repeatedAnnotation(annotation: AnnotationDescriptor) {
            val annotationEntry = (annotation.source as? KotlinSourceElement)?.psi as? KtAnnotationEntry ?: return
            trace.report(REPEATED_ANNOTATION.on(annotationEntry))
        }
    }

    private fun checkTypeAliasExpansion(declaration: KtTypeAlias, typeAliasDescriptor: TypeAliasDescriptor) {
        val typeAliasExpansion = TypeAliasExpansion.createWithFormalArguments(typeAliasDescriptor)
        val reportStrategy = TypeAliasDeclarationCheckingReportStrategy(trace, typeAliasDescriptor, declaration, upperBoundChecker)
        TypeAliasExpander(reportStrategy, true).expandWithoutAbbreviation(typeAliasExpansion, TypeAttributes.Empty)
    }

    private fun checkConstructorDeclaration(constructorDescriptor: ClassConstructorDescriptor, declaration: KtConstructor<*>) {
        modifiersChecker.checkModifiersForDeclaration(declaration, constructorDescriptor)
        identifierChecker.checkDeclaration(declaration, trace)
        checkVarargParameters(trace, constructorDescriptor)
        checkConstructorVisibility(constructorDescriptor, declaration)
        checkExpectedClassConstructor(constructorDescriptor, declaration)
        checkContextParameters(declaration.modifierList)
    }

    private fun checkExpectedClassConstructor(constructorDescriptor: ClassConstructorDescriptor, declaration: KtConstructor<*>) {
        if (!constructorDescriptor.isExpect) return

        if (declaration.hasBody()) {
            trace.report(EXPECTED_DECLARATION_WITH_BODY.on(declaration))
        }

        if (constructorDescriptor.containingDeclaration.kind == ClassKind.ENUM_CLASS) {
            trace.report(EXPECTED_ENUM_CONSTRUCTOR.on(declaration))
        }

        if (declaration is KtPrimaryConstructor &&
            !DescriptorUtils.isAnnotationClass(constructorDescriptor.constructedClass) &&
            !constructorDescriptor.constructedClass.isValueClass()
        ) {
            for (parameter in declaration.valueParameters) {
                if (parameter.hasValOrVar()) {
                    trace.report(EXPECTED_CLASS_CONSTRUCTOR_PROPERTY_PARAMETER.on(parameter))
                }
            }
        }

        if (declaration is KtSecondaryConstructor) {
            val delegationCall = declaration.getDelegationCall()
            if (!delegationCall.isImplicit) {
                trace.report(EXPECTED_CLASS_CONSTRUCTOR_DELEGATION_CALL.on(delegationCall))
            }
        }
    }

    private fun checkConstructorVisibility(constructorDescriptor: ClassConstructorDescriptor, declaration: KtDeclaration) {
        val visibilityModifier = declaration.visibilityModifier()
        val visibilityKeyword = visibilityModifier?.node?.elementType ?: return
        val classDescriptor = constructorDescriptor.containingDeclaration

        when {
            classDescriptor.kind == ClassKind.ENUM_CLASS -> {
                if (visibilityKeyword != KtTokens.PRIVATE_KEYWORD) {
                    trace.report(NON_PRIVATE_CONSTRUCTOR_IN_ENUM.on(visibilityModifier))
                }
            }
            classDescriptor.modality == Modality.SEALED -> {
                val protectedIsAllowed =
                    languageVersionSettings.supportsFeature(LanguageFeature.AllowSealedInheritorsInDifferentFilesOfSamePackage)
                if (!(visibilityKeyword == KtTokens.PRIVATE_KEYWORD || (protectedIsAllowed && visibilityKeyword == KtTokens.PROTECTED_KEYWORD))) {
                    val factory =
                        if (protectedIsAllowed) NON_PRIVATE_OR_PROTECTED_CONSTRUCTOR_IN_SEALED else NON_PRIVATE_CONSTRUCTOR_IN_SEALED
                    trace.report(factory.on(visibilityModifier))
                }
            }
        }
    }

    private fun checkModifiersAndAnnotationsInPackageDirective(file: KtFile) {
        val packageDirective = file.packageDirective ?: return
        val modifierList = packageDirective.modifierList ?: return

        for (annotationEntry in modifierList.annotationEntries) {
            val calleeExpression = annotationEntry.calleeExpression
            if (calleeExpression != null) {
                calleeExpression.constructorReferenceExpression?.let { trace.report(UNRESOLVED_REFERENCE.on(it, it)) }
            }
        }
        annotationChecker.check(packageDirective, trace, null)
        ModifierCheckerCore.check(packageDirective, trace, descriptor = null, languageVersionSettings = languageVersionSettings)
    }

    private fun checkClass(classDescriptor: ClassDescriptorWithResolutionScopes, classOrObject: KtClassOrObject) {
        checkSupertypesForConsistency(classDescriptor, classOrObject)
        checkLocalAnnotation(classDescriptor, classOrObject)
        checkTypesInClassHeader(classOrObject)

        when (classOrObject) {
            is KtClass -> {
                checkClassButNotObject(classOrObject, classDescriptor)
                descriptorResolver.checkNamesInConstraints(
                    classOrObject, classDescriptor, classDescriptor.scopeForClassHeaderResolution, trace
                )
            }
            is KtObjectDeclaration -> {
                checkObject(classOrObject, classDescriptor)
            }
        }

        checkPrimaryConstructor(classOrObject, classDescriptor)

        checkExpectDeclarationModifiers(classOrObject, classDescriptor)
        checkContextParameters(classOrObject.modifierList)
    }

    private fun checkLocalAnnotation(classDescriptor: ClassDescriptor, classOrObject: KtClassOrObject) {
        if (classDescriptor.kind == ClassKind.ANNOTATION_CLASS && DescriptorUtils.isLocal(classDescriptor)) {
            trace.report(LOCAL_ANNOTATION_CLASS.on(languageVersionSettings, classOrObject))
        }
    }

    private fun checkTypesInClassHeader(classOrObject: KtClassOrObject) {
        fun KtTypeReference.type(): KotlinType? = trace.bindingContext.get(TYPE, this)

        for (delegationSpecifier in classOrObject.superTypeListEntries) {
            val typeReference = delegationSpecifier.typeReference ?: continue
            typeReference.type()?.let { upperBoundChecker.checkBoundsInSupertype(typeReference, it, trace, languageVersionSettings) }
        }

        if (classOrObject !is KtClass) return

        val upperBoundCheckRequests = ArrayList<DescriptorResolver.UpperBoundCheckRequest>()

        for (typeParameter in classOrObject.typeParameters) {
            val typeReference = typeParameter.extendsBound ?: continue
            val type = typeReference.type() ?: continue
            upperBoundCheckRequests.add(DescriptorResolver.UpperBoundCheckRequest(typeParameter.nameAsName, typeReference, type))
        }

        for (constraint in classOrObject.typeConstraints) {
            val typeReference = constraint.boundTypeReference ?: continue
            val type = typeReference.type() ?: continue
            val name = constraint.subjectTypeParameterName?.getReferencedNameAsName() ?: continue
            upperBoundCheckRequests.add(DescriptorResolver.UpperBoundCheckRequest(name, typeReference, type))
        }

        DescriptorResolver.checkUpperBoundTypes(trace, upperBoundCheckRequests, false)

        for (request in upperBoundCheckRequests) {
            upperBoundChecker.checkBoundsInSupertype(request.upperBound, request.upperBoundType, trace, languageVersionSettings)
        }
    }

    private fun checkOnlyOneTypeParameterBound(
        descriptor: TypeParameterDescriptor, declaration: KtTypeParameter, owner: KtTypeParameterListOwner
    ) {
        val upperBounds = descriptor.upperBounds
        val (boundsWhichAreTypeParameters, otherBounds) = upperBounds
            .map(KotlinType::constructor)
            .partition { constructor -> constructor.declarationDescriptor is TypeParameterDescriptor }
            .let { pair -> pair.first.toSet() to pair.second.toSet() }
        if (boundsWhichAreTypeParameters.size > 1 || (boundsWhichAreTypeParameters.size == 1 && otherBounds.isNotEmpty())) {
            val reportOn = if (boundsWhichAreTypeParameters.size + otherBounds.size == 2) {
                // If there's only one problematic bound (either 2 type parameter bounds, or 1 type parameter bound + 1 other bound),
                // report the diagnostic on that bound

                val allBounds: List<Pair<KtTypeReference, KotlinType?>> =
                    owner.typeConstraints
                        .filter { constraint ->
                            constraint.subjectTypeParameterName?.getReferencedNameAsName() == declaration.nameAsName
                        }
                        .mapNotNull { constraint -> constraint.boundTypeReference }
                        .map { typeReference -> typeReference to trace.bindingContext.get(TYPE, typeReference) }

                val problematicBound =
                    allBounds.firstOrNull { bound -> bound.second?.constructor != boundsWhichAreTypeParameters.first() }

                problematicBound?.first ?: declaration
            } else {
                // Otherwise report the diagnostic on the type parameter declaration
                declaration
            }

            if ((descriptor.containingDeclaration as? MemberDescriptor)?.isInlineOnly() == true) return

            trace.report(BOUNDS_NOT_ALLOWED_IF_BOUNDED_BY_TYPE_PARAMETER.on(reportOn))
        }
    }

    private fun checkSupertypesForConsistency(classifier: ClassifierDescriptor, sourceElement: PsiElement) {
        if (classifier is TypeParameterDescriptor) {
            val immediateUpperBounds = classifier.upperBounds.map { it.constructor }
            if (immediateUpperBounds.size != immediateUpperBounds.toSet().size) {
                // If there are duplicate type constructors among the _immediate_ upper bounds,
                // then the REPEATED_BOUNDS diagnostic would be already reported for those bounds of this type parameter
                return
            }
        }

        val multiMap = SubstitutionUtils.buildDeepSubstitutionMultimap(classifier.defaultType)
        for ((typeParameterDescriptor, projections) in multiMap.asMap()) {
            if (projections.size <= 1) continue

            // Immediate arguments of supertypes cannot be projected
            val conflictingTypes = projections.map { it.type }.toMutableSet()
            removeDuplicateTypes(conflictingTypes)
            if (conflictingTypes.size <= 1) continue

            val containingDeclaration = typeParameterDescriptor.containingDeclaration as? ClassDescriptor
                ?: throw AssertionError("Not a class descriptor: " + typeParameterDescriptor.containingDeclaration)
            if (sourceElement is KtClassOrObject) {
                val delegationSpecifierList = sourceElement.getSuperTypeList() ?: continue
                trace.report(
                    INCONSISTENT_TYPE_PARAMETER_VALUES.on(
                        delegationSpecifierList, typeParameterDescriptor, containingDeclaration, conflictingTypes
                    )
                )
            } else if (sourceElement is KtTypeParameter) {
                trace.report(
                    INCONSISTENT_TYPE_PARAMETER_BOUNDS.on(
                        sourceElement, typeParameterDescriptor, containingDeclaration, conflictingTypes
                    )
                )
            }
        }
    }

    private fun checkObject(declaration: KtObjectDeclaration, classDescriptor: ClassDescriptorWithResolutionScopes) {
        checkOpenMembers(classDescriptor)
        if (declaration.isLocal && !declaration.isCompanion() && !declaration.isObjectLiteral()) {
            trace.report(LOCAL_OBJECT_NOT_ALLOWED.on(declaration, classDescriptor))
        }
    }

    private fun checkClassButNotObject(aClass: KtClass, classDescriptor: ClassDescriptorWithResolutionScopes) {
        checkOpenMembers(classDescriptor)
        checkTypeParameters(aClass)
        checkTypeParameterConstraints(aClass)
        FiniteBoundRestrictionChecker.check(aClass, classDescriptor, trace)
        NonExpansiveInheritanceRestrictionChecker.check(aClass, classDescriptor, trace)

        when {
            aClass.isInterface() -> {
                checkConstructorInInterface(aClass)
                checkMethodsOfAnyInInterface(classDescriptor)
                if (aClass.isLocal && classDescriptor.containingDeclaration !is ClassDescriptor) {
                    trace.report(LOCAL_INTERFACE_NOT_ALLOWED.on(aClass, classDescriptor))
                }
            }
            classDescriptor.kind == ClassKind.ANNOTATION_CLASS -> {
                checkAnnotationClassMembers(aClass)
                checkValOnAnnotationParameter(aClass)
            }
            aClass is KtEnumEntry -> checkEnumEntry(aClass, classDescriptor)
        }
    }

    private fun checkPrimaryConstructor(classOrObject: KtClassOrObject, classDescriptor: ClassDescriptor) {
        val primaryConstructor = classDescriptor.unsubstitutedPrimaryConstructor ?: return
        val declaration = classOrObject.primaryConstructor ?: return

        for (parameter in declaration.valueParameters) {
            trace.get(PRIMARY_CONSTRUCTOR_PARAMETER, parameter)?.let {
                modifiersChecker.checkModifiersForDeclaration(parameter, it)
                LateinitModifierApplicabilityChecker.checkLateinitModifierApplicability(trace, parameter, it)
            }
        }

        if (!declaration.hasConstructorKeyword()) {
            declaration.modifierList?.let { trace.report(MISSING_CONSTRUCTOR_KEYWORD.on(it)) }
        }

        if (declaration.valueParameterList == null) {
            declaration.getConstructorKeyword()?.let { trace.report(MISSING_CONSTRUCTOR_BRACKETS.on(it)) }
        }

        if (classOrObject !is KtClass) {
            trace.report(CONSTRUCTOR_IN_OBJECT.on(declaration))
        }

        checkConstructorDeclaration(primaryConstructor, declaration)
    }

    private fun checkTypeParameters(typeParameterListOwner: KtTypeParameterListOwner) {
        for (ktTypeParameter in typeParameterListOwner.typeParameters) {
            if (!languageVersionSettings.supportsFeature(LanguageFeature.ClassTypeParameterAnnotations)) {
                AnnotationResolverImpl.reportUnsupportedAnnotationForTypeParameter(ktTypeParameter, trace, languageVersionSettings)
            }

            trace.get(TYPE_PARAMETER, ktTypeParameter)?.let { DescriptorResolver.checkConflictingUpperBounds(trace, it, ktTypeParameter) }
        }
    }

    private fun checkTypeParameterConstraints(typeParameterListOwner: KtTypeParameterListOwner) {
        val constraints = typeParameterListOwner.typeConstraints
        if (constraints.isEmpty()) return

        for (typeParameter in typeParameterListOwner.typeParameters) {
            if (typeParameter.extendsBound != null && hasConstraints(typeParameter, constraints)) {
                trace.report(MISPLACED_TYPE_PARAMETER_CONSTRAINTS.on(typeParameter))
            }
            val typeParameterDescriptor = trace.get(TYPE_PARAMETER, typeParameter) ?: continue
            checkSupertypesForConsistency(typeParameterDescriptor, typeParameter)
            checkOnlyOneTypeParameterBound(typeParameterDescriptor, typeParameter, typeParameterListOwner)
        }

        for (constraint in constraints) {
            constraint.annotationEntries.forEach {
                trace.report(ANNOTATION_IN_WHERE_CLAUSE_WARNING.on(it))
            }
        }
    }

    private fun checkConstructorInInterface(klass: KtClass) {
        klass.primaryConstructor?.let { trace.report(CONSTRUCTOR_IN_INTERFACE.on(it)) }
    }

    private fun checkMethodsOfAnyInInterface(classDescriptor: ClassDescriptorWithResolutionScopes) {
        for (declaredCallableMember in classDescriptor.declaredCallableMembers) {
            if (declaredCallableMember !is FunctionDescriptor) continue

            val declaration = DescriptorToSourceUtils.descriptorToDeclaration(declaredCallableMember)
            if (declaration !is KtNamedFunction) continue

            if (isHidingParentMemberIfPresent(declaredCallableMember)) continue

            if (isImplementingMethodOfAny(declaredCallableMember)) {
                trace.report(METHOD_OF_ANY_IMPLEMENTED_IN_INTERFACE.on(declaration))
            }
        }
    }

    private fun checkAnnotationClassMembers(classOrObject: KtClassOrObject) {
        for (declaration in classOrObject.declarations) {
            if (declaration !is KtClassOrObject ||
                !languageVersionSettings.supportsFeature(LanguageFeature.NestedClassesInAnnotations)
            ) {
                trace.report(ANNOTATION_CLASS_MEMBER.on(declaration))
            }
        }
    }

    private fun checkValOnAnnotationParameter(aClass: KtClass) {
        for (parameter in aClass.primaryConstructorParameters) {
            if (!parameter.hasValOrVar()) {
                trace.report(MISSING_VAL_ON_ANNOTATION_PARAMETER.on(parameter))
            } else if (parameter.isMutable) {
                trace.report(VAR_ANNOTATION_PARAMETER.on(parameter))
            }
        }
    }

    private fun checkOpenMembers(classDescriptor: ClassDescriptorWithResolutionScopes) {
        if (classCanHaveOpenMembers(classDescriptor)) return

        for (memberDescriptor in classDescriptor.declaredCallableMembers) {
            if (memberDescriptor.kind != CallableMemberDescriptor.Kind.DECLARATION) continue
            val member = DescriptorToSourceUtils.descriptorToDeclaration(memberDescriptor) as? KtNamedDeclaration
            if (member != null && member.hasModifier(KtTokens.OPEN_KEYWORD)) {
                if (classDescriptor.kind == ClassKind.OBJECT) {
                    trace.report(NON_FINAL_MEMBER_IN_OBJECT.on(member))
                } else {
                    trace.report(NON_FINAL_MEMBER_IN_FINAL_CLASS.on(member))
                }
            }
        }
    }

    private fun checkBackingField(property: KtProperty) {
        property.fieldDeclaration?.let {
            trace.report(EXPLICIT_BACKING_FIELDS_UNSUPPORTED.on(it))
        }
    }

    private fun checkProperty(property: KtProperty, propertyDescriptor: PropertyDescriptor) {
        val containingDeclaration = propertyDescriptor.containingDeclaration
        if (containingDeclaration is ClassDescriptor) {
            checkMemberProperty(property, propertyDescriptor, containingDeclaration)
        }
        LateinitModifierApplicabilityChecker.checkLateinitModifierApplicability(trace, property, propertyDescriptor)
        checkPropertyInitializer(property, propertyDescriptor)
        checkAccessors(property, propertyDescriptor)
        checkTypeParameterConstraints(property)
        exposedChecker.checkProperty(property, propertyDescriptor)
        shadowedExtensionChecker.checkDeclaration(property, propertyDescriptor)
        checkPropertyTypeParametersAreUsedInReceiverType(propertyDescriptor)
        checkImplicitCallableType(property, propertyDescriptor)
        checkExpectDeclarationModifiers(property, propertyDescriptor)
        checkBackingField(property)
        checkContextParameters(property.modifierList)
    }

    private fun checkExpectDeclarationModifiers(declaration: KtDeclaration, descriptor: MemberDescriptor) {
        if (!descriptor.isExpect) return

        if (DescriptorVisibilities.isPrivate(descriptor.visibility)) {
            trace.report(EXPECTED_PRIVATE_DECLARATION.on(declaration.modifierList?.getModifier(KtTokens.PRIVATE_KEYWORD) ?: declaration))
        }

        checkExpectDeclarationHasNoExternalModifier(declaration)
        if (declaration is KtFunction && languageVersionSettings.supportsFeature(LanguageFeature.MultiplatformRestrictions)) {
            declaration.modifierList?.getModifier(KtTokens.TAILREC_KEYWORD)?.let {
                trace.report(EXPECTED_TAILREC_FUNCTION.on(it))
            }
        }
    }

    private fun checkExpectDeclarationHasNoExternalModifier(declaration: KtDeclaration) {
        if (languageVersionSettings.supportsFeature(LanguageFeature.MultiplatformRestrictions)) {
            declaration.modifierList?.getModifier(KtTokens.EXTERNAL_KEYWORD)?.let {
                trace.report(EXPECTED_EXTERNAL_DECLARATION.on(it))
            }
        }
    }

    private fun checkPropertyTypeParametersAreUsedInReceiverType(descriptor: PropertyDescriptor) {
        val allTypeParameters = descriptor.typeParameters.toSet()
        val allAccessibleTypeParameters = HashSet<TypeParameterDescriptor>()

        fun addAccessibleTypeParametersFromType(type: KotlinType?) {
            TypeUtils.contains(type) {
                val declarationDescriptor = it.constructor.declarationDescriptor
                if (declarationDescriptor is TypeParameterDescriptor && declarationDescriptor in allTypeParameters) {
                    if (allAccessibleTypeParameters.add(declarationDescriptor)) {
                        declarationDescriptor.upperBounds.forEach(::addAccessibleTypeParametersFromType)
                    }
                }
                false
            }
        }
        addAccessibleTypeParametersFromType(descriptor.extensionReceiverParameter?.type)
        for (contextReceiverParameter in descriptor.contextReceiverParameters) {
            addAccessibleTypeParametersFromType(contextReceiverParameter.type)
        }

        val typeParametersInaccessibleFromReceiver = allTypeParameters - allAccessibleTypeParameters
        for (typeParameter in typeParametersInaccessibleFromReceiver) {
            val typeParameterPsi = DescriptorToSourceUtils.getSourceFromDescriptor(typeParameter)
            if (typeParameterPsi is KtTypeParameter) {
                trace.report(TYPE_PARAMETER_OF_PROPERTY_NOT_USED_IN_RECEIVER.on(typeParameterPsi))
            }
        }
    }

    private fun checkMemberProperty(
        property: KtProperty,
        propertyDescriptor: PropertyDescriptor,
        classDescriptor: ClassDescriptor
    ) {
        val modifierList = property.modifierList

        if (modifierList != null) {
            if (modifierList.hasModifier(KtTokens.ABSTRACT_KEYWORD)) {
                //has abstract modifier
                if (!classCanHaveAbstractDeclaration(classDescriptor)) {
                    trace.report(ABSTRACT_PROPERTY_IN_NON_ABSTRACT_CLASS.on(property, property.name ?: "", classDescriptor))
                    return
                }
            } else if (classDescriptor.kind == ClassKind.INTERFACE &&
                modifierList.hasModifier(KtTokens.OPEN_KEYWORD) &&
                propertyDescriptor.modality == Modality.ABSTRACT
            ) {
                trace.report(REDUNDANT_OPEN_IN_INTERFACE.on(property))
            }
        }

        if (propertyDescriptor.modality == Modality.ABSTRACT) {
            property.initializer?.let { trace.report(ABSTRACT_PROPERTY_WITH_INITIALIZER.on(it)) }
            property.delegate?.let { trace.report(ABSTRACT_DELEGATED_PROPERTY.on(it)) }
            val getter = property.getter
            if (getter != null && getter.hasBody()) {
                trace.report(ABSTRACT_PROPERTY_WITH_GETTER.on(getter))
            }
            val setter = property.setter
            if (setter != null && setter.hasBody()) {
                trace.report(ABSTRACT_PROPERTY_WITH_SETTER.on(setter))
            }
        }
    }

    private fun checkPropertyInitializer(property: KtProperty, propertyDescriptor: PropertyDescriptor) {
        val hasAnyAccessorImplementation = propertyDescriptor.hasAnyAccessorImplementation()

        val containingDeclaration = propertyDescriptor.containingDeclaration
        val inInterface = DescriptorUtils.isInterface(containingDeclaration)
        if (propertyDescriptor.modality == Modality.ABSTRACT) {
            if (!property.hasDelegateExpressionOrInitializer() && property.typeReference == null) {
                trace.report(PROPERTY_WITH_NO_TYPE_NO_INITIALIZER.on(property))
            }
            if (inInterface && property.hasModifier(KtTokens.PRIVATE_KEYWORD) && !property.hasModifier(KtTokens.ABSTRACT_KEYWORD)) {
                trace.report(PRIVATE_PROPERTY_IN_INTERFACE.on(property))
            }
            return
        }

        val backingFieldRequired = trace.bindingContext.get(BACKING_FIELD_REQUIRED, propertyDescriptor) ?: false
        if (inInterface && backingFieldRequired && hasAnyAccessorImplementation) {
            trace.report(BACKING_FIELD_IN_INTERFACE.on(property))
        }

        val initializer = property.initializer
        val delegate = property.delegate
        val isExpect = propertyDescriptor.isExpect
        if (initializer != null) {
            when {
                inInterface -> trace.report(PROPERTY_INITIALIZER_IN_INTERFACE.on(initializer))
                isExpect -> trace.report(EXPECTED_PROPERTY_INITIALIZER.on(initializer))
                !backingFieldRequired -> trace.report(PROPERTY_INITIALIZER_NO_BACKING_FIELD.on(initializer))
                property.receiverTypeReference != null -> trace.report(EXTENSION_PROPERTY_WITH_BACKING_FIELD.on(initializer))
                property.contextReceivers.isNotEmpty() -> trace.report(CONTEXT_RECEIVERS_WITH_BACKING_FIELD.on(initializer))
            }
        } else if (delegate != null) {
            if (inInterface) {
                trace.report(DELEGATED_PROPERTY_IN_INTERFACE.on(delegate))
            } else if (isExpect) {
                trace.report(EXPECTED_DELEGATED_PROPERTY.on(delegate))
            } else if (property.receiverTypeReference != null) {
                val delegatedPropertyResolvedCall = trace.get(DELEGATED_PROPERTY_RESOLVED_CALL, propertyDescriptor.getter)
                val provideDelegateResolvedCall = trace.get(PROVIDE_DELEGATE_RESOLVED_CALL, propertyDescriptor)
                val delegateType = provideDelegateResolvedCall?.resultingDescriptor?.returnType
                    ?: delegate.expression?.let { trace.getType(it) } ?: return

                val delegateClassDescriptor =
                    delegateType.lowerIfFlexible().unwrap().constructor.declarationDescriptor.let {
                        it as? ClassDescriptor ?: (it as? TypeAliasDescriptor)?.expandedType?.constructor?.declarationDescriptor
                    } as? ClassDescriptor ?: return
                val delegateClassScope by lazy { delegateClassDescriptor.unsubstitutedMemberScope }
                val dispatchReceiverType = delegatedPropertyResolvedCall?.dispatchReceiver?.type
                val extensionReceiverType = delegatedPropertyResolvedCall?.extensionReceiver?.type
                val usedParameter = propertyDescriptor.typeParameters.find { typeParameter ->
                    dispatchReceiverType?.contains { it.constructor == typeParameter.typeConstructor } == true ||
                            extensionReceiverType?.contains { it.constructor == typeParameter.typeConstructor } == true
                }
                if (usedParameter != null) {
                    var propertyWithTypeParameterTypeFound = false
                    val names = delegateClassScope.getVariableNames()
                    for (name in names) {
                        delegateClassScope.getContributedVariables(name, KotlinLookupLocation(delegate))
                            .forEach { propertyInDelegateClass ->
                                if (propertyInDelegateClass.type.contains { it.constructor.declarationDescriptor is TypeParameterDescriptor }) {
                                    propertyWithTypeParameterTypeFound = true
                                    return@forEach
                                }
                            }
                    }
                    if (!propertyWithTypeParameterTypeFound) return
                    trace.report(
                        DELEGATE_USES_EXTENSION_PROPERTY_TYPE_PARAMETER.on(
                            languageVersionSettings,
                            delegate,
                            usedParameter.name.asString()
                        )
                    )
                }
            }
        } else {
            val isUninitialized = trace.bindingContext.get(IS_UNINITIALIZED, propertyDescriptor) ?: false
            val isExternal = propertyDescriptor.isEffectivelyExternal()
            if (backingFieldRequired && !inInterface && !propertyDescriptor.isLateInit && !isExpect && isUninitialized && !isExternal) {
                if (propertyDescriptor.extensionReceiverParameter != null && !hasAnyAccessorImplementation) {
                    trace.report(EXTENSION_PROPERTY_MUST_HAVE_ACCESSORS_OR_BE_ABSTRACT.on(property))
                } else if (diagnosticSuppressor.shouldReportNoBody(propertyDescriptor)) {
                    val isOpenValDeferredInitDeprecationWarning =
                        !languageVersionSettings.supportsFeature(LanguageFeature.ProhibitOpenValDeferredInitialization) &&
                                propertyDescriptor.getEffectiveModality(languageVersionSettings) == Modality.OPEN &&
                                !propertyDescriptor.isVar &&
                                trace.bindingContext.get(IS_DEFINITELY_NOT_ASSIGNED_IN_CONSTRUCTOR, propertyDescriptor) == false
                    // KT-61228
                    val isFalsePositiveDeferredInitDeprecationWarning = isOpenValDeferredInitDeprecationWarning &&
                            propertyDescriptor.getEffectiveModality() == Modality.FINAL
                    if (!isFalsePositiveDeferredInitDeprecationWarning) {
                        reportMustBeInitialized(
                            propertyDescriptor,
                            containingDeclaration,
                            hasAnyAccessorImplementation,
                            property,
                            isOpenValDeferredInitDeprecationWarning,
                            languageVersionSettings,
                            trace
                        )
                    }
                }
            } else if (property.typeReference == null && !languageVersionSettings.supportsFeature(LanguageFeature.ShortSyntaxForPropertyGetters)) {
                trace.report(
                    UNSUPPORTED_FEATURE.on(
                        property,
                        LanguageFeature.ShortSyntaxForPropertyGetters to languageVersionSettings
                    )
                )
            } else if (noExplicitTypeOrGetterType(property)) {
                trace.report(PROPERTY_WITH_NO_TYPE_NO_INITIALIZER.on(property))
            }

            if (propertyDescriptor.isLateInit) {
                if (propertyDescriptor.isExpect) {
                    trace.report(EXPECTED_LATEINIT_PROPERTY.on(property.modifierList?.getModifier(KtTokens.LATEINIT_KEYWORD) ?: property))
                }
                if (backingFieldRequired && !inInterface && !isUninitialized && trace[MUST_BE_LATEINIT, propertyDescriptor] != true) {
                    trace.report(UNNECESSARY_LATEINIT.on(property))
                }
            }
        }
    }

    private fun reportMustBeInitialized(
        propertyDescriptor: PropertyDescriptor,
        containingDeclaration: DeclarationDescriptor,
        hasAnyAccessorImplementation: Boolean,
        property: KtProperty,
        isOpenValDeferredInitDeprecationWarning: Boolean,
        languageVersionSettings: LanguageVersionSettings,
        trace: BindingTrace,
    ) {
        check(propertyDescriptor.getEffectiveModality(languageVersionSettings) != Modality.ABSTRACT) {
            "${::reportMustBeInitialized.name} isn't called for abstract properties"
        }
        val suggestMakingItFinal = containingDeclaration is ClassDescriptor &&
                !propertyDescriptor.hasSetterAccessorImplementation() &&
                propertyDescriptor.getEffectiveModality(languageVersionSettings) != Modality.FINAL &&
                trace.bindingContext.get(IS_DEFINITELY_NOT_ASSIGNED_IN_CONSTRUCTOR, propertyDescriptor) == false
        val suggestMakingItAbstract = containingDeclaration is ClassDescriptor && !hasAnyAccessorImplementation
        if (isOpenValDeferredInitDeprecationWarning && !suggestMakingItFinal && suggestMakingItAbstract) {
            error("Not reachable case. Every \"open val + deferred init\" case that could be made `abstract`, also could be made `final`")
        }
        val isMissedMustBeInitializedDeprecationWarning =
            !languageVersionSettings.supportsFeature(LanguageFeature.ProhibitMissedMustBeInitializedWhenThereIsNoPrimaryConstructor) &&
                    containingDeclaration is ClassDescriptor &&
                    containingDeclaration.constructors.none { it.isPrimary } &&
                    trace.bindingContext.get(IS_DEFINITELY_NOT_ASSIGNED_IN_CONSTRUCTOR, propertyDescriptor) == false
        val factory = when {
            suggestMakingItFinal && suggestMakingItAbstract -> MUST_BE_INITIALIZED_OR_FINAL_OR_ABSTRACT
            suggestMakingItFinal -> MUST_BE_INITIALIZED_OR_BE_FINAL
            suggestMakingItAbstract -> MUST_BE_INITIALIZED_OR_BE_ABSTRACT
            else -> MUST_BE_INITIALIZED
        }
        trace.report(
            when (isMissedMustBeInitializedDeprecationWarning || isOpenValDeferredInitDeprecationWarning) {
                true -> factory.deprecationWarning
                false -> factory
            }.on(property)
        )
    }

    private val DiagnosticFactory0<KtProperty>.deprecationWarning: DiagnosticFactory0<KtProperty>
        get() = when (this) {
            MUST_BE_INITIALIZED -> MUST_BE_INITIALIZED_WARNING
            MUST_BE_INITIALIZED_OR_BE_ABSTRACT -> MUST_BE_INITIALIZED_OR_BE_ABSTRACT_WARNING
            MUST_BE_INITIALIZED_OR_BE_FINAL -> MUST_BE_INITIALIZED_OR_BE_FINAL_WARNING
            MUST_BE_INITIALIZED_OR_FINAL_OR_ABSTRACT -> MUST_BE_INITIALIZED_OR_FINAL_OR_ABSTRACT_WARNING
            else -> error("Only MUST_BE_INITIALIZED is supported")
        }

    private fun noExplicitTypeOrGetterType(property: KtProperty) =
        property.typeReference == null
                && (property.getter == null || (property.getter!!.hasBlockBody() && property.getter!!.returnTypeReference == null))

    fun checkFunction(function: KtNamedFunction, functionDescriptor: SimpleFunctionDescriptor) {
        val typeParameterList = function.typeParameterList
        val nameIdentifier = function.nameIdentifier
        if (typeParameterList != null && nameIdentifier != null &&
            typeParameterList.textRange.startOffset > nameIdentifier.textRange.startOffset
        ) {
            trace.report(DEPRECATED_TYPE_PARAMETER_SYNTAX.on(typeParameterList))
        }
        checkTypeParameterConstraints(function)
        checkImplicitCallableType(function, functionDescriptor)
        exposedChecker.checkFunction(function, functionDescriptor)
        checkVarargParameters(trace, functionDescriptor)

        val containingDescriptor = functionDescriptor.containingDeclaration
        val hasAbstractModifier = function.hasModifier(KtTokens.ABSTRACT_KEYWORD)
        val hasExternalModifier = functionDescriptor.isEffectivelyExternal()

        if (containingDescriptor is ClassDescriptor) {
            val inInterface = containingDescriptor.kind == ClassKind.INTERFACE
            val isExpectClass = containingDescriptor.isExpect
            if (hasAbstractModifier && !classCanHaveAbstractDeclaration(containingDescriptor)) {
                trace.report(ABSTRACT_FUNCTION_IN_NON_ABSTRACT_CLASS.on(function, functionDescriptor.name.asString(), containingDescriptor))
            }
            val hasBody = function.hasBody()
            if (hasBody && hasAbstractModifier) {
                trace.report(ABSTRACT_FUNCTION_WITH_BODY.on(function, functionDescriptor))
            }
            if (!hasBody && inInterface) {
                if (function.hasModifier(KtTokens.PRIVATE_KEYWORD)) {
                    trace.report(PRIVATE_FUNCTION_WITH_NO_BODY.on(function, functionDescriptor))
                }
                if (!containingDescriptor.isExpect && !hasAbstractModifier && function.hasModifier(KtTokens.OPEN_KEYWORD)) {
                    trace.report(REDUNDANT_OPEN_IN_INTERFACE.on(function))
                }
            }
            if (!hasBody && !hasAbstractModifier && !hasExternalModifier && !inInterface && !isExpectClass &&
                diagnosticSuppressor.shouldReportNoBody(functionDescriptor)
            ) {
                trace.report(NON_ABSTRACT_FUNCTION_WITH_NO_BODY.on(function, functionDescriptor))
            }
        } else /* top-level only */ {
            if (!function.hasBody() && !hasAbstractModifier && !hasExternalModifier && !functionDescriptor.isExpect &&
                diagnosticSuppressor.shouldReportNoBody(functionDescriptor)
            ) {
                trace.report(NON_MEMBER_FUNCTION_NO_BODY.on(function, functionDescriptor))
            }
        }

        if (functionDescriptor.isExpect) {
            checkExpectedFunction(function, functionDescriptor)
        }

        shadowedExtensionChecker.checkDeclaration(function, functionDescriptor)
        checkContextParameters(function.modifierList)
    }

    private fun checkExpectedFunction(function: KtNamedFunction, functionDescriptor: FunctionDescriptor) {
        if (function.hasBody()) {
            trace.report(EXPECTED_DECLARATION_WITH_BODY.on(function))
        }

        checkExpectDeclarationModifiers(function, functionDescriptor)
    }

    private fun checkImplicitCallableType(declaration: KtCallableDeclaration, descriptor: CallableDescriptor) {
        descriptor.returnType?.unwrap()?.let {
            val target = declaration.nameIdentifier ?: declaration
            if (declaration.typeReference == null) {
                if (it.isNothing() && !declaration.hasModifier(KtTokens.OVERRIDE_KEYWORD)) {
                    trace.report(
                        (if (declaration is KtProperty) IMPLICIT_NOTHING_PROPERTY_TYPE else IMPLICIT_NOTHING_RETURN_TYPE).on(target)
                    )
                }
                if (it.contains { type -> type.constructor is IntersectionTypeConstructor }) {
                    trace.report(IMPLICIT_INTERSECTION_TYPE.on(target, it))
                }
            } else if (it.isNothing() && it is AbbreviatedType) {
                trace.report(
                    (if (declaration is KtProperty) ABBREVIATED_NOTHING_PROPERTY_TYPE else ABBREVIATED_NOTHING_RETURN_TYPE).on(target)
                )
            }
        }
    }

    private fun checkAccessors(property: KtProperty, propertyDescriptor: PropertyDescriptor) {
        for (accessorDescriptor in propertyDescriptor.accessors) {
            val accessor = if (accessorDescriptor is PropertyGetterDescriptor) property.getter else property.setter
            if (accessor != null) {
                modifiersChecker.checkModifiersForDeclaration(accessor, accessorDescriptor)
                identifierChecker.checkDeclaration(accessor, trace)
            } else {
                modifiersChecker.runDeclarationCheckers(property, accessorDescriptor)
            }
        }
        checkAccessor(propertyDescriptor, property.getter, propertyDescriptor.getter)
        checkAccessor(propertyDescriptor, property.setter, propertyDescriptor.setter)
    }

    private fun reportVisibilityModifierDiagnostics(tokens: Collection<PsiElement>, diagnostic: DiagnosticFactory0<PsiElement>) {
        for (token in tokens) {
            trace.report(diagnostic.on(token))
        }
    }

    private fun checkAccessor(
        propertyDescriptor: PropertyDescriptor,
        accessor: KtPropertyAccessor?,
        accessorDescriptor: PropertyAccessorDescriptor?
    ) {
        if (accessor == null || accessorDescriptor == null) return
        if (propertyDescriptor.isExpect && accessor.hasBody()) {
            trace.report(EXPECTED_DECLARATION_WITH_BODY.on(accessor))
        }

        val accessorModifierList = accessor.modifierList ?: return
        val tokens = modifiersChecker.getTokensCorrespondingToModifiers(
            accessorModifierList,
            setOf(KtTokens.PUBLIC_KEYWORD, KtTokens.PROTECTED_KEYWORD, KtTokens.PRIVATE_KEYWORD, KtTokens.INTERNAL_KEYWORD)
        )
        if (accessor.isGetter) {
            if (accessorDescriptor.visibility != propertyDescriptor.visibility) {
                reportVisibilityModifierDiagnostics(tokens.values, GETTER_VISIBILITY_DIFFERS_FROM_PROPERTY_VISIBILITY)
            } else {
                reportVisibilityModifierDiagnostics(tokens.values, REDUNDANT_MODIFIER_IN_GETTER)
            }
        } else {
            if (propertyDescriptor.isOverridable
                && accessorDescriptor.visibility == DescriptorVisibilities.PRIVATE
                && propertyDescriptor.visibility != DescriptorVisibilities.PRIVATE
            ) {
                if (propertyDescriptor.modality == Modality.ABSTRACT) {
                    reportVisibilityModifierDiagnostics(tokens.values, PRIVATE_SETTER_FOR_ABSTRACT_PROPERTY)
                } else {
                    reportVisibilityModifierDiagnostics(tokens.values, PRIVATE_SETTER_FOR_OPEN_PROPERTY)
                }
            } else {
                val compare = DescriptorVisibilities.compare(accessorDescriptor.visibility, propertyDescriptor.visibility)
                if (compare == null || compare > 0) {
                    reportVisibilityModifierDiagnostics(tokens.values, SETTER_VISIBILITY_INCONSISTENT_WITH_PROPERTY_VISIBILITY)
                }
            }
        }
        if (propertyDescriptor.isExpect) {
            checkExpectDeclarationHasNoExternalModifier(accessor)
        }
    }

    private fun checkEnumEntry(enumEntry: KtEnumEntry, enumEntryClass: ClassDescriptor) {
        val enumClass = enumEntryClass.containingDeclaration as ClassDescriptor
        if (DescriptorUtils.isEnumClass(enumClass)) {
            if (enumClass.isExpect) {
                if (enumEntry.getBody() != null) {
                    trace.report(EXPECTED_ENUM_ENTRY_WITH_BODY.on(enumEntry))
                }
            }
        } else {
            assert(DescriptorUtils.isInterface(enumClass)) { "Enum entry should be declared in enum class: " + enumEntryClass }
        }
    }

    private fun checkVarargParameters(trace: BindingTrace, callableDescriptor: CallableDescriptor) {
        val varargParameters = callableDescriptor.valueParameters.filter { it.varargElementType != null }

        if (varargParameters.size > 1) {
            for (parameter in varargParameters) {
                val parameterDeclaration = DescriptorToSourceUtils.descriptorToDeclaration(parameter) as? KtParameter ?: continue
                trace.report(MULTIPLE_VARARG_PARAMETERS.on(parameterDeclaration))
            }
        }

        val nullableNothing = callableDescriptor.builtIns.nullableNothingType
        for (parameter in varargParameters) {
            val varargElementType = parameter.varargElementType!!.upperIfFlexible()
            if (KotlinTypeChecker.DEFAULT.isSubtypeOf(varargElementType, nullableNothing) ||
                (varargElementType.isInlineClassType() &&
                        !UnsignedTypes.isUnsignedType(varargElementType) &&
                        !KotlinBuiltIns.isPrimitiveTypeOrNullablePrimitiveType(varargElementType))
            ) {
                val parameterDeclaration = DescriptorToSourceUtils.descriptorToDeclaration(parameter) as? KtParameter ?: continue
                trace.report(FORBIDDEN_VARARG_PARAMETER_TYPE.on(parameterDeclaration, varargElementType))
            }
        }
    }

    private fun checkContextParameters(modifierList: KtModifierList?) {
        val contextReceiverLists = modifierList?.contextReceiverLists ?: return
        contextReceiverLists.forEach { contextReceiverList ->
            if (contextReceiverList.contextParameters().isNotEmpty()) {
                trace.report(CONTEXT_PARAMETERS_UNSUPPORTED.on(contextReceiverList))
            }
        }

        if (contextReceiverLists.size > 1) {
            trace.report(MULTIPLE_CONTEXT_LISTS.on(modifierList))
        }
    }

    companion object {

        private fun removeDuplicateTypes(conflictingTypes: MutableSet<KotlinType>) {
            val iterator = conflictingTypes.iterator()
            while (iterator.hasNext()) {
                val type = iterator.next()
                for (otherType in conflictingTypes) {
                    val subtypeOf = KotlinTypeChecker.DEFAULT.equalTypes(type, otherType)
                    if (type !== otherType && subtypeOf) {
                        iterator.remove()
                        break
                    }
                }
            }
        }

        private fun hasConstraints(typeParameter: KtTypeParameter, constraints: List<KtTypeConstraint>): Boolean {
            if (typeParameter.name == null) return false
            return constraints.any { it.subjectTypeParameterName?.text == typeParameter.name }
        }

        private val METHOD_OF_ANY_NAMES = ImmutableSet.of("toString", "hashCode", "equals")

        private fun isImplementingMethodOfAny(member: CallableMemberDescriptor): Boolean {
            if (!METHOD_OF_ANY_NAMES.contains(member.name.asString())) return false
            if (member.modality == Modality.ABSTRACT) return false

            return isImplementingMethodOfAnyInternal(member, HashSet<ClassDescriptor>())
        }

        private fun isImplementingMethodOfAnyInternal(
            member: CallableMemberDescriptor,
            visitedClasses: MutableSet<ClassDescriptor>
        ): Boolean {
            for (overridden in member.overriddenDescriptors) {
                val containingDeclaration = overridden.containingDeclaration
                if (containingDeclaration !is ClassDescriptor) continue
                if (visitedClasses.contains(containingDeclaration)) continue

                if (DescriptorUtils.getFqName(containingDeclaration) == StandardNames.FqNames.any) {
                    return true
                }

                if (isHidingParentMemberIfPresent(overridden)) continue

                visitedClasses.add(containingDeclaration)

                if (isImplementingMethodOfAnyInternal(overridden, visitedClasses)) {
                    return true
                }
            }

            return false
        }

        private fun isHidingParentMemberIfPresent(member: CallableMemberDescriptor): Boolean {
            val declaration = DescriptorToSourceUtils.descriptorToDeclaration(member) as? KtNamedDeclaration ?: return false
            val modifierList = declaration.modifierList ?: return true
            return !modifierList.hasModifier(KtTokens.OVERRIDE_KEYWORD)
        }

        private fun PropertyDescriptor.hasSetterAccessorImplementation(): Boolean = setter?.hasBody() == true
        fun PropertyDescriptor.hasAnyAccessorImplementation(): Boolean = hasSetterAccessorImplementation() || getter?.hasBody() == true
    }
}

private fun PropertyDescriptor.getEffectiveModality(): Modality =
    when (modality == Modality.OPEN && (containingDeclaration as? ClassDescriptor)?.modality == Modality.FINAL) {
        true -> Modality.FINAL
        false -> modality
    }

fun PropertyDescriptor.getEffectiveModality(languageVersionSettings: LanguageVersionSettings): Modality =
    when (languageVersionSettings.supportsFeature(LanguageFeature.TakeIntoAccountEffectivelyFinalInMustBeInitializedCheck)) {
        true -> getEffectiveModality()
        false -> modality
    }
