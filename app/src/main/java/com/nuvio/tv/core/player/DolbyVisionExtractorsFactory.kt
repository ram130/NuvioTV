package com.nuvio.tv.core.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import com.nuvio.tv.core.player.dvmkv.MatroskaExtractor as DvMatroskaExtractor
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * App-level Dolby Vision Profile 7 to 8.1 conversion that needs no forked Media3.
 *
 * It wraps a stock [ExtractorsFactory] and, for video tracks of containers whose
 * RPU rides in-band as NAL units (MP4 / fMP4 = length-delimited, TS = Annex-B),
 * intercepts the sample stream at the [TrackOutput] level and rewrites the
 * Dolby Vision RPU NAL (type 62) via [DoviBridge], drops the enhancement-layer
 * NAL units, and rewrites the codec string (dvhe.07 becomes dvhe.08).
 *
 * Matroska is special: the RPU arrives as BlockAdditional data that stock Media3
 * discards before any TrackOutput, so for MKV this factory swaps in the vendored
 * [com.nuvio.tv.core.player.dvmkv.MatroskaExtractor], which surfaces the RPU through
 * [DolbyVisionMatroskaTransformer].
 *
 * For any non-DV7 content (or when [config] is inactive) every wrapper is a strict
 * pass-through, so normal playback of all formats is unaffected.
 */
@UnstableApi
internal class DolbyVisionExtractorsFactory(
    private val delegate: ExtractorsFactory,
    private val config: DolbyVisionConversionConfig
) : ExtractorsFactory {

    override fun createExtractors(): Array<Extractor> =
        delegate.createExtractors().map(::wrap).toTypedArray()

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>
    ): Array<Extractor> =
        delegate.createExtractors(uri, responseHeaders).map(::wrap).toTypedArray()

    private fun wrap(extractor: Extractor): Extractor {
        if (!config.active) return extractor
        // Matroska: the DV7 RPU rides in BlockAdditional, which the stock
        // MatroskaExtractor discards before any TrackOutput. Swap it for the
        // vendored extractor that surfaces the RPU through a transformer.
        if (extractor.javaClass.name == STOCK_MATROSKA_EXTRACTOR) {
            return DvMatroskaExtractor(
                DefaultSubtitleParserFactory(),
                /* flags= */ 0,
                DolbyVisionMatroskaTransformer(config)
            )
        }
        val nalFormat = nalFormatFor(extractor) ?: return extractor
        return DolbyVisionExtractor(extractor, config, nalFormat)
    }

    private fun nalFormatFor(extractor: Extractor): NalFormat? {
        val name = extractor.javaClass.name
        return when {
            // RPU is in-band in the sample for these containers, so reachable here.
            name.contains("FragmentedMp4Extractor") -> NalFormat.LENGTH_DELIMITED
            name.contains("Mp4Extractor") -> NalFormat.LENGTH_DELIMITED
            name.contains("TsExtractor") -> NalFormat.ANNEX_B
            else -> null
        }
    }

    private companion object {
        const val STOCK_MATROSKA_EXTRACTOR = "androidx.media3.extractor.mkv.MatroskaExtractor"
    }
}

/** How HEVC NAL units are framed in the sample stream for a given container. */
internal enum class NalFormat { ANNEX_B, LENGTH_DELIMITED }

/**
 * Per-playback DV7 to 8.1 conversion diagnostics, fed by the factory/transformer and read
 * by the player diagnostics. Reset per playback alongside the DoviBridge counters.
 */
internal object DolbyVisionConversionStats {
    private val codecStringRewriteCount = AtomicLong(0)
    @Volatile private var lastSourceProfile: Int? = null
    @Volatile private var lastConversionMode: Int? = null

    fun reset() {
        codecStringRewriteCount.set(0)
        lastSourceProfile = null
        lastConversionMode = null
    }

    /** Records the SOURCE DV profile (pre-conversion), e.g. 7. */
    fun recordSourceProfile(profile: Int?) {
        if (profile != null) lastSourceProfile = profile
    }

    fun recordConversionMode(mode: Int) {
        lastConversionMode = mode
    }

    fun recordCodecStringRewrite() {
        codecStringRewriteCount.incrementAndGet()
    }

    fun getCodecStringRewriteCount(): Long = codecStringRewriteCount.get()
    fun getLastSourceProfile(): Int? = lastSourceProfile
    fun getLastSelectedConversionMode(): Int? = lastConversionMode
}

/**
 * Drives the per-stream conversion decision. Mirrors the auto-pick used by the
 * extractor hook installer: profile-7 default = mode 1 (ToMel); profile-7 with
 * preserve-mapping = mode 5 (8.1 preserve); profile 5 = mode 3; a [forcedMode]
 * in 0..4 overrides all of it.
 */
internal data class DolbyVisionConversionConfig(
    val active: Boolean,
    val forcedMode: Int = -1,
    val preserveMapping: Boolean = false,
    val dv5Enabled: Boolean = false,
    /** True when the user explicitly chose "Convert to DV8.1" (not AUTO). */
    val manualDv81: Boolean = false
) {
    /** Manual mode-2 default with per-RPU fallback to mode 1 (not for AUTO / forced). */
    val allowMode2Fallback: Boolean get() = manualDv81 && forcedMode !in 0..4

    /**
     * DV5 via the toggle is signal-only (codec rewritten to 8.1, profile-5 RPU kept). A
     * libdovi RPU rewrite runs only when a mode is forced in Advanced. DV7 always converts.
     */
    val convertDv5Rpu: Boolean get() = forcedMode in 0..4

    /** True when a track of [profile] should be converted. */
    fun shouldConvert(profile: Int?): Boolean {
        if (!active) return false
        return when (profile) {
            7 -> true
            // DV5 is already single-layer; only convert it when the user explicitly chose
            // Convert to DV8.1. AUTO leaves DV5 alone (converting it breaks colors).
            5 -> dv5Enabled && manualDv81
            else -> false
        }
    }

    /** libdovi conversion mode to use for [profile]. */
    fun conversionMode(profile: Int?): Int {
        if (forcedMode in 0..4) return forcedMode
        return when {
            (profile == 7 || profile == null) && preserveMapping -> 5
            profile == 5 -> 3
            manualDv81 -> 2 // manual Convert to DV8.1 prefers mode 2 (falls back to 1)
            else -> 1       // AUTO convert stays on mode 1
        }
    }
}

/** Wraps an [Extractor] to inject a DV-rewriting [ExtractorOutput]. */
@UnstableApi
private class DolbyVisionExtractor(
    private val delegate: Extractor,
    private val config: DolbyVisionConversionConfig,
    private val nalFormat: NalFormat
) : Extractor {

    override fun init(output: ExtractorOutput) {
        delegate.init(DolbyVisionExtractorOutput(output, config, nalFormat))
    }

    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    @Throws(IOException::class)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
        delegate.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) = delegate.seek(position, timeUs)

    override fun release() = delegate.release()

    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation
}

/** Wraps an [ExtractorOutput] to swap video [TrackOutput]s for DV-rewriting ones. */
@UnstableApi
private class DolbyVisionExtractorOutput(
    private val delegate: ExtractorOutput,
    private val config: DolbyVisionConversionConfig,
    private val nalFormat: NalFormat
) : ExtractorOutput {

    override fun track(id: Int, type: Int): TrackOutput {
        val track = delegate.track(id, type)
        return if (type == C.TRACK_TYPE_VIDEO) {
            DolbyVisionTrackOutput(track, config, nalFormat)
        } else {
            track
        }
    }

    override fun endTracks() = delegate.endTracks()

    override fun seekMap(seekMap: SeekMap) = delegate.seekMap(seekMap)
}

/**
 * The actual RPU-rewriting [TrackOutput]. Buffers a sample's bytes and, on
 * [sampleMetadata], rewrites the DV RPU NAL (and drops EL NAL units) before
 * forwarding to the delegate. A no-op pass-through unless the track is a DV
 * profile the config wants converted.
 */
