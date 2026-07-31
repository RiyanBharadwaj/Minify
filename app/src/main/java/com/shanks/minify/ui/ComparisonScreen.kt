package com.shanks.minify.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shanks.minify.ui.compare.ImageComparator
import com.shanks.minify.ui.compare.VideoComparator
import com.shanks.minify.ui.theme.BgDark
import com.shanks.minify.ui.theme.TextSec

/**
 * Describes the pair of media to compare on the [ComparisonScreen]. The sealed
 * type lets the screen dispatch to the correct comparison mode (image slider vs
 * synchronized side-by-side video playback) purely from the source shape, with
 * no runtime flags.
 *
 * - [Images]: an original ("before") and compressed ("after") still image
 *   (Requirement 10.1).
 * - [Videos]: an original ("before") and compressed ("after") video
 *   (Requirement 11.1).
 */
sealed interface ComparisonSource {
    data class Images(val before: Uri, val after: Uri) : ComparisonSource
    data class Videos(val before: Uri, val after: Uri) : ComparisonSource
}

/**
 * Full-screen before/after comparison overlay shown after a compression
 * completes. It dispatches to an image comparator or a video comparator based on
 * the [source] sealed type and exposes a single close affordance via [onClose].
 *
 * This is the scaffold established in task 8.1; the actual comparator content is
 * filled in by later tasks:
 * - Image mode → `ImageComparator` (task 8.2): draggable reveal divider with a
 *   shared zoom/pan viewport.
 * - Video mode → `VideoComparator` (task 8.3): two synchronized ExoPlayer
 *   surfaces side by side with a shared control row, drift sync, single-source
 *   audio, synchronized completion, and lifecycle-aware release.
 *
 * Requirements: 10.1, 11.1.
 */
@Composable
fun ComparisonScreen(
    source: ComparisonSource,
    onClose: () -> Unit,
) {
    BackHandler { onClose() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark),
    ) {
        when (source) {
            is ComparisonSource.Images -> ImageComparator(source = source)
            is ComparisonSource.Videos -> VideoComparator(source = source)
        }

        // Close affordance — dismisses the comparison overlay.
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
        ) {
            Text(text = "✕", fontSize = 22.sp, color = TextSec)
        }
    }
}
