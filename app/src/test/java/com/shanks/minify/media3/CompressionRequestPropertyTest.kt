package com.shanks.minify.media3

import com.shanks.minify.ui.CodecChoice
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Property-based test for [CompressionRequest.fromExtras], the defensive intent
 * parsing validator that guards `CompressionService.onStartCommand`.
 *
 * `fromExtras` is the pure, Android-Intent-independent core of the compression
 * start path: it takes the already-extracted intent extras and decides whether
 * they constitute a runnable request. It must be total (never throws) and must
 * accept a request if and only if every required extra is present and valid.
 */
class CompressionRequestPropertyTest {

    // Feature: media-editor-fixes, Property 15
    /**
     * Property 15: Compression request parsing rejects any missing or invalid
     * extra.
     *
     * For any generated combination of nullable/blank inputUri, nullable/blank
     * outputPath, nullable codecName (a mix of valid [CodecChoice] names and
     * random invalid strings), an editStatePresent boolean, and arbitrary
     * targetSizeMb / beforeSize: `fromExtras` returns [CompressionRequest.Valid]
     * if and only if inputUri is non-null-and-non-blank AND outputPath is
     * non-null-and-non-blank AND codecName is a valid CodecChoice name AND
     * editStatePresent is true; otherwise it returns [CompressionRequest.Invalid].
     * It must never throw.
     *
     * **Validates: Requirements 16.1, 16.2**
     */
    @Property(tries = 300)
    fun fromExtrasIsValidIffAllRequiredExtrasPresentAndCodecValid(
        @ForAll("inputs") input: Extras,
    ) {
        val result = runCatching {
            CompressionRequest.fromExtras(
                inputUri = input.inputUri,
                outputPath = input.outputPath,
                codecName = input.codecName,
                editStatePresent = input.editStatePresent,
                targetSizeMb = input.targetSizeMb,
                beforeSize = input.beforeSize,
            )
        }

        // Totality: fromExtras must never throw for any input.
        assertTrue(
            result.isSuccess,
            "fromExtras must never throw, but threw for $input: ${result.exceptionOrNull()}",
        )
        val request = result.getOrThrow()

        val validCodec = CodecChoice.entries.firstOrNull { it.name == input.codecName }
        val expectedValid = !input.inputUri.isNullOrBlank() &&
            !input.outputPath.isNullOrBlank() &&
            validCodec != null &&
            input.editStatePresent

        if (expectedValid) {
            assertTrue(
                request is CompressionRequest.Valid,
                "expected Valid for $input but got $request",
            )
            // When Valid, every returned field must equal the corresponding input
            // (codec parsed to the matching CodecChoice).
            val valid = request as CompressionRequest.Valid
            assertEquals(input.inputUri, valid.inputUri, "inputUri must round-trip")
            assertEquals(input.outputPath, valid.outputPath, "outputPath must round-trip")
            assertEquals(validCodec, valid.codec, "codec must parse to the matching CodecChoice")
            assertEquals(input.targetSizeMb, valid.targetSizeMb, "targetSizeMb must round-trip")
            assertEquals(input.beforeSize, valid.beforeSize, "beforeSize must round-trip")
        } else {
            assertTrue(
                request is CompressionRequest.Invalid,
                "expected Invalid for $input but got $request",
            )
        }
    }

    @Provide
    fun inputs(): Arbitrary<Extras> {
        val validCodecNames = CodecChoice.entries.map { it.name }

        // inputUri / outputPath: mix of null, blank (empty + whitespace) and
        // non-blank strings so both the null/blank rejection and acceptance paths
        // are exercised.
        val nullableStrings: Arbitrary<String?> = Arbitraries.oneOf(
            Arbitraries.just<String?>(null),
            Arbitraries.of("", " ", "   ", "\t", "\n"),
            Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(40)
                .map { it as String? },
        )

        // codecName: null, a valid CodecChoice name, or a random (likely invalid)
        // string. Random strings could in theory equal a valid name, but the
        // property re-derives expectedValid from the actual name so that is fine.
        val codecNames: Arbitrary<String?> = Arbitraries.oneOf(
            Arbitraries.just<String?>(null),
            Arbitraries.of(*validCodecNames.toTypedArray()).map { it as String? },
            Arbitraries.strings().ofMinLength(0).ofMaxLength(12).map { it as String? },
        )

        val editStatePresent: Arbitrary<Boolean> = Arbitraries.of(true, false)
        val targetSizeMb: Arbitrary<Float> = Arbitraries.floats().between(-10f, 10_000f)
        val beforeSize: Arbitrary<Long> = Arbitraries.longs().between(-1L, 10_000_000_000L)

        return Combinators.combine(
            nullableStrings,
            nullableStrings,
            codecNames,
            editStatePresent,
            targetSizeMb,
            beforeSize,
        ).`as` { uri, out, codec, edit, size, before ->
            Extras(uri, out, codec, edit, size, before)
        }
    }

    data class Extras(
        val inputUri: String?,
        val outputPath: String?,
        val codecName: String?,
        val editStatePresent: Boolean,
        val targetSizeMb: Float,
        val beforeSize: Long,
    )
}