@UnstableApi
private class DolbyVisionTrackOutput(
    private val delegate: TrackOutput,
    private val config: DolbyVisionConversionConfig,
    private val nalFormat: NalFormat
) : TrackOutput {

    private val scratch = ParsableByteArray()
    private val out = ByteArrayOutputStream()
    private var pending = ByteArray(0)

    private var converting = false
    private var rewriteSamples = false
    private var profile: Int? = null
    private var codecs: String? = null
    private var nalLengthFieldLength = 4

    override fun durationUs(durationUs: Long) = delegate.durationUs(durationUs)

    override fun format(format: Format) {
        profile = parseDvProfile(format.codecs)
        converting = config.shouldConvert(profile)
        // Signal-only DV5 (toggle) just relabels the codec; its samples pass through
        // untouched, so skip the per-sample buffer/scan entirely.
        rewriteSamples = converting && !(profile == 5 && !config.convertDv5Rpu)
        nalLengthFieldLength = parseNalLengthFieldLength(format)
        var outFormat = format
        if (converting) {
            DolbyVisionConversionStats.recordSourceProfile(profile)
            val rewritten = rewriteDvCodecString(format.codecs)
            if (rewritten != null && rewritten != format.codecs) {
                outFormat = format.buildUpon().setCodecs(rewritten).build()
                DolbyVisionConversionStats.recordCodecStringRewrite()
            }
        }
        codecs = outFormat.codecs
        delegate.format(outFormat)
    }

    @Throws(IOException::class)
    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int
    ): Int {
        if (!rewriteSamples) return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)
        val bytes = ByteArray(length)
        val read = input.read(bytes, 0, length)
        if (read == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) return C.RESULT_END_OF_INPUT
            throw EOFException()
        }
        sampleData(ParsableByteArray(bytes, read), read, sampleDataPart)
        return read
    }

    override fun sampleData(
        data: ParsableByteArray,
        length: Int,
        sampleDataPart: Int
    ) {
        if (!rewriteSamples || sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN || length <= 0) {
            delegate.sampleData(data, length, sampleDataPart)
            return
        }
        val incoming = ByteArray(length)
        data.readBytes(incoming, 0, length)
        pending = if (pending.isEmpty()) incoming else pending + incoming
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?
    ) {
        if (!rewriteSamples || pending.isEmpty()) {
            delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            return
        }
        // `offset` = trailing buffered bytes that belong to the NEXT sample. Keep
        // them in `pending` and rewrite only this sample's bytes. We deliver the
        // rewritten sample fresh and pass offset 0 (nothing delivered after it),
        // which keeps sizing correct even when the rewrite changes the length.
        val carrySize = offset.coerceIn(0, pending.size)
        val sampleEnd = pending.size - carrySize
        val sampleBytes = if (carrySize == 0) pending else pending.copyOfRange(0, sampleEnd)
        val carry = if (carrySize == 0) ByteArray(0) else pending.copyOfRange(sampleEnd, pending.size)
        val rewritten = when (nalFormat) {
            NalFormat.ANNEX_B -> rewriteAnnexB(sampleBytes)
            NalFormat.LENGTH_DELIMITED -> rewriteLengthDelimited(sampleBytes, nalLengthFieldLength)
        } ?: sampleBytes
        pending = carry
        scratch.reset(rewritten)
        delegate.sampleData(scratch, rewritten.size)
        delegate.sampleMetadata(timeUs, flags, rewritten.size, 0, cryptoData)
    }

    // ── Length-delimited (MP4 / fMP4) ──
    private fun rewriteLengthDelimited(sample: ByteArray, lengthFieldLength: Int): ByteArray? {
        if (sample.size < lengthFieldLength) return null
        out.reset()
        var changed = false
        var pos = 0
        while (pos + lengthFieldLength <= sample.size) {
            var nalSize = 0
            for (i in 0 until lengthFieldLength) {
                nalSize = (nalSize shl 8) or (sample[pos + i].toInt() and 0xFF)
            }
            val nalStart = pos + lengthFieldLength
            if (nalSize <= 0 || nalStart + nalSize > sample.size) return null
            val nal = sample.copyOfRange(nalStart, nalStart + nalSize)
            val transformed = transformNal(nal)
            if (transformed == null) {
                // Drop this NAL (enhancement layer).
                changed = true
            } else {
                if (transformed !== nal) changed = true
                writeLengthPrefix(out, transformed.size, lengthFieldLength)
                out.write(transformed, 0, transformed.size)
            }
            pos = nalStart + nalSize
        }
        if (pos != sample.size) return null
        return if (changed) out.toByteArray() else sample
    }

    // ── Annex-B (TS) ── ported from H265Reader.DolbyVisionTransformingTrackOutput
    private fun rewriteAnnexB(sample: ByteArray): ByteArray? {
        out.reset()
        val len = sample.size
        var scan = 0
        var changed = false
        while (scan < len) {
            val start = findStartCode(sample, scan, len)
            if (start < 0) break
            var next = findStartCode(sample, start + 3, len)
            if (next < 0) next = len
            if (start > scan) out.write(sample, scan, start - scan)
            val scLen = startCodeLength(sample, start, next)
            val payloadOffset = start + scLen
            if (payloadOffset >= next) {
                out.write(sample, start, next - start)
            } else {
                val nal = sample.copyOfRange(payloadOffset, next)
                val transformed = transformNal(nal)
                if (transformed == null) {
                    changed = true // drop EL NAL
                } else {
                    if (transformed !== nal) changed = true
                    out.write(sample, start, scLen)
                    out.write(transformed, 0, transformed.size)
                }
            }
            scan = next
        }
        if (scan < len) out.write(sample, scan, len - scan)
        return if (changed) out.toByteArray() else sample
    }

    /**
     * Returns the NAL to emit: the (possibly RPU-rewritten) NAL, or null to drop
     * it (enhancement-layer NAL that isn't the RPU).
     */
    private fun transformNal(nal: ByteArray): ByteArray? {
        if (nal.isEmpty()) return nal
        val nalType = (nal[0].toInt() ushr 1) and 0x3F
        val layerId = nuhLayerId(nal)
        if (layerId > 0 && nalType != NAL_TYPE_DV_RPU) {
            return null // drop enhancement-layer NAL
        }
        if (nalType != NAL_TYPE_DV_RPU) {
            return nal // pass non-RPU base-layer NAL through
        }
        val mode = config.conversionMode(profile)
        var converted = DoviBridge.convertDv7RpuToDv81(nal, mode)?.takeIf { it.isNotEmpty() }
        if (converted != null) {
            DolbyVisionConversionStats.recordConversionMode(mode)
        } else if (config.allowMode2Fallback && mode == 2) {
            converted = DoviBridge.convertDv7RpuToDv81(nal, 1)?.takeIf { it.isNotEmpty() }
            if (converted != null) DolbyVisionConversionStats.recordConversionMode(1)
        }
        return normalizeNuhLayerIdToZero(converted ?: nal)
    }

    private companion object {
        const val NAL_TYPE_DV_RPU = 62

        fun parseDvProfile(codecs: String?): Int? {
            if (codecs.isNullOrBlank()) return null
            val m = Regex("^(?:dvhe|dvav|dvh1|dva1)\\.(\\d+)\\.")
                .find(codecs.trim().lowercase()) ?: return null
            return m.groupValues[1].toIntOrNull()
        }

        /** dvhe.05/.07.xx becomes dvhe.08.xx so the Format advertises single-layer 8.1. */
        fun rewriteDvCodecString(codecs: String?): String? {
            if (codecs.isNullOrBlank()) return null
            return Regex("(?i)(dvhe|dvav|dvh1|dva1)\\.0[57]\\.")
                .replace(codecs) { mr -> "${mr.groupValues[1]}.08." }
        }

        fun parseNalLengthFieldLength(format: Format): Int {
            // HEVC hvcC: lengthSizeMinusOne is the low 2 bits of the byte at
            // index 21. Fall back to the near-universal 4 if not parseable.
            val csd = format.initializationData.firstOrNull() ?: return 4
            if (csd.size <= 21) return 4
            // Only trust it if this looks like an hvcC (configurationVersion == 1).
            if (csd[0].toInt() != 1) return 4
            return (csd[21].toInt() and 0x03) + 1
        }

        fun nuhLayerId(nal: ByteArray): Int {
            if (nal.size < 2) return 0
            val b0 = nal[0].toInt() and 0x01
            val b1 = nal[1].toInt() and 0xF8
            return (b0 shl 5) or (b1 ushr 3)
        }

        fun normalizeNuhLayerIdToZero(nal: ByteArray): ByteArray {
            if (nal.size < 2 || nuhLayerId(nal) == 0) return nal
            val copy = nal.copyOf()
            copy[0] = (copy[0].toInt() and 0xFE).toByte()
            copy[1] = (copy[1].toInt() and 0x07).toByte()
            return copy
        }

        fun writeLengthPrefix(out: ByteArrayOutputStream, value: Int, lengthFieldLength: Int) {
            for (i in lengthFieldLength - 1 downTo 0) {
                out.write((value ushr (i * 8)) and 0xFF)
            }
        }

        fun findStartCode(data: ByteArray, from: Int, limit: Int): Int {
            var i = from
            while (i + 2 < limit) {
                if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                    if (data[i + 2].toInt() == 1) return i
                    if (i + 3 < limit && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) return i
                }
                i++
            }
            return -1
        }

        fun startCodeLength(data: ByteArray, startCodeOffset: Int, limit: Int): Int {
            return if (startCodeOffset + 3 < limit &&
                data[startCodeOffset].toInt() == 0 &&
                data[startCodeOffset + 1].toInt() == 0 &&
                data[startCodeOffset + 2].toInt() == 0 &&
                data[startCodeOffset + 3].toInt() == 1
            ) 4 else 3
        }
    }
}
