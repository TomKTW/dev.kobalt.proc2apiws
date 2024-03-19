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

package dev.kobalt.md2htmlws.jvm.storage

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
import java.io.*
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.*
import kotlin.jvm.optionals.getOrNull

sealed class InputType {
    class NoValue() : InputType()
    class StringValue(val input: String) : InputType()
    class ByteArrayValue(val input: ByteArray) : InputType()
}

sealed class OutputType {
    data object StringValue : OutputType()
    data object ByteArrayValue : OutputType()
}

class StorageRepository(
    private val command: List<String>
) {

    /** Semaphore limiter to prevent a large amount of requests at once. */
    private val semaphore = Semaphore(5)

    /** Processes provided string data by sending it to external Java executable and writes it back to output stream. */
    private suspend fun convert(
        properties: Map<String, String>,
        inputType: InputType,
        outputType: OutputType,
        outputStream: OutputStream
    ) = withContext(Dispatchers.IO) {
        val javaPathString = ProcessHandle.current().info().command().getOrNull()!!

        val parameters = command.map { it.replace("\$currentJava\$", javaPathString) } + properties.flatMap {
            listOf(it.key, it.value)
        }
        val processBuilder = ProcessBuilder(parameters)
        val process = processBuilder.start()
        val stdout = when (outputType) {
            is OutputType.StringValue -> BufferedReader(InputStreamReader(process.inputStream))
            is OutputType.ByteArrayValue -> BufferedInputStream(process.inputStream)
        }
        val stderr = BufferedReader(InputStreamReader(process.errorStream))
        val stdin = BufferedWriter(OutputStreamWriter(process.outputStream))
        (inputType as? InputType.StringValue)?.let {

        }
        val stdinJob = when (inputType) {
            is InputType.ByteArrayValue -> null
            is InputType.NoValue -> null
            is InputType.StringValue -> launch(Dispatchers.IO) {
                stdin.write(inputType.input)
                stdin.flush()
                stdin.close()
            }
        }
        val stderrJob = launch(Dispatchers.IO) {
            println(stderr.readText())
        }
        val stdoutJob = async(Dispatchers.IO) {
            when (stdout) {
                is BufferedReader -> stdout.readText()
                is BufferedInputStream -> outputStream.write(stdout.readBytes())
                else -> throw Exception()
            }
        }
        stdinJob?.join()
        stderrJob.join()
        stdoutJob.await()
        process.waitFor(5, TimeUnit.SECONDS)
        if (process.exitValue() == 0) outputStream else throw Exception()
    }

    suspend fun submit(
        properties: Map<String, String>,
        inputType: InputType,
        outputType: OutputType,
        outputStream: OutputStream
    ): OutputStream {
        return withContext(Dispatchers.IO) {
            semaphore.withPermit {
                withTimeout(5000) {
                    convert(properties, inputType, outputType, outputStream)
                }
            }
        }
    }

    private fun getInputStreamFromResources(path: String): InputStream? =
        this::class.java.classLoader.getResourceAsStream(path)

    private fun getBytesFromResources(path: String): ByteArray? =
        getInputStreamFromResources(path)?.use { it.readBytes() }

    private fun getTextFromResources(path: String): String? =
        getBytesFromResources(path)?.decodeToString()

    fun getMessagePageContent(): String {
        return getTextFromResources("message.html")!!
    }

}