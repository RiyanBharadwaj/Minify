# Implementation Plan - Ordered Geometry Pipeline for Photo Editor

The current "Original" peek in the photo editor causes an "expansion" jump because it doesn't account for crops or the specific order of user transformations. I will implement a geometry pipeline to ensure the peek framing perfectly matches the edited framing.

## Proposed Changes

### [Component: Photo Editor]

#### [MODIFY] [PhotoEditorHost.kt](file:///C:/Users/Administrator/StudioProjects/Minify/app/src/main/java/com/shanks/minify/editor/PhotoEditorHost.kt)

- **Introduce `GeometryOp`**: Define a sealed interface to track `Rotate90Cw`, `MirrorH`, and `Crop(rect)`.
- **Implement `applyGeometry`**: A helper function that sequentially applies a list of `GeometryOp` to a bitmap, ensuring intermediate bitmaps are recycled to save memory.
- **Enhanced State Tracking**:
    - Add `currentGeometry: List<GeometryOp>` to the editor state.
    - Include the geometry list in `PhotoBaseState` and the undo/redo stack.
- **Automatic Peek Generation**:
    - Add a `LaunchedEffect` that regenerates `peekOriginalBitmap` whenever the `originalBitmap` or `currentGeometry` changes.
    - This ensures the "Original" view is always framed exactly like the current "Edited" view.
- **Update Call Sites**:
    - Update `onApply` (Crop), `onRotate`, and `onMirror` to append their respective operations to the geometry list.
    - Update `Filter` and `Adjust` to preserve the current geometry list during edits.
- **Harmonize Full Compare**:
    - Update the slider-based comparison overlay to use `peekOriginalBitmap` instead of the raw `originalBitmap`, so the wipe also stays pixel-aligned after a crop.

## Verification Plan

### Manual Verification
1. **Crop Check**:
    - Crop an image. Press and hold. Verify the peek shows the *cropped* version of the original image (matching current framing).
2. **Rotation Order Check**:
    - Rotate -> Mirror -> Rotate. Press and hold. Verify the peek stays perfectly still and matches the transformed framing.
3. **Wipe Alignment**:
    - Crop an image, then open the "Compare" screen. Move the slider and verify the "Original" and "Edited" versions are pixel-aligned across the divider.
