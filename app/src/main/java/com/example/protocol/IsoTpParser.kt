package com.example.protocol

/**
 * Reconstructed message from ISO-TP CAN frames (ISO 15765-2).
 */
data class IsoTpMessage(
    val canId: String?,
    val individualFrames: List<RawCanFrame>,
    val reconstructedPayloadHex: String,
    val reconstructedBytes: List<Int>,
    val isComplete: Boolean,
    val totalExpectedLength: Int,
    val isMalformed: Boolean = false,
    val malformedReason: String? = null
)

/**
 * Reassembles ISO-TP multi-frame CAN responses into coherent OBD payloads.
 *
 * Implements ISO 15765-2:
 * - Single Frame (SF, 0x0_)
 * - First Frame (FF, 0x1_)
 * - Consecutive Frame (CF, 0x2_) with sequence number verification
 * - Flow Control (FC, 0x3_)
 * - Handles CAN ID grouping, padding bytes stripping, and malformed state reporting.
 */
object IsoTpParser {

    /**
     * Reassembles a list of raw response lines from ELM327 into parsed ISO-TP messages.
     * Groups frames by CAN ID (e.g. 7E8, 7E9) and reassembles multi-frame payloads.
     */
    fun reassembleLines(lines: List<String>): List<IsoTpMessage> {
        val frames = lines.map { CanFrameParser.parseFrame(it) }
            .filter { it.dataBytes.isNotEmpty() }

        if (frames.isEmpty()) {
            return emptyList()
        }

        // Group frames by CAN ID (or "NO_HEADER" if no header)
        val groupedByCanId = frames.groupBy { it.canId ?: "NO_HEADER" }
        val results = mutableListOf<IsoTpMessage>()

        for ((canId, canFrames) in groupedByCanId) {
            val resolvedCanId = if (canId == "NO_HEADER") null else canId

            var currentFrames = mutableListOf<RawCanFrame>()
            var expectedTotalLength = 0
            var payloadAccumulator = mutableListOf<Int>()
            var isMultiFrame = false
            var expectedSequenceNumber = 1
            var isCurrentMalformed = false
            var currentMalformedReason: String? = null

            for (frame in canFrames) {
                when (frame.pciType) {
                    IsoTpPciType.SINGLE_FRAME -> {
                        // Flush any unfinalized multi-frame
                        if (currentFrames.isNotEmpty()) {
                            results.add(
                                createIsoTpMessage(
                                    resolvedCanId,
                                    currentFrames,
                                    payloadAccumulator,
                                    expectedTotalLength,
                                    isCurrentMalformed,
                                    currentMalformedReason
                                )
                            )
                            currentFrames = mutableListOf()
                            payloadAccumulator = mutableListOf()
                            isMultiFrame = false
                            isCurrentMalformed = false
                            currentMalformedReason = null
                        }

                        // Single Frame handling: check length in lower nibble
                        val sfLength = frame.dataBytes[0] and 0x0F
                        val availableBytes = frame.dataBytes.size - 1
                        val isSfMalformed = sfLength <= 0 || sfLength > availableBytes
                        val sfBytes = if (isSfMalformed) {
                            frame.payloadBytes
                        } else {
                            frame.dataBytes.subList(1, 1 + sfLength)
                        }

                        results.add(
                            IsoTpMessage(
                                canId = resolvedCanId,
                                individualFrames = listOf(frame),
                                reconstructedPayloadHex = sfBytes.joinToString("") { "%02X".format(it) },
                                reconstructedBytes = sfBytes,
                                isComplete = !isSfMalformed,
                                totalExpectedLength = sfLength,
                                isMalformed = isSfMalformed,
                                malformedReason = if (isSfMalformed) "Invalid single frame length: $sfLength (available: $availableBytes)" else null
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
                                    expectedTotalLength,
                                    isCurrentMalformed,
                                    currentMalformedReason
                                )
                            )
                            currentFrames = mutableListOf()
                            payloadAccumulator = mutableListOf()
                        }

                        isMultiFrame = true
                        isCurrentMalformed = false
                        currentMalformedReason = null
                        currentFrames.add(frame)

                        // 12-bit total message length: ((dataBytes[0] & 0x0F) << 8) | dataBytes[1]
                        expectedTotalLength = if (frame.dataBytes.size >= 2) {
                            ((frame.dataBytes[0] and 0x0F) shl 8) or (frame.dataBytes[1] and 0xFF)
                        } else {
                            0
                        }
                        // FIX P0-2: ISO 15765-2 limits the 12-bit length field to 4095 bytes.
                        // A frame advertising more than 4095 bytes is structurally impossible on
                        // standard CAN (max 4095 data bytes for extended addressing, 4095 for
                        // normal — the latter is already at the protocol maximum).
                        // Accepting it unchallenged would cause unbounded growth of
                        // payloadAccumulator and potential OOM on malicious/malformed ECUs.
                        if (expectedTotalLength > 4095) {
                            isCurrentMalformed = true
                            currentMalformedReason = "First Frame length field ($expectedTotalLength) exceeds ISO 15765-2 maximum of 4095 — rejecting as malformed"
                            expectedTotalLength = 0
                            payloadAccumulator.clear()
                            currentFrames.add(frame)
                            continue
                        }

                        expectedSequenceNumber = 1
                        payloadAccumulator.addAll(frame.payloadBytes)

                        if (expectedTotalLength < 8) {
                            isCurrentMalformed = true
                            currentMalformedReason = "First Frame specifies invalid ISO-TP length < 8 ($expectedTotalLength)"
                        }

                        expectedSequenceNumber = 1
                        payloadAccumulator.addAll(frame.payloadBytes)
                    }

                    IsoTpPciType.CONSECUTIVE_FRAME -> {
                        if (isMultiFrame) {
                            currentFrames.add(frame)
                            val actualSn = frame.dataBytes[0] and 0x0F
                            if (actualSn != expectedSequenceNumber) {
                                isCurrentMalformed = true
                                currentMalformedReason = "ISO-TP sequence number mismatch: expected $expectedSequenceNumber, got $actualSn"
                            }
                            expectedSequenceNumber = (expectedSequenceNumber + 1) % 16

                            payloadAccumulator.addAll(frame.payloadBytes)

                            if (payloadAccumulator.size >= expectedTotalLength && expectedTotalLength > 0) {
                                val trimmedPayload = payloadAccumulator.take(expectedTotalLength)
                                results.add(
                                    IsoTpMessage(
                                        canId = resolvedCanId,
                                        individualFrames = currentFrames.toList(),
                                        reconstructedPayloadHex = trimmedPayload.joinToString("") { "%02X".format(it) },
                                        reconstructedBytes = trimmedPayload,
                                        isComplete = !isCurrentMalformed,
                                        totalExpectedLength = expectedTotalLength,
                                        isMalformed = isCurrentMalformed,
                                        malformedReason = currentMalformedReason
                                    )
                                )
                                currentFrames = mutableListOf()
                                payloadAccumulator = mutableListOf()
                                isMultiFrame = false
                                isCurrentMalformed = false
                                currentMalformedReason = null
                            }
                        } else {
                            // Stray consecutive frame without preceding First Frame
                            results.add(
                                IsoTpMessage(
                                    canId = resolvedCanId,
                                    individualFrames = listOf(frame),
                                    reconstructedPayloadHex = frame.payloadBytes.joinToString("") { "%02X".format(it) },
                                    reconstructedBytes = frame.payloadBytes,
                                    isComplete = false,
                                    totalExpectedLength = frame.payloadBytes.size,
                                    isMalformed = true,
                                    malformedReason = "Orphan consecutive frame received without preceding First Frame"
                                )
                            )
                        }
                    }

                    IsoTpPciType.FLOW_CONTROL -> {
                        currentFrames.add(frame)
                    }

                    IsoTpPciType.NON_ISO_TP -> {
                        val bytes = frame.payloadBytes
                        results.add(
                            IsoTpMessage(
                                canId = resolvedCanId,
                                individualFrames = listOf(frame),
                                reconstructedPayloadHex = bytes.joinToString("") { "%02X".format(it) },
                                reconstructedBytes = bytes,
                                isComplete = true,
                                totalExpectedLength = bytes.size,
                                isMalformed = false,
                                malformedReason = null
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
                val isDone = (trimmed.size == expectedTotalLength && expectedTotalLength > 0)
                results.add(
                    IsoTpMessage(
                        canId = resolvedCanId,
                        individualFrames = currentFrames,
                        reconstructedPayloadHex = trimmed.joinToString("") { "%02X".format(it) },
                        reconstructedBytes = trimmed,
                        isComplete = isDone && !isCurrentMalformed,
                        totalExpectedLength = expectedTotalLength,
                        isMalformed = isCurrentMalformed || (!isDone && expectedTotalLength > 0),
                        malformedReason = currentMalformedReason ?: if (!isDone) "Incomplete multi-frame message: received ${trimmed.size}/$expectedTotalLength bytes" else null
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
        expectedLength: Int,
        isMalformed: Boolean = false,
        malformedReason: String? = null
    ): IsoTpMessage {
        val trimmed = if (expectedLength in 1..payload.size) payload.take(expectedLength) else payload
        val isDone = (trimmed.size == expectedLength && expectedLength > 0)
        return IsoTpMessage(
            canId = canId,
            individualFrames = frames,
            reconstructedPayloadHex = trimmed.joinToString("") { "%02X".format(it) },
            reconstructedBytes = trimmed,
            isComplete = isDone && !isMalformed,
            totalExpectedLength = expectedLength,
            isMalformed = isMalformed || (!isDone && expectedLength > 0),
            malformedReason = malformedReason
        )
    }
}
