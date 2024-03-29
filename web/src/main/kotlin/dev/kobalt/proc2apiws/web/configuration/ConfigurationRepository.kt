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

package dev.kobalt.proc2apiws.web.configuration

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.*
import java.util.concurrent.TimeUnit
import kotlin.jvm.optionals.getOrNull

/** Repository of configuration that provides conversion process from received data as parameters into output stream as response. */
class ConfigurationRepository(
    private val command: List<String>
) {

    /** Semaphore limiter to prevent a large amount of requests at once. */
    private val semaphore = Semaphore(5)

    /** Processes provided string data by sending it to external Java executable and writes it back to output stream. */
    private suspend fun convert(
        properties: Map<String, String>,
        inputType: ConfigurationInputType,
        outputType: ConfigurationOutputType,
        outputStream: OutputStream
    ) = withContext(Dispatchers.IO) {
        // Working path of Java process that runs on this server.
        val javaPathString = ProcessHandle.current().info().command().getOrNull()!!
        // Append command as first values and then append remaining properties.
        val parameters = command.map {
            // Change 'currentJava' variable to Java process working path.
            it.replace("\$currentJava\$", javaPathString)
        } + properties.flatMap {
            listOf(it.key, it.value)
        }
        // Build and start process from given parameters.
        val process = ProcessBuilder(parameters).start()
        // Prepare standard output stream based on given type as string reader or raw binary stream.
        val stdout = when (outputType) {
            is ConfigurationOutputType.StringValue -> BufferedReader(InputStreamReader(process.inputStream))
            is ConfigurationOutputType.ByteArrayValue -> BufferedInputStream(process.inputStream)
        }
        // Prepare standard error output stream in case of failure.
        val stderr = BufferedReader(InputStreamReader(process.errorStream))
        // Prepare standard input stream as raw binary stream. Note: There is no implementation of string reader here.
        val stdin = BufferedWriter(OutputStreamWriter(process.outputStream))
        // Launch coroutine to send standard input to process.
        val stdinJob = when (inputType) {
            is ConfigurationInputType.ByteArrayValue -> null
            is ConfigurationInputType.NoValue -> null
            is ConfigurationInputType.StringValue -> launch(Dispatchers.IO) {
                stdin.write(inputType.input)
                stdin.flush()
                stdin.close()
            }
        }
        // Launch coroutine to print standard error output.
        val stderrJob = launch(Dispatchers.IO) {
            println(stderr.readText())
        }
        // Launch coroutine to read text or bytes from output stream.
        val stdoutJob = async(Dispatchers.IO) {
            when (stdout) {
                is BufferedReader -> outputStream.write(stdout.readText().toByteArray())
                is BufferedInputStream -> outputStream.write(stdout.readBytes())
                else -> throw Exception()
            }
        }
        // Combine and wait each job until it's done.
        stdinJob?.join()
        stderrJob.join()
        stdoutJob.await()
        // Wait for at least 5 seconds after coroutines are done to receive exit value.
        process.waitFor(5, TimeUnit.SECONDS)
        // Provide output stream if process has successfully exited.
        if (process.exitValue() == 0) outputStream else throw Exception()
    }

    /** Submits given properties, input and output type, and output stream to be processed. */
    suspend fun submit(
        properties: Map<String, String>,
        inputType: ConfigurationInputType,
        outputType: ConfigurationOutputType,
        outputStream: OutputStream
    ): OutputStream {
        return withContext(Dispatchers.IO) {
            // Keep submission requests under limit to avoid overload.
            semaphore.withPermit {
                withTimeout(5000) {
                    convert(properties, inputType, outputType, outputStream)
                }
            }
        }
    }

    /** Returns input stream of resource from given path. */
    private fun getInputStreamFromResources(path: String): InputStream? =
        this::class.java.classLoader.getResourceAsStream(path)

    /** Returns contents of resource in bytes from given path. */
    private fun getBytesFromResources(path: String): ByteArray? =
        getInputStreamFromResources(path)?.use { it.readBytes() }

    /** Returns contents of resource in string from given path. */
    private fun getTextFromResources(path: String): String? =
        getBytesFromResources(path)?.decodeToString()

    /** Returns HTML content of message page in string. */
    fun getMessagePageContent(): String {
        return getTextFromResources("message.html")!!
    }

}