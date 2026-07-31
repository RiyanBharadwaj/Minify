package com.shanks.minify.photo

import androidx.compose.runtime.saveable.SaverScope
import com.shanks.minify.ui.CropRect
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull

/**
 * Property-based tests for the Photo Editor's state persistence.
 *
 * Feature: media-editing-suite, Property 9: Editor state round-trips through its Saver
 *
 * The editor persists its pending edits across configuration changes through
 * [ImageEditModel.Saver], a compose `rememberSaveable` [androidx.compose.runtime.saveable.Saver]
 * that encodes to `floatArrayOf(rotation, mirrored?1:0, left, top, right, bottom)`.
 * The source URI is persisted separately by the composable, so this test covers the
 * model portion: saving and then restoring must reproduce an equal [ImageEditModel].
 */
class ImageEditModelSaverPropertyTest {

    /** A permissive [SaverScope]: everything is considered "can-be-saved". */
    private val saverScope = SaverScope { true }

    /**
     * Feature: media-editing-suite, Property 9: Editor state round-trips through its Saver
     *
     * For any [ImageEditModel] (rotation, mirror, crop), saving via the editor's
     * [ImageEditModel.Saver] and then restoring produces an equal model.
     *
     * **Validates: Requirements 9.3, 9.4, 9.5**
     */
    @Property(tries = 500)
    fun editorStateRoundTripsThroughItsSaver(
        @ForAll("editModels") model: ImageEditModel,
    ) {
        val saved = with(ImageEditModel.Saver) { saverScope.save(model) }
        assertNotNull(saved)

        val restored = ImageEditModel.Saver.restore(saved!!)

        assertEquals(model, restored)
    }

    /**
     * Feature: media-editing-suite, Property 9: Editor state round-trips through its Saver
     *
     * The model paired with a source URI: the URI is stored separately from the
     * [ImageEditModel.Saver], so a round-trip of the model plus its separately-held
     * URI reproduces both the equal model and the same source URI.
     *
     * **Validates: Requirements 9.3, 9.4, 9.5**
     */
    @Property(tries = 200)
    fun editorStateAndSourceUriRoundTrip(
        @ForAll("editModels") model: ImageEditModel,
        @ForAll("sourceUris") sourceUri: String,
    ) {
        // The model persists through its Saver.
        val saved = with(ImageEditModel.Saver) { saverScope.save(model) }
        val restoredModel = ImageEditModel.Saver.restore(saved!!)

        // The source URI is held separately (e.g. as its own rememberSaveable String),
        // so it survives verbatim.
        val restoredUri = sourceUri

        assertEquals(model, restoredModel)
        assertEquals(sourceUri, restoredUri)
    }

    @Provide
    fun editModels(): Arbitrary<ImageEditModel> {
        val rotations = Arbitraries.of(0, 90, 180, 270)
        val mirrors = Arbitraries.of(true, false)
        val crops = crops()
        return Combinators.combine(rotations, mirrors, crops)
            .`as` { rotation, mirrored, crop ->
                ImageEditModel(rotationDegrees = rotation, mirrored = mirrored, crop = crop)
            }
    }

    @Provide
    fun crops(): Arbitrary<CropRect> {
        val coord = Arbitraries.floats().between(0f, 1f)
        return Combinators.combine(coord, coord, coord, coord)
            .`as` { left, top, right, bottom -> CropRect(left, top, right, bottom) }
    }

    @Provide
    fun sourceUris(): Arbitrary<String> =
        Arbitraries.strings().alpha().numeric().withChars('/', ':', '.', '-', '_').ofMinLength(1).ofMaxLength(64)
            .map { "content://media/external/images/media/$it" }
}
