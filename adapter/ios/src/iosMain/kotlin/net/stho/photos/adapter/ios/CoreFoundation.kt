@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package net.stho.photos.adapter.ios

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.posix.memcpy
import kotlinx.cinterop.ptr

/**
 * A Keychain query, and the ownership rules that come with one.
 *
 * Security.framework takes `CFDictionary`, and Core Foundation is manually reference counted:
 * every string this builds is created here and has to be released here. Collecting them and
 * releasing on [close] is what makes that a property of the block rather than of remembering,
 * which matters because each query is built on a path that can also throw.
 */
internal class CfQuery : AutoCloseable {

    private val owned = mutableListOf<COpaquePointer>()

    val ref: CFMutableDictionaryRef = requireNotNull(
        CFDictionaryCreateMutable(
            allocator = null,
            capacity = 0,
            keyCallBacks = kCFTypeDictionaryKeyCallBacks.ptr,
            valueCallBacks = kCFTypeDictionaryValueCallBacks.ptr,
        ),
    ) { "Core Foundation would not allocate a dictionary" }

    /** A constant, which Core Foundation owns and we must not release. */
    fun put(key: COpaquePointer?, value: COpaquePointer?) {
        CFDictionaryAddValue(ref, key, value)
    }

    /** A string of ours, retained into the dictionary and released with it. */
    fun putString(key: COpaquePointer?, value: String) {
        val bridged = requireNotNull(CFBridgingRetain(value as NSString))
        owned += bridged
        CFDictionaryAddValue(ref, key, bridged)
    }

    /** Bytes of ours, likewise. */
    fun putData(key: COpaquePointer?, value: NSData) {
        val bridged = requireNotNull(CFBridgingRetain(value))
        owned += bridged
        CFDictionaryAddValue(ref, key, bridged)
    }

    /** Something already created by a `…Create…` call, whose reference this now owns. */
    fun own(value: COpaquePointer): COpaquePointer = value.also { owned += it }

    override fun close() {
        owned.forEach(::CFRelease)
        CFRelease(ref)
    }
}

/** `NSData` to Kotlin bytes, in one copy. */
internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    return ByteArray(size).apply {
        usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    }
}
