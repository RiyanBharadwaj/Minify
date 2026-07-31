# Theme Video Comparison Screen

The Video Comparison screen currently uses a hardcoded `AccentPurple` color for its UI components (Slider and Play/Pause button). This ignores the app's dynamic theme, which allows users to select different accent colors (Cyan, Magenta, etc.).

## Proposed Changes

### UI Components

#### [MODIFY] [VideoComparator.kt](file:///C:/Users/Administrator/StudioProjects/Minify/app/src/main/java/com/shanks/minify/ui/compare/VideoComparator.kt)
- Replace all occurrences of `AccentPurple` with `MaterialTheme.colorScheme.primary`.
- Remove the unused `import com.shanks.minify.ui.theme.AccentPurple`.
- Add `import androidx.compose.material3.MaterialTheme`.

#### [MODIFY] [ImageComparator.kt](file:///C:/Users/Administrator/StudioProjects/Minify/app/src/main/java/com/shanks/minify/ui/compare/ImageComparator.kt)
- Remove the unused `import com.shanks.minify.ui.theme.AccentPurple`.

## Verification Plan

### Manual Verification
- Deploy the app.
- Change the app's accent color in Settings (e.g., to Cyan).
- Navigate to the Video Comparison screen.
- Verify that the Slider and Play/Pause button now use the selected accent color (Cyan) instead of Purple.
