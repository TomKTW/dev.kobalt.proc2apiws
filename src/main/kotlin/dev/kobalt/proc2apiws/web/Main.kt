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

package dev.kobalt.proc2apiws.web

import dev.kobalt.proc2apiws.web.configuration.*
import dev.kobalt.proc2apiws.web.extension.isLocatedIn
import dev.kobalt.proc2apiws.web.inputstream.InputStreamSizeLimitReachedException
import dev.kobalt.proc2apiws.web.inputstream.LimitedSizeInputStream
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.cachingheaders.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.plugins.forwardedheaders.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.*

/** Main method. */
suspend fun main(args: Array<String>) {
    // Parse given arguments.
    val parser = ArgParser(
        programName = "server"
    )
    val configPath by parser.option(
        type = ArgType.String,
        fullName = "configPath",
        shortName = "conf",
        description = "Path of configuration JSON file"
    )
    val nginxConfigPath by parser.option(
        type = ArgType.String,
        fullName = "nginxConfigPath",
        shortName = "nxcf",
        description = "Path of generated nginx configuration file"
    )
    parser.parse(args)
    //  Configure and start servers from configuration file.
    val mainScope = CoroutineScope(Dispatchers.Main)
    val configList = configPath?.let {
        Json.parseToJsonElement(Path(it).readText()).jsonArray
    }?.mapNotNull {
        it.jsonObject.toConfigurationEntity()
    }.orEmpty()
    // Generate nginx configuration if path was set for it.
    nginxConfigPath?.let { path ->
        Path(path).writeText(configList.joinToString("\n\n") { it.toNginxConfig() })
    }
    // Prepare and start servers.
    configList.map { config ->
        // Every server will launch separately and placed to wait until shutdown is requested.
        mainScope.async(
            context = Dispatchers.IO + NonCancellable,
            start = CoroutineStart.LAZY
        ) {
            setupServer(config).also {
                // Add shutdown hook to stop the server gracefully.
                Runtime.getRuntime().addShutdownHook(thread(start = false) {
                    it.stop(0, 10, TimeUnit.SECONDS)
                })
            }.also {
                it.start(true)
            }
        }
    }.awaitAll()
}

