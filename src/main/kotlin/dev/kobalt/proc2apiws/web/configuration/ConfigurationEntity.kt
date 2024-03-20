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

import dev.kobalt.proc2apiws.web.extension.*
import kotlinx.serialization.json.JsonObject

/** Represents an entity containing HTTP server configuration.*/
data class ConfigurationEntity(
    /** Port where server would be running on. */
    val port: Int,
    /** Host where server would be running on. */
    val host: String,
    /** Root path used to restrict access to paths that belong to this one as parent.*/
    val pathRestrict: String,
    /** Primary command to be executed, separated into queries. */
    val command: List<String>,
    /** List of parameters that will be included in command as suffix. */
    val parameters: List<Parameter>,
    /** Type of output ("binary" or "string" values) that will be sent back as response. */
    val outputType: String,
    /** Filename of output response. */
    val outputFilename: String,
    /** Content type of output that is used to provide file type on response. */
    val outputContentType: String,
    /** Domain name of the server. Used for generating nginx configuration. */
    val serverName: String,
    /** Location of the server as path. Used for generating nginx configuration. */
    val serverLocation: String
) {

    /** Represents a parameter entry that will be used in command execution. */
    data class Parameter(
        /** Identifier key in HTML form under property 'name'. */
        val form: String,
        /** Identifier key in process parameter without value. */
        val process: String?,
        /** Prefix that will be included in value of this parameter.*/
        val valuePrefix: String?,
        /** Suffix that will be included in value of this parameter.*/
        val valueSuffix: String?,
        /** If true, treat value as path and check if it's within [ConfigurationEntity.pathRestrict] path. */
        val verifyPath: Boolean,
        /** If true, this parameter won't be checked if it's blank. Prevents [Parameter.verifyPath] usage on blank value. */
        val optional: Boolean,
        /** If true, this parameter is treated as standard input stream instead of parameter. */
        val stdin: Boolean,
        /** List of overrides that will be used to replace specific properties. */
        val override: List<Override>
    ) {

        /** Represents a .*/
        data class Override(
            /** Value from parameter that needs to match received value to apply overrides.*/
            val value: String,
            /** Overridden filename of output. */
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

/** Returns configuration entity from JSON object. */
fun JsonObject.toConfigurationEntity() = runCatching {
    ConfigurationEntity(
        port = this["port"]?.toIntOrNull()!!,
        host = this["host"]?.toStringOrNull()!!,
        pathRestrict = this["pathRestrict"]?.toStringOrNull()!!,
        command = this["command"]?.toJsonArrayOrNull().orEmpty().mapNotNull { it.toStringOrNull() },
        parameters = this["parameters"]?.toJsonArrayOrNull().orEmpty().mapNotNull {
            it.toJsonObjectOrNull()?.toConfigurationEntityParameter()
        },
        outputType = this["outputType"]?.toStringOrNull()!!,
        outputFilename = this["outputFilename"]?.toStringOrNull()!!,
        outputContentType = this["outputContentType"]?.toStringOrNull()!!,
        serverName = this["serverName"]?.toStringOrNull()!!,
        serverLocation = this["serverLocation"]?.toStringOrNull()!!,
    )
}.getOrNull()

/** Returns configuration entity parameter from JSON object. */
fun JsonObject.toConfigurationEntityParameter(): ConfigurationEntity.Parameter? = runCatching {
    ConfigurationEntity.Parameter(
        form = this["form"]?.toStringOrNull()!!,
        process = this["process"]?.toStringOrNull(),
        valuePrefix = this["valuePrefix"]?.toStringOrNull(),
        valueSuffix = this["valueSuffix"]?.toStringOrNull(),
        verifyPath = this["verifyPath"]?.toBooleanOrNull() ?: false,
        optional = this["optional"]?.toBooleanOrNull() ?: false,
        stdin = this["stdin"]?.toBooleanOrNull() ?: false,
        override = this["outputOverride"]?.toJsonArrayOrNull().orEmpty().mapNotNull {
            it.toJsonObjectOrNull()?.toConfigurationEntityParameterOverride()
        },
    )
}.getOrNull()

/** Returns configuration entity parameter override from JSON object. */
fun JsonObject.toConfigurationEntityParameterOverride(): ConfigurationEntity.Parameter.Override? = runCatching {
    ConfigurationEntity.Parameter.Override(
        value = this["value"]?.toStringOrNull()!!,
        outputFilename = this["outputFilename"]?.toStringOrNull()!!,
    )
}.getOrNull()
