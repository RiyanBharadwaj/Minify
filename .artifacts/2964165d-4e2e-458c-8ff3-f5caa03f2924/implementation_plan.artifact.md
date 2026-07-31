# Native Video Editing & Icon Branding

The goal is to restore basic video editing (trimming and cropping) using native Android tools (Media3) without the heavy FFmpeg dependency, and to update the app's branding with the provided icon design.

## User Review Required

> [!NOTE]
> The new video editor will be built directly into the app using Jetpack Compose. It will focus on **Trimming** and **Cropping**, which are supported natively by Media3 Transformer. Advanced features like stickers or multi-track splitting (from the removed LibreCuts) will not be present in this initial native version.

> [!IMPORTANT]
> I will replace the app icon with the design you provided. The reason the previous icon was lost is that I removed a redundant-looking PNG file while optimizing, not realizing it was your preferred design. I will ensure the new design is correctly set up as an Adaptive Icon.

## Proposed Changes

### 1. Native Video Editor (Compose)

#### [NEW] [NativeVideoEditor.kt](file:///C:/Users/Administrator/StudioProjects/Minify/app/src/main/java/com/shanks/minify/ui/editor/video/NativeVideoEditor.kt)
- Create a new Compose-based editing screen.
- **Preview**: Integrated ExoPlayer preview for real-time feedback.
- **Trimming**: A range slider to select the start and end times.
- **Cropping**: Integration with the existing `CropOverlay` to select the spatial area.
- **State Management**: Modifies the `EditState` which is already supported by the compression pipeline.

#### [MODIFY] [MainScreen.kt](file:///C:/Users/Administrator/StudioProjects/Minify/app/src/main/java/com/shanks/minify/ui/MainScreen.kt)
- Re-add the "Edit" button to the Video preview.
- Launch the `NativeVideoEditor` overlay when clicked.

### 2. Branding & Icons

#### [NEW] App Icon
- Save the provided image as the new `ic_launcher_foreground`.
- Update `ic_launcher.xml` and `ic_launcher_round.xml` mipmaps.

#### [DELETE] Unnecessary Resources
- Scan the `res/drawable` and `res/mipmap` directories for icons that are no longer referenced in the code (leftovers from the merged-then-deleted editor) and remove them.

### 3. Cleanup

#### [MODIFY] [app/build.gradle.kts](file:///C:/Users/Administrator/StudioProjects/Minify/app/build.gradle.kts)
- Ensure all necessary Media3 Effect dependencies are present for native cropping.

## Verification Plan

### Automated Tests
- Build and run the app to ensure no resource-linking errors.
- Run a test compression with a native trim/crop applied.

### Manual Verification
- **App Icon**: Verify the new icon appears on the device launcher.
- **Video Editing**: Open the "Edit" screen, apply a trim and a crop, and verify they are correctly applied to the final compressed video.
- **APK Size**: Verify that adding these native features keeps the APK size significantly lower than the FFmpeg version (targeting ~8-9 MB).
