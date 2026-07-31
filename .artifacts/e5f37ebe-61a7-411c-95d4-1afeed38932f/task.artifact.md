# Task: Ordered Geometry Pipeline for Photo Editor

- [x] Define `GeometryOp` and `applyGeometry` in `PhotoEditorHost.kt`
- [x] Update `PhotoBaseState` and `currentGeometry` state tracking
- [x] Refactor `loadBase` and `commitBase` to handle geometry lists
- [x] Add `LaunchedEffect` for automatic `peekOriginalBitmap` generation
- [x] Update all tool call sites (Crop, Rotate, Mirror, Filter, Adjust) to pass updated geometry
- [x] Update `CompareWipeOverlay` usage to use geometry-matched original
- [x] Verify fix for aspect ratio jumps during peek
