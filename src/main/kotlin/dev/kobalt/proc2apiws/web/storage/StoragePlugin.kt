/*
 * dev.kobalt.md2htmlws
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

import io.ktor.server.application.*
import io.ktor.server.application.hooks.*
import io.ktor.util.*
import kotlin.io.path.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.notExists
import kotlin.jvm.optionals.getOrNull

/** Plugin that provides an instance of storage repository. */
val StoragePlugin = createApplicationPlugin(
    name = StorageConfiguration.NAME,
    createConfiguration = ::StorageConfiguration
) {
    application.attributes.put(
        AttributeKey(StorageConfiguration.NAME),
        StorageRepository(
            command = pluginConfig.command!!,
        )
    )
}

