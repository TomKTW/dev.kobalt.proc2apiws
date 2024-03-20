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

package dev.kobalt.proc2apiws.web.extension

import kotlinx.serialization.json.*

/** Returns JSON element as integer, or otherwise as null if it doesn't match the type. */
fun JsonElement.toIntOrNull(): Int? {
    return (this as? JsonPrimitive)?.intOrNull
}

/** Returns JSON element as string, or otherwise as null if it doesn't match the type. */
fun JsonElement.toStringOrNull(): String? {
    return (this as? JsonPrimitive)?.contentOrNull
}

/** Returns JSON element as boolean, or otherwise as null if it doesn't match the type. */
fun JsonElement.toBooleanOrNull(): Boolean? {
    return (this as? JsonPrimitive)?.booleanOrNull
}

/** Returns JSON element as JSON object, or otherwise as null if it doesn't match the type. */
fun JsonElement.toJsonObjectOrNull(): JsonObject? {
    return (this as? JsonObject)
}

/** Returns JSON element as JSON array, or otherwise as null if it doesn't match the type. */
fun JsonElement.toJsonArrayOrNull(): JsonArray? {
    return (this as? JsonArray)
}