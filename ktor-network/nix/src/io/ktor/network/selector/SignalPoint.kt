/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.network.selector

import io.ktor.network.interop.*
import io.ktor.network.util.*
import io.ktor.utils.io.core.*
import io.ktor.utils.io.errors.*
import kotlinx.atomicfu.*
import kotlinx.atomicfu.locks.*
import kotlinx.cinterop.*
import platform.posix.*

internal class SignalPoint : Closeable {
    private val readDescriptor: Int
    private val writeDescriptor: Int
    private var remaining: Int by atomic(0)
    private val lock = SynchronizedObject()
    private var closed = false

    val selectionDescriptor: Int
        get() = readDescriptor

    init {
        val (read, write) = memScoped {
            val pipeDescriptors = allocArray<IntVar>(2)
            pipe(pipeDescriptors).check()

            repeat(2) { index ->
                makeNonBlocking(pipeDescriptors[index])
            }

            Pair(pipeDescriptors[0], pipeDescriptors[1])
        }

        readDescriptor = read
        writeDescriptor = write
    }

    fun check() {
        synchronized(lock) {
            if (closed) return@synchronized
            while (remaining > 0) {
                remaining -= read_from_pipe(readDescriptor)
            }
        }
    }

    @OptIn(UnsafeNumber::class)
    fun signal() {
        synchronized(lock) {
            if (closed) return@synchronized

            if (remaining > 0) return

            memScoped {
                val array = allocArray<ByteVar>(1)
                array[0] = 7
                // note: here we ignore the result of write intentionally
                // we simply don't care whether the buffer is full or the pipe is already closed
                val result = write(writeDescriptor, array, 1.convert())
                if (result < 0) return

                remaining += result.toInt()
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return@synchronized
            closed = true

            close(writeDescriptor)
            read_from_pipe(readDescriptor)
            close(readDescriptor)
        }
    }

    private fun makeNonBlocking(descriptor: Int) {
        fcntl(descriptor, F_SETFL, fcntl(descriptor, F_GETFL) or O_NONBLOCK).check()
    }
}
