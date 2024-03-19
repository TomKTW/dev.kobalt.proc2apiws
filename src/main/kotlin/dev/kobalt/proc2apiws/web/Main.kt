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

import dev.kobalt.proc2apiws.web.inputstream.InputStreamSizeLimitReachedException
import dev.kobalt.proc2apiws.web.inputstream.LimitedSizeInputStream
import dev.kobalt.md2htmlws.jvm.extension.isLocatedIn
import dev.kobalt.md2htmlws.jvm.storage.*
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
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.*

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
        it.jsonObject.toStorageConfigEntity()
    }.orEmpty()
    // Generate nginx configuration if path was set for it.
    nginxConfigPath?.let { path ->
        Path(path).writeText(configList.joinToString("\n\n") { it.toNginxConfig() })
    }
    // Prepare and start servers.
    configList.map { config ->
        mainScope.async(
            context = Dispatchers.IO + NonCancellable,
            start = CoroutineStart.LAZY
        ) {
            setupServer(config).also {
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
fun setupServer(config: StorageConfigEntity) = embeddedServer(CIO, config.port, config.host) {
    install(ForwardedHeaders)
    install(DefaultHeaders)
    install(CallLogging)
    install(Compression)
    install(StoragePlugin) {
        command = config.command
    }
    install(IgnoreTrailingSlash)
    install(CachingHeaders) { options { _, _ -> CachingOptions(CacheControl.MaxAge(maxAgeSeconds = 0)) } }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            cause.printStackTrace()
            call.respondText(
                text = call.application.storage.getMessagePageContent().replace("\$title\$", "Failure").replace(
                    "\$description\$", when (cause) {
                        is InputStreamSizeLimitReachedException -> "Submitted content is bigger than size limit (500 kB)."
                        else -> "Conversion was not successful."
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
                // TODO: CLEANUP!


                var originalFilename: String? = null
                var outputOverride: StorageConfigEntity.Parameter.OutputOverride? = null

                val properties = call.receiveMultipart().readAllParts().mapNotNull { part ->
                    config.parameters.find { it.form == part.name }?.let { parameter ->
                        val value = when (part) {
                            is PartData.FileItem -> LimitedSizeInputStream(
                                part.streamProvider(),
                                500 * 1024
                            ).readBytes()
                                .decodeToString()

                            is PartData.FormItem -> part.value.takeIf { it.length < 500 * 1024 } ?: throw Exception()
                            else -> throw Exception()
                        }

                        if (originalFilename == null) {
                            originalFilename = when (part) {
                                is PartData.FileItem -> part.originalFileName
                                else -> null
                            }?.let { Path(it) }?.nameWithoutExtension
                        }

                        if (parameter.optional && value.isEmpty()) return@let null
                        val updatedValue = parameter.valuePrefix.orEmpty() + value + parameter.valueSuffix.orEmpty()
                        if (parameter.verifyPath) {
                            val path = Path(updatedValue)
                            val verifyPath = Path(config.pathRestrict)
                            if (!path.exists() || !verifyPath.exists() || !path.isLocatedIn(verifyPath)) {
                                throw Exception()
                            }
                        }
                        if (parameter.outputOverride.isNotEmpty()) {
                            outputOverride = parameter.outputOverride.find { it.value == value }?.let {
                                it.copy(outputFilename = it.outputFilename)
                            }
                        }
                        parameter.process.takeIf { !parameter.stdin } to updatedValue
                    }
                }
                val input = properties.find { (name, _) -> name == null }?.second.let {
                    when {
                        it == null -> InputType.NoValue()
                        else -> InputType.StringValue(it)
                    }
                }
                val parameters = properties.mapNotNull { (name, value) -> name?.let { it to value } }.toMap()

                val outputType = when (config.outputType) {
                    "string" -> OutputType.StringValue
                    "binary" -> OutputType.ByteArrayValue
                    else -> throw Exception()
                }

                // Convert the data.
                val bytes = ByteArrayOutputStream().use {
                    application.storage.submit(parameters, input, outputType, it)
                    it.toByteArray()
                }

                val contentType = ContentType.parse(config.outputContentType)

                val outputOverrideFilename =
                    outputOverride?.outputFilename.orEmpty().replace("\$originalFilename\$", originalFilename ?: "file")

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
            }.getOrElse {
                it.printStackTrace()
                call.respondText(
                    text = application.storage.getMessagePageContent().replace("\$title\$", "Failure").replace(
                        "\$description\$", when (it) {
                            is InputStreamSizeLimitReachedException -> "Submitted content is bigger than size limit (500 kB)."
                            else -> "Conversion was not successful."
                        }
                    ),
                    contentType = ContentType.Text.Html,
                    status = HttpStatusCode.InternalServerError
                )
            }
        }
    }
}.also {
    Runtime.getRuntime().addShutdownHook(thread(start = false) {
        it.stop(0, 10, TimeUnit.SECONDS)
    })
}.also {
    it.start(true)
}

/*
suspend fun main(args: Array<String>) {
    // Parse given arguments.
    val parser = ArgParser(
        programName = "proc2apiws"
    )
    val jarPath by parser.option(
        type = ArgType.String,
        fullName = "jarPath",
        shortName = "jar",
        description = "Path to converter JAR file"
    )
    val httpServerPort by parser.option(
        type = ArgType.Int,
        fullName = "httpServerPort",
        shortName = "hsp",
        description = "Port to host the server at"
    )
    val httpServerHost by parser.option(
        type = ArgType.String,
        fullName = "httpServerHost",
        shortName = "hsh",
        description = "Host value (127.0.0.1 for private, 0.0.0.0 for public access)"
    )
    parser.parse(args)

    val test = String::class

    test.isInstance("abc")

    val formProperties = listOf(
        "title",
        "message",
        "buttons",
        "icon",
        "font",
        "width",
        "titleBarStartColor",
        "titleBarEndColor",
        "titleBarTextColor",
        "windowBackgroundColor",
        "messageTextColor",
        "buttonTextColor",
        "buttonBackgroundColor",
    )




    ifLet(jarPath, httpServerPort, httpServerHost) { path, port, host ->
        CoroutineScope(Dispatchers.Main).async(
            context = Dispatchers.IO + NonCancellable,
            start = CoroutineStart.LAZY
        ) {
            setupServer(path, port, host, formProperties).also {
                Runtime.getRuntime().addShutdownHook(thread(start = false) {
                    it.stop(0, 10, TimeUnit.SECONDS)
                })
            }.also {
                it.start(true)
            }
        }.await()
    }
}

/** Returns an instance of server with configuration from given entity .*/
fun setupServer(jarPath: String, port: Int, host: String, formProperties: List<String>) = embeddedServer(CIO, port, host) {
    install(ForwardedHeaders)
    install(DefaultHeaders)
    install(CallLogging)
    install(Compression)
    install(ConverterPlugin) {
        this.jarPath = jarPath
    }
    install(IgnoreTrailingSlash)
    install(CachingHeaders) {
        options { _, _ -> CachingOptions(CacheControl.MaxAge(maxAgeSeconds = 0)) }
    }
    install(StatusPages)
    install(Routing) {
        post {
            runCatching {
                val parts = call.receiveMultipart().readAllParts().filter {
                    formProperties.contains(it.name)
                }
                val partData = parts.map {
                    when (it) {
                        is PartData.FileItem -> LimitedSizeInputStream(it.streamProvider(), 500 * 1024).readBytes()
                            .decodeToString()

                        is PartData.FormItem -> it.value.takeIf { it.length < 500 * 1024 } ?: throw Exception()
                        else -> throw Exception()
                    }
                }

                // Convert the data.
                val bytes = ByteArrayOutputStream().use {
                    application.converter.submit(data, it)
                    it.toByteArray()
                }
                // Apply header after conversion to prevent downloading failed page.
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(
                        ContentDisposition.Parameters.FileName, "output.zip"
                    ).toString()
                )
                // Respond with zipped output stream file.
                call.respondBytes(
                    contentType = ContentType.Application.Zip,
                    status = HttpStatusCode.OK,
                    bytes = bytes
                )
            }.getOrElse {
                call.respondText(
                    text = application.converter.getMessagePageContent().replace("\$title\$", "Failure").replace(
                        "\$description\$", when (it) {
                            is InputStreamSizeLimitReachedException -> "Submitted content is bigger than size limit (500 kB)."
                            else -> "Conversion was not successful."
                        }
                    ),
                    contentType = ContentType.Text.Html,
                    status = HttpStatusCode.InternalServerError
                )
            }
        }
    }
}.also {
    Runtime.getRuntime().addShutdownHook(thread(start = false) {
        it.stop(0, 10, TimeUnit.SECONDS)
    })
}.also {
    it.start(true)
}*/