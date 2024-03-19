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

import kotlinx.serialization.json.*

/** Represents an entity containing HTTP server configuration.*/
data class StorageConfigEntity(
    /** Port where server would be running on. */
    val port: Int,
    /** Host where server would be running on. */
    val host: String,

    val pathRestrict: String,
    /** Server name of the server. */
    val command: List<String>,
    /** Title of the website. */
    val parameters: List<Parameter>,

    val outputType: String,
    val outputFilename: String,
    val outputContentType: String,
    val serverName: String,
    val serverLocation: String
) {

    data class Parameter(
        val form: String,
        val process: String?,
        val valuePrefix: String?,
        val valueSuffix: String?,
        val verifyPath: Boolean,
        val optional: Boolean,
        val stdin: Boolean,
        val outputOverride: List<OutputOverride>
    ) {

        data class OutputOverride(
            val value: String,
            val outputFilename: String
        )

    }

    /** Returns string containing a template of NGINX configuration that can be used. */
    fun toNginxConfig() = """
    |server {
    |        listen 80;
    |        listen 443 ssl;
    |        server_name ${serverName};
    |        location $serverLocation {
    |                proxy_pass http://localhost:${port}/;
    |                proxy_set_header Host ${'$'}http_host;
    |                proxy_set_header X-Real-IP ${'$'}remote_addr;
    |                proxy_set_header X-Forwarded-For ${'$'}proxy_add_x_forwarded_for;
    |                server_tokens off;
    |        }
    |        ssl_certificate /etc/letsencrypt/live/${serverName}/fullchain.pem;
    |        ssl_certificate_key /etc/letsencrypt/live/${serverName}/privkey.pem;
    |        ssl_dhparam /etc/letsencrypt/ssl-dhparams.pem;
    |        include /etc/letsencrypt/options-ssl-nginx.conf;
    |}
    """.trimMargin()

}

/** Returns storage configuration entity object from JSON object. Variables in JSON are replaced with their values. */
fun JsonObject.toStorageConfigEntity() = runCatching {
    StorageConfigEntity(
        port = this["port"]?.jsonPrimitive?.intOrNull!!,
        host = this["host"]?.jsonPrimitive?.contentOrNull!!,
        pathRestrict = this["pathRestrict"]?.jsonPrimitive?.contentOrNull!!,
        command = this["command"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull },
        parameters = this["parameters"]?.jsonArray.orEmpty()
            .mapNotNull { it.jsonObject.toStorageConfigEntityParameters() },
        outputType = this["outputType"]?.jsonPrimitive?.contentOrNull!!,
        outputFilename = this["outputFilename"]?.jsonPrimitive?.contentOrNull!!,
        outputContentType = this["outputContentType"]?.jsonPrimitive?.contentOrNull!!,
        serverName = this["serverName"]?.jsonPrimitive?.contentOrNull!!,
        serverLocation = this["serverLocation"]?.jsonPrimitive?.contentOrNull!!,
    )
}.getOrNull()

fun JsonObject.toStorageConfigEntityParameters(): StorageConfigEntity.Parameter? = runCatching {
    StorageConfigEntity.Parameter(
        form = (this["form"] as? JsonPrimitive)?.contentOrNull!!,
        process = (this["process"] as? JsonPrimitive)?.contentOrNull,
        valuePrefix = (this["valuePrefix"] as? JsonPrimitive)?.contentOrNull,
        valueSuffix = (this["valueSuffix"] as? JsonPrimitive)?.contentOrNull,
        verifyPath = (this["verifyPath"] as? JsonPrimitive)?.booleanOrNull ?: false,
        optional = (this["optional"] as? JsonPrimitive)?.booleanOrNull ?: false,
        stdin = (this["stdin"] as? JsonPrimitive)?.booleanOrNull ?: false,
        outputOverride = this["outputOverride"]?.jsonArray.orEmpty()
            .mapNotNull { it.jsonObject.toStorageConfigEntityParametersOutputOverride() },
    )
}.getOrNull()

fun JsonObject.toStorageConfigEntityParametersOutputOverride(): StorageConfigEntity.Parameter.OutputOverride? =
    runCatching {
        StorageConfigEntity.Parameter.OutputOverride(
            value = (this["value"] as? JsonPrimitive)?.contentOrNull!!,
            outputFilename = (this["outputFilename"] as? JsonPrimitive)?.contentOrNull!!,
        )
    }.getOrNull()
