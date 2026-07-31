# Task - Focused Style Sheet and Preview Stability

- [x] Lift `showStyle` state into the `PhotoEditorHost` to control global chrome visibility
- [x] Hide the toolbar, top Close button, and top Done button while the style sheet is open
- [x] Implement a hierarchical back-button handler (Close sheet -> Deselect layer -> Discard changes)
- [x] Pin the `TextLivePreview` width using actual pixels to prevent spill-over during alignment changes
- [x] Reset the transform (scale/rotate) on the live preview so it only mirrors typography
- [x] Refactor `LayerControls` to accept lifted state and callbacks
- [x] Verify that the UI feels less cramped and more focused when styling layers
- [x] Verify that alignment toggles no longer cause the preview text to slide out of its box
