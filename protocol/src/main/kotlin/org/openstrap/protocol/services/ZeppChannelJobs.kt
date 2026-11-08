package org.openstrap.protocol.services

import org.openstrap.protocol.ByteWriter
import org.openstrap.protocol.bytesOf
import org.openstrap.protocol.history.HistoryParsers
import org.openstrap.protocol.history.PaiRecord
import org.openstrap.protocol.history.ParseContext
import org.openstrap.protocol.history.ParseResult
import org.openstrap.protocol.u8

/** PaiModule 0x0022 (03 doc §3.18): Zepp 10.10.5 reads PAI here (`whx`) when the module's version is 5 or more. */
object PaiCodec {
    const val QUERY = 0x0C
    private const val REPLY = 0x0D
    private const val RECORD = 102
    private const val HEADER = 3
    private const val VERSION_AT = 2
    private const val FULL_RECORD_VERSION: Byte = 5

    val versionRequest: ByteArray = bytesOf(0x00)

    /** `01 xx version` (`hox.H`): the version is byte 2. */
    fun parseVersion(reply: ByteArray): Int? = if (reply.size > VERSION_AT && reply.u8(0) == 0x01) reply.u8(VERSION_AT) else null

    fun query(since: Long): ByteArray = ByteWriter().u8(QUERY).u32(since).toByteArray()

    fun isReply(reply: ByteArray) = reply.isNotEmpty() && reply.u8(0) == REPLY

    /**
     * `0D status count` then 102-byte records (`xqk` case 0), read whole whatever their version byte says. Any status
     * but 0 is an empty result, as Zepp's codec returns it (the strap answers `0D 01` when it has nothing new). Null
     * only when it isn't a reply.
     */
    fun parse(reply: ByteArray): List<PaiRecord>? = when {
        !isReply(reply) -> null
        reply.size < HEADER || reply.u8(1) != 0 -> emptyList()
        else -> (HEADER..reply.size - RECORD step RECORD).mapNotNull { at ->
            val record = reply.copyOfRange(at, at + RECORD)
            val version = record[0]
            record[0] = FULL_RECORD_VERSION
            val parsed = HistoryParsers.pai.parse(record, ParseContext(0)) as? ParseResult.Success
            parsed?.records?.singleOrNull()?.copy(version = version.toInt() and 0xFF)
        }
    }
}

/** Sleep module 0x0032: Zepp asks `01` once per strap and runs the sleep plan job (66) when the version is 2 or more. */
object SleepModuleCodec {
    val versionRequest: ByteArray = bytesOf(0x01)

    /** `02 version [bool] [string] [string]`. */
    fun parseVersion(reply: ByteArray): Int? = if (reply.size >= 2 && reply.u8(0) == 0x02) reply.u8(1) else null
}