/** Returns an instance of server with configuration from given entity .*/
fun setupServer(config: ConfigurationEntity) = embeddedServer(CIO, config.port, config.host) {
    install(ForwardedHeaders)
    install(DefaultHeaders)
    install(CallLogging)
    install(Compression)
    install(ConfigurationPlugin) { command = config.command }
    install(IgnoreTrailingSlash)
    install(CachingHeaders) { options { _, _ -> CachingOptions(CacheControl.MaxAge(maxAgeSeconds = 0)) } }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            cause.printStackTrace()
            call.respondText(
                text = call.application.configuration.getMessagePageContent().replace("\$title\$", "Failure").replace(
                    "\$description\$", when (cause) {
                        is InputStreamSizeLimitReachedException -> "Submitted content is bigger than size limit (${cause.maxSize / 1024}kB maximum, ${cause.currentSize / 1024}kB received)."
                        else -> "Processing request was not successful."
                    }
                ),
                contentType = ContentType.Text.Html,
                status = HttpStatusCode.InternalServerError
            )
        }
    }
    install(Routing) {
        post {
            runCatching {
                // TODO: Add property for selecting part that will be used for original filename.
                // Original filename of first submitted file part.
                var originalFilename: String? = null
                // Override value for changing output filename.
                var outputOverride: ConfigurationEntity.Parameter.Override? = null
                // Convert all parts to property keymap.
                val properties = call.receiveMultipart().readAllParts().mapNotNull { part ->
                    // Skip parts that are not in configuration.
                    config.parameters.find { it.form == part.name }?.let { parameter ->
                        // TODO: Add size limit to configuration.
                        // Read value from part.
                        val value = when (part) {
                            is PartData.FileItem -> LimitedSizeInputStream(
                                part.streamProvider(),
                                500 * 1024
                            ).readBytes()
                                .decodeToString()

                            is PartData.FormItem -> part.value.takeIf { it.length < 500 * 1024 } ?: throw Exception()
                            else -> throw Exception()
                        }
                        // Get the first original filename from file part and store it for processing without extension.
                        if (originalFilename == null) {
                            originalFilename = when (part) {
                                is PartData.FileItem -> part.originalFileName
                                else -> null
                            }?.let { Path(it) }?.nameWithoutExtension
                        }
                        // Skip part if the value is empty and treated as optional.
                        if (parameter.optional && value.isEmpty()) return@let null
                        // Append prefix and suffix to value.
                        val updatedValue = parameter.valuePrefix.orEmpty() + value + parameter.valueSuffix.orEmpty()
                        // If parameter value is treated as path, check if it's within legitimate parent range.
                        if (parameter.verifyPath) {
                            val path = Path(updatedValue)
                            val verifyPath = Path(config.pathRestrict)
                            if (!path.exists() || !verifyPath.exists() || !path.isLocatedIn(verifyPath)) {
                                throw Exception()
                            }
                        }
                        // If override option exists and matches given value, apply override value.
                        if (parameter.override.isNotEmpty()) {
                            outputOverride = parameter.override.find { it.value == value }?.let {
                                it.copy(outputFilename = it.outputFilename)
                            }
                        }
                        // If parameter is treated as standard input stream for processing, set key as null.
                        parameter.process.takeIf { !parameter.stdin } to updatedValue
                    }
                }
                // Get first property with key as null as that will be used for standard input stream.
                val input = properties.find { (name, _) -> name == null }?.second.let {
                    when {
                        it == null -> ConfigurationInputType.NoValue
                        else -> ConfigurationInputType.StringValue(it)
                    }
                }
                // Get remaining properties to map.
                val parameters = properties.mapNotNull { (name, value) -> name?.let { it to value } }.toMap()
                // Get the value of output type that will be used for processed response.
                val outputType = when (config.outputType) {
                    "string" -> ConfigurationOutputType.StringValue
                    "binary" -> ConfigurationOutputType.ByteArrayValue
                    else -> throw Exception()
                }
                // Convert the data through output stream.
                val bytes = ByteArrayOutputStream().use {
                    application.configuration.submit(parameters, input, outputType, it)
                    it.toByteArray()
                }
                // Parse the content type for response.
                val contentType = ContentType.parse(config.outputContentType)
                // Get output filename from override if defined. It will be replaced if '$outputFilename$' is defined in 'outputFilename'.
                val outputOverrideFilename = outputOverride?.outputFilename.orEmpty()
                    .replace("\$originalFilename\$", originalFilename ?: "file")
                // Apply header after conversion to prevent downloading failed page.
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(
                        ContentDisposition.Parameters.FileName,
                        config.outputFilename.replace("\$outputFilename\$", outputOverrideFilename)
                    ).toString()
                )
                // Respond with zipped output stream file.
                call.respondBytes(
                    contentType = contentType,
                    status = HttpStatusCode.OK,
                    bytes = bytes
                )
            }.getOrElse { cause ->
                // TODO: Check if this is redundant. As ironic it might look like, status pages might break inconveniently.
                cause.printStackTrace()
                call.respondText(
                    text = call.application.configuration.getMessagePageContent().replace("\$title\$", "Failure")
                        .replace(
                            "\$description\$", when (cause) {
                                is InputStreamSizeLimitReachedException -> "Submitted content is bigger than size limit (${cause.maxSize / 1024}kB maximum, ${cause.currentSize / 1024}kB received)."
                                else -> "Processing request was not successful."
                        }
                    ),
                    contentType = ContentType.Text.Html,
                    status = HttpStatusCode.InternalServerError
                )
            }
        }
    }
}