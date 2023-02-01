/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.js

internal typealias BitMask = IntArray

internal fun BitMask(activeBits: Array<Int>): BitMask {
    return if (activeBits.size == 0) {
        IntArray(0)
    } else {
        val max: Int = JsMath.asDynamic().max.apply(null, activeBits)
        val intArray = IntArray((max shr 5) + 1)
        for (activeBit in activeBits) {
            val numberIndex = activeBit shr 5
            val positionInNumber = activeBit and 31
            val numberWithSettledBit = 1 shl positionInNumber
            intArray[numberIndex] = intArray[numberIndex] or numberWithSettledBit
        }
        intArray
    }
}

internal fun BitMask.isBitSet(possibleActiveBit: Int): Boolean {
    val numberIndex = possibleActiveBit shr 5
    if (numberIndex > size) return false
    val positionInNumber = possibleActiveBit and 31
    val numberWithSettledBit = 1 shl positionInNumber
    return get(numberIndex) and numberWithSettledBit != 0
}

internal fun CompositeBitMask(capacity: Int, masks: dynamic): BitMask {
    return IntArray(capacity) { i ->
        masks.reduce({ acc: Int, it: BitMask ->
                         if (i >= it.size)
                             acc
                         else
                             acc or it[i]
                     }, 0)
    }
}

internal fun implement(vararg interfaces: dynamic): BitMask {
    var maxSize = 1
    val masks = js("[]")

    for (i in interfaces) {
        var currentSize = maxSize
        val imask: BitMask? = i.prototype.`$imask$` ?: i.`$imask$`

        if (imask != null) {
            masks.push(imask)
            currentSize = imask.size
        }

        val iid: Int? = i.`$metadata$`.iid
        val iidImask: BitMask? = iid?.let { BitMask(arrayOf(it)) }

        if (iidImask != null) {
            masks.push(iidImask)
            currentSize = JsMath.max(currentSize, iidImask.size)
        }

        if (currentSize > maxSize) {
            maxSize = currentSize
        }
    }

    return CompositeBitMask(maxSize, masks)
}

