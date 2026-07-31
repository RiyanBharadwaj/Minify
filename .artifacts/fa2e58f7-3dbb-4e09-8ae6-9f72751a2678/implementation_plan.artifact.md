# Implementation Plan - Fix Post-Edit Image Expansion (Final Alignment)

The photo editor still exhibits an "expansion" bug where the image stretches to fill the screen width after any edit. While previous attempts focused on layout parameters and density, the root cause is the scaling rule itself: the editor's source `ImageView` is using `FIT_CENTER` (which upscales), while all other overlays use `Inside` logic (which doesn't).

## Proposed Changes

### 1. Scaling Rule Alignment

#### [MODIFY] [PhotoEditorHost.kt](file:///C:/Users/Administrator/StudioProjects/Minify/app/src/main/java/com/shanks/minify/editor/PhotoEditorHost.kt)

- **Switch to `CENTER_INSIDE`**:
    - In the `AndroidView` factory, change `source.scaleType` from `FIT_CENTER` to `CENTER_INSIDE`.
    - In the `reassertSourceScaling()` helper, change `iv.scaleType` to `CENTER_INSIDE`.
    - **Why**: `CENTER_INSIDE` is the Android equivalent of Compose's `ContentScale.Inside`. It fits the bitmap but never upscales it, ensuring that small images stay centered at native resolution even if their container expands to fill the screen.

### 2. Robust Layout Stability

#### [MODIFY] [PhotoEditorHost.kt](file:///C:/Users/Administrator/StudioProjects/Minify/app/src/main/java/com/shanks/minify/editor/PhotoEditorHost.kt)

- **Cast-Free Layout Capture**:
    - Update `sourceBaseLp` to store `android.view.ViewGroup.LayoutParams` instead of `RelativeLayout.LayoutParams`.
    - Add a `sourceBaseSize` (`IntArray`) to track the original width/height.
    - In the factory, capture the `LayoutParams` directly without casting.
    - In `reassertSourceScaling()`, restore the original width/height to the captured object before re-applying it. This ensures stability even if the `ImageView` is not a direct child of a `RelativeLayout`.

### 3. Diagnostic Cleanup

#### [MODIFY] [PhotoEditorHost.kt](file:///C:/Users/Administrator/StudioProjects/Minify/app/src/main/java/com/shanks/minify/editor/PhotoEditorHost.kt)

- **Enhanced Logging**: Add a log in the factory to print the actual class name of the captured `LayoutParams`. This will confirm if the previous `as? RelativeLayout.LayoutParams` cast was failing.

## Verification Plan

### Automated Tests
- Build the project: `gradle_build("app:assembleDebug")`.

### Manual Verification
1. **Clean Rebuild**: Perform a **Clean Project** and **Reinstall** to ensure the new scaling rule is active.
2. **Post-Edit Stability**: Open a small image, Rotate/Mirror it. Verify the image stays centered at its native footprint (no "expansion").
3. **Compare Alignment**: Verify that the "Original" peek (using `ContentScale.Inside`) perfectly matches the editor's current state (now also using `CENTER_INSIDE`).
4. **Logcat Check**: Confirm that `scale=CENTER_INSIDE` is logged for both "open" and "afterEdit".
