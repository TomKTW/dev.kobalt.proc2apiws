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

/** Type of the standard input that will be received. */
sealed class ConfigurationInputType {

    /** Standard input is not used. */
    data object NoValue : ConfigurationInputType()

    /** Standard input is based on string. */
    class StringValue(val input: String) : ConfigurationInputType()

    /** Standard input is based on bytes. */
    class ByteArrayValue(val input: ByteArray) : ConfigurationInputType()

}