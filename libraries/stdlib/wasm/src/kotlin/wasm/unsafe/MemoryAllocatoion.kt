/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.wasm.unsafe

import kotlin.wasm.internal.WasmOp
import kotlin.wasm.internal.implementedAsIntrinsic
import kotlin.wasm.internal.unsafeGetScratchRawMemory

/**
 * WebAssembly linear memory allocator.
 */
public interface MemoryAllocator {
    /**
     * Allocate a block of linear memory of given [size] in bytes.
     *
     * @return an address of allocated memory. It is guaranteed to be a multiple of 8.
     */
    fun allocate(size: Int): Ptr
}

/**
 * Run a [block] of code, providing it a temporary [MemoryAllocator] as an argument, and return its result.
 *
 * Free all memory allocated with provided allocator after running the [block].
 *
 * This function is intened to facilitate the exchange of values with outside world through linear memory.
 * For example:
 *
 *    val buffer_size = ...
 *    withScopedMemoryAllocator { allocator ->
 *        val buffer_address = allocator.allocate(buffer_size)
 *        importedWasmFunctionThatWritesToBuffer(buffer_address, buffer_size)
 *        return readDataFromBufferIntoManagedKotlinMemory(buffer_address, buffer_size)
 *    }
 *
 * WARNING! Addresses leaked outside of [block] scope become invalid.
 *
 * WARNING! All allocations are kept alive while the [block] is running.
 *          Try to limit the scope to a few allocations that have to be used together.
 *          Prefer splitting allocations into separate scopes if possible.
 *
 * WARNING! Nested call [withScopedMemoryAllocator] will throw [IllegalStateException] will be trown.
 *          Standard library may use this allocator to facilitaty JS interop. For example to copy String.
 *
 * WARNING! Accessing allocator outside of the [block] scope will throw [IllegalStateException].
 */
public inline fun <T> withScopedMemoryAllocator(
    block: (allocator: MemoryAllocator) -> T
): T {
    check(!inScopedMemoryAllocatorBlock) { "Calls to withScopedMemoryAllocator can't be nested" }
    val allocator = ScopedMemoryAllocator()
    val result = try {
        block(allocator)
    } finally {
        allocator.destroy()
    }
    return result
}


@PublishedApi
internal var inScopedMemoryAllocatorBlock: Boolean = false

@PublishedApi
internal class ScopedMemoryAllocator : MemoryAllocator {
    private var destroyed = false
    private var availableAddress: Ptr = unsafeGetScratchRawMemory(0/*unused*/)

    override fun allocate(size: Int): Ptr {
        check(!destroyed) { "ScopedMemoryAllocator is destroyed when out of scope" }

        // Pad available address to align it to 8
        val align = 8
        availableAddress = (availableAddress + align - 1) and (align - 1).inv()
        check(availableAddress % 8 == 0)
        return availableAddress
    }

    fun destroy() {
        destroyed = true
    }
}

private const val WASM_PAGE_SIZE_IN_BYTES = 65536  // 64 KiB

/**
 * Current linear memory size in pages
 */
@WasmOp(WasmOp.MEMORY_SIZE)
internal fun wasmMemorySize(): Int =
    implementedAsIntrinsic

/**
 * Grow memory by a given delta (in pages).
 * Return the previous size, or -1 if enough memory cannot be allocated.
 */
@WasmOp(WasmOp.MEMORY_GROW)
internal fun wasmMemoryGrow(delta: Int): Int =
    implementedAsIntrinsic