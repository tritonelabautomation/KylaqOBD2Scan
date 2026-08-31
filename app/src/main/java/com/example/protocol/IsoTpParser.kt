package com.example.protocol

/**
 * Reconstructed message from ISO-TP CAN frames
 */
data class IsoTpMessage(
    val canId: String?,
    val individualFrames: List<RawCanFrame>,
    val reconstructedPayloadHex: String,
    val reconstructedBytes: List<Int>,
    val isComplete: Boolean,
    val totalExpectedLength: Int
)

/**
 * Reassembles ISO-TP multi-frame CAN responses into coherent OBD payloads
 */
object IsoTpParser {

    /**
     * Reassembles a list of raw response lines from ELM327 into parsed ISO-TP messages.
     * Can handle single or multiple lines from one or more ECUs (e.g. 7E8).
     */
    fun reassembleLines(lines: List<String>): List<IsoTpMessage> {
        val frames = lines.map { CanFrameParser.parseFrame(it) }
            .filter { it.dataBytes.isNotEmpty() }

        if (frames.isEmpty()) {
            return emptyList()
        }

        // Group frames by CAN ID (or "UNKNOWN" if no header)
        val groupedByCanId = frames.groupBy { it.canId ?: "NO_HEADER" }
        val results = mutableListOf<IsoTpMessage>()

        for ((canId, canFrames) in groupedByCanId) {
            val resolvedCanId = if (canId == "NO_HEADER") null else canId

            var currentFrames = mutableListOf<RawCanFrame>()
            var expectedTotalLength = 0
            var payloadAccumulator = mutableListOf<Int>()
            var isMultiFrame = false

            for (frame in canFrames) {
                when (frame.pciType) {
                    IsoTpPciType.SINGLE_FRAME -> {
                        // Flush any pending multi-frame
                        if (currentFrames.isNotEmpty()) {
                            results.add(
                                createIsoTpMessage(
                                    resolvedCanId,
                                    currentFrames,
                                    payloadAccumulator,
                                    expectedTotalLength
                                )
                            )
                            currentFrames = mutableListOf()
                            payloadAccumulator = mutableListOf()
                        }
                        // Single frame is complete immediately
                        val sfBytes = frame.payloadBytes
                        results.add(
                            IsoTpMessage(
                                canId = resolvedCanId,
                                individualFrames = listOf(frame),
                                reconstructedPayloadHex = sfBytes.joinToString("") { "%02X".format(it) },
                                reconstructedBytes = sfBytes,
                                isComplete = true,
                                totalExpectedLength = sfBytes.size
                            )
                        )
                    }

                    IsoTpPciType.FIRST_FRAME -> {
                        if (currentFrames.isNotEmpty()) {
                            results.add(
                                createIsoTpMessage(
                                    resolvedCanId,
                                    currentFrames,
                                    payloadAccumulator,
                                    expectedTotalLength
                                )
                            )
                            currentFrames = mutableListOf()
                            payloadAccumulator = mutableListOf()
                        }
                        isMultiFrame = true
                        currentFrames.add(frame)
                        // 12-bit length: (dataBytes[0] & 0x0F) << 8 | dataBytes[1]
                        expectedTotalLength = if (frame.dataBytes.size >= 2) {
                            ((frame.dataBytes[0] and 0x0F) shl 8) or (frame.dataBytes[1] and 0xFF)
                        } else {
                            0
                        }
                        payloadAccumulator.addAll(frame.payloadBytes)
                    }

                    IsoTpPciType.CONSECUTIVE_FRAME -> {
                        if (isMultiFrame) {
                            currentFrames.add(frame)
                            payloadAccumulator.addAll(frame.payloadBytes)
                            if (payloadAccumulator.size >= expectedTotalLength && expectedTotalLength > 0) {
                                val trimmedPayload = payloadAccumulator.take(expectedTotalLength)
                                results.add(
                                    IsoTpMessage(
                                        canId = resolvedCanId,
                                        individualFrames = currentFrames.toList(),
                                        reconstructedPayloadHex = trimmedPayload.joinToString("") { "%02X".format(it) },
                                        reconstructedBytes = trimmedPayload,
                                        isComplete = true,
                                        totalExpectedLength = expectedTotalLength
                                    )
                                )
                                currentFrames = mutableListOf()
                                payloadAccumulator = mutableListOf()
                                isMultiFrame = false
                            }
                        } else {
                            // Stray consecutive frame
                            currentFrames.add(frame)
                        }
                    }

                    IsoTpPciType.FLOW_CONTROL -> {
                        // Flow control acknowledgment frame
                        currentFrames.add(frame)
                    }

                    IsoTpPciType.NON_ISO_TP -> {
                        // Regular unformatted OBD bytes
                        val bytes = frame.payloadBytes
                        results.add(
                            IsoTpMessage(
                                canId = resolvedCanId,
                                individualFrames = listOf(frame),
                                reconstructedPayloadHex = bytes.joinToString("") { "%02X".format(it) },
                                reconstructedBytes = bytes,
                                isComplete = true,
                                totalExpectedLength = bytes.size
                            )
                        )
                    }
                }
            }

            // Flush any remaining unfinalized frames
            if (currentFrames.isNotEmpty()) {
                val trimmed = if (expectedTotalLength in 1..payloadAccumulator.size) {
                    payloadAccumulator.take(expectedTotalLength)
                } else {
                    payloadAccumulator
                }
                results.add(
                    IsoTpMessage(
                        canId = resolvedCanId,
                        individualFrames = currentFrames,
                        reconstructedPayloadHex = trimmed.joinToString("") { "%02X".format(it) },
                        reconstructedBytes = trimmed,
                        isComplete = (trimmed.size == expectedTotalLength && expectedTotalLength > 0),
                        totalExpectedLength = expectedTotalLength
                    )
                )
            }
        }

        return results
    }

    private fun createIsoTpMessage(
        canId: String?,
        frames: List<RawCanFrame>,
        payload: List<Int>,
        expectedLength: Int
    ): IsoTpMessage {
        val trimmed = if (expectedLength in 1..payload.size) payload.take(expectedLength) else payload
        return IsoTpMessage(
            canId = canId,
            individualFrames = frames,
            reconstructedPayloadHex = trimmed.joinToString("") { "%02X".format(it) },
            reconstructedBytes = trimmed,
            isComplete = (trimmed.size == expectedLength && expectedLength > 0),
            totalExpectedLength = expectedLength
        )
    }
}
