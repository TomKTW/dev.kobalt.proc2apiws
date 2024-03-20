/*
 * dev.kobalt.proc2apiws
 * Copyright (C) 2024 Tom.K
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dev.kobalt.proc2apiws.web.inputstream

import java.io.Closeable
import java.io.FilterInputStream
import java.io.InputStream

/** Input stream with maximum size restriction. This is used to prevent receiving input streams that could take too many resources. */
class LimitedSizeInputStream(
    /** The input stream that is being processed. */
    inputStream: InputStream,
    /** Maximum size of input stream in bytes. If it goes beyond this limit, an exception is thrown. */
    private val maxSize: Long
) : FilterInputStream(inputStream), Closeable {

    /** Current size of input stream in bytes. It's updated until stream reaches the end. */
    private var currentSize: Long = 0

    /** Verifies if the current size is under maximum size of the stream. Otherwise, an exception is thrown.*/
    private fun checkSize() {
        if (currentSize > maxSize) throw InputStreamSizeLimitReachedException(maxSize, currentSize)
    }

    /** Reads a byte from input stream. Stream size is checked every time. @see [FilterInputStream.read] */
    override fun read(): Int {
        return super.read().also { if (it != -1) currentSize++; checkSize() }
    }

    /** Reads bytes from input stream. Stream size is checked every time. @see [FilterInputStream.read] */
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        return super.read(b, off, len).also { if (it > 0) currentSize += it.toLong(); checkSize() }
    }

    /** Closes input stream. @see [FilterInputStream.close] */
    override fun close() {
        super.close()
    }

}