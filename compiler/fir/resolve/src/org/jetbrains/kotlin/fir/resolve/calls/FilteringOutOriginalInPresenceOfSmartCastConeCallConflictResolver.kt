/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.calls

import org.jetbrains.kotlin.utils.sure

object FilteringOutOriginalInPresenceOfSmartCastConeCallConflictResolver : ConeCallConflictResolver() {
    override fun chooseMaximallySpecificCandidates(
        candidates: Set<Candidate>,
        discriminateGenerics: Boolean,
        discriminateAbstracts: Boolean
    ): Set<Candidate> {
        val (originalIfSmartCastPresent, other) = candidates.partition { it.isFromOriginalTypeInPresenceOfSmartCast }

        // If we have both successful candidates from smart cast and original, use the former one as they might have more correct return type
        if (originalIfSmartCastPresent.isNotEmpty() && other.isNotEmpty()) return other.toSet().discriminateByInvokeVariablePriority()

        return candidates.discriminateByInvokeVariablePriority()
    }

    // See the relevant test at testData/diagnostics/tests/resolve/invoke/kt9517.kt
    private fun Set<Candidate>.discriminateByInvokeVariablePriority(): Set<Candidate> {
        if (size <= 1) return this

        // Resulting successful candidates should always belong to the same tower group.
        // Thus, if one of them is not variable + invoke, it should be applied to others, too.
        if (first().callInfo.candidateForCommonInvokeReceiver == null) return this

        val (originalIfSmartCastPresent, other) = partition {
            it.callInfo.candidateForCommonInvokeReceiver.sure {
                "If one candidate within a group is variable+invoke, other should be the same, but $it found"
            }.isFromOriginalTypeInPresenceOfSmartCast
        }

        if (originalIfSmartCastPresent.isNotEmpty() && other.isNotEmpty()) return other.toSet()

        return this
    }
}
