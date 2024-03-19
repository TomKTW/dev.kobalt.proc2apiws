/*
 * dev.kobalt.proc2apiws
 * Copyright (C) 2022 Tom.K
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

class LimitedSizeInputStream(
    inputStream: InputStream,
    private val maxSize: Long
) : FilterInputStream(inputStream), Closeable {

    private var currentSize: Long = 0

    private fun checkSize() {
        if (currentSize > maxSize) throw InputStreamSizeLimitReachedException(maxSize, currentSize)
    }

    override fun read(): Int {
        return super.read().also { if (it != -1) currentSize++; checkSize() }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        return super.read(b, off, len).also { if (it > 0) currentSize += it.toLong(); checkSize() }
    }

    override fun close() {
        super.close()
    }

}