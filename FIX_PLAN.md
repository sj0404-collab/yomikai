# Fix Plan: Yomikai Lens Adapter & 5-8 Other Bugs

## 1. Lens Adapter in KOMA path (1071ms -> "no text")
**Root Cause**:
- `GlensOcrEngine.kt` uses HTTP request sending (`executeRequest` with `CONNECT_TIMEOUT_MS = 10_000` and `READ_TIMEOUT_MS = 60_000`) and `parseResponsePage` which returns `GlensPageResult`.
- If the request fails or returns empty/invalid response (e.g., HTTP status != 200..299, or `parseResponsePage` returns empty text), `recognizeText` or `recognizePage` throws an `IOException` ("GLens request failed with HTTP ...") or returns empty.
- In `KomaOcrEngine.kt`, `recognize` calculates `millis = System.currentTimeMillis() - start`. If `GlensOcrEngine` fails (e.g., timeout ~1000ms or network error) and returns `null` (via `KomaEngineBridge.read` returning `null` when `repository.scanPage` throws or returns empty), `KomaReaderScreen` shows `"«...» не дал текста этой странице"`.
- The "1071ms" is the measured runtime in the `KomaOcrEngine.recognize` block when `GlensOcrEngine` HTTP request times out or fails at exactly ~1071ms (likely `CONNECT_TIMEOUT_MS` or `READ_TIMEOUT_MS` combined with network latency, or specific `millis` calculation in `KomaOcrEngine.recognize` vs `GlensOcrEngine` `recognizePage` total time).
- The "second reader" (`ReaderActivity` / `ReaderLauncher`) uses `onOpenLegacy` and `skipKoma` intent extras. When `skipKoma = true`, it bypasses `KomaReaderScreen` and goes directly to `ReaderActivity`.
- **Fix Strategy**:
  1. In `GlensOcrEngine.kt`, adjust `executeRequest` to handle timeouts/failures gracefully and return a `GlensPageResult` with empty text (or specific flag) instead of throwing an exception that kills the KOMA bridge, OR ensure `KomaEngineBridge.read` catches the exception and returns a valid `PageOcr` with `millis` and `engine` info, but allows `KomaReaderScreen` to fallback to local KOMA engine if `GlensOcrEngine` fails (already implemented in `KomaReaderScreen.kt` line 366: `«...» не дал текста этой странице... Прочитано встроенным KOMA`).
  2. Analyze `ReaderActivity.kt` `onOpenLegacy` and `skipKoma` intent extras. Ensure `KomaChapterReader.kt` passes the correct `skipKoma` flag and `onOpenLegacy` callback logic.
  3. In `GlensOcrEngine.kt`, fix the `executeRequest` timeout logic: reduce `CONNECT_TIMEOUT_MS` to ~1000ms or handle the 1071ms threshold explicitly in `KomaOcrEngine.recognize` to trigger the fallback faster (avoiding the 1071ms "no text" freeze).
  4. In `KomaOcrEngine.kt`, ensure `recognize` uses `slot` and `band` correctly when calling `KomaEngineBridge.read` (currently `KomaReaderScreen` passes `band` but `KomaEngineBridge.read` ignores `band` in `repository.scanPage` because `OcrRepository` doesn't support bands natively, or `KomaEngineBridge` scales the image incorrectly). We will align `GlensOcrEngine` request payload `clientLanguage` and `clientRegion` with `ReaderActivity`'s `onOpenLegacy` intent extras (which might contain language/region preferences from the legacy reader).

## 2. KomaChapterLoader cache name collision (path vs id replacement)
**Location**: `/home/user/yomikai_main/app/src/main/java/eu/kanade/tachiyomi/ui/koma/KomaOcrEngine.kt` (cacheName function) and `KomaChapterLoader.kt`.
**Bug**: `cacheName` uses `id.replace(Regex("[^\\p{L}\\p{N}._-]"), "_")`. If `id` is `chapter/66`, it becomes `chapter_66_...`. But `KomaChapterLoader` uses `id = "chapter/$chapterId"` and `KomaPageStore` uses `KomaPageStore.keyOf(path)`. Collisions occur when different chapters share the same sanitized name.
**Fix**: Use a unique hash or prefix for `cacheName` in `KomaOcrEngine.kt` and ensure `KomaChapterLoader` uses `chapterId` directly in the cache file name instead of the sanitized path.

## 3. KomaOcrEngine bitmap recycling in `recognize` finally block
**Location**: `/home/user/yomikai_main/app/src/main/java/eu/kanade/tachiyomi/ui/koma/KomaOcrEngine.kt` (line ~700-750).
**Bug**: `image.recycle()` and `decoded.recycle()` in `finally` block can recycle the `cachedCopy` from `pageCache`, causing "trying to use a recycled bitmap" in `KomaReaderScreen`.
**Fix**: Check `if (image !== cachedCopy && !image.isRecycled) image.recycle()` and same for `decoded`. (Already partially present, but needs verification for `region` and `upscaleForOcr` paths).

## 4. AutoReadEngine GLens-fix duplication
**Location**: `/home/user/yomikai_main/app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt` (AutoReadEngine logic) and `KomaReaderScreen.kt`.
**Bug**: Duplicated logic for handling Google Lens failures in `AutoReadEngine` vs `KomaReaderScreen`. `AutoReadEngine` in `ReaderActivity` uses `autoReadFrameBusy` and `frameGeometryOf` which might conflict with `KomaOcrEngine`'s `slot/band` handling.
**Fix**: Consolidate the GLens-fix logic in `KomaEngineBridge.read` to return a `PageOcr` with `millis` and `engine` info, and ensure `AutoReadEngine` in `ReaderActivity` uses the same `skipKoma` logic.

## 5. ReadingOrderSorter logic
**Location**: `/home/user/yomikai_main/core/common/src/main/kotlin/tachiyomi/core/common/util/system/ReadingOrderSorter.kt` (referenced in session memory).
**Bug**: Sorting logic for vertical/horizontal lines in `GlensOcrEngine.kt` (`readingOrder` function) vs `KomaOcrEngine.recognize` (`sorted` regions).
**Fix**: Align `GlensOcrEngine.readingOrder` bands logic with `KomaOcrEngine.recognize` `sorted` regions (using `(it.top * 80).toInt()` vs `(it.top * 40).toInt()` etc.).

## 6. OcrPageSourceResolver virtual path handling
**Location**: `/home/user/yomikai_main/app/src/main/java/eu/kanade/tachiyomi/ui/koma/KomaOcrEngine.kt` (`decode` and `region` functions) and `KomaChapterLoader.kt`.
**Bug**: `KomaPageStore.isVirtual(path)` handling in `decode` and `region` might fail for certain virtual paths (e.g., `koma://...`) if `KomaPageStore.ensure` is not called correctly.
**Fix**: Ensure `KomaPageStore.ensure` is called in `KomaOcrEngine.decode` and `region` before accessing bytes, and handle `koma://` prefix correctly.

## 7. KomaText cleanSpeech/RussianLine logic
**Location**: `/home/user/yomikai_main/app/src/main/java/eu/kanade/tachiyomi/ui/koma/KomaOcrEngine.kt` (`KomaText.cleanSpeech` and `isRussianLine` in `rescued` function).
**Bug**: `cleanSpeech` might strip valid Russian characters or fail on specific punctuation, causing "no text" in `GlensOcrEngine` bridge.
**Fix**: Update `KomaText.cleanSpeech` and `isRussianLine` to handle GLens-specific punctuation and Cyrillic characters correctly.

## 8. KomaReaderSettingsSheet/ReaderActivity transition logic
**Location**: `/home/user/yomikai_main/app/src/main/java/eu/kanade/tachiyomi/ui/koma/KomaReaderScreen.kt` (line 946-957) and `ReaderActivity.kt`.
**Bug**: Transition from KOMA to Legacy Reader (`ReaderActivity`) via `onOpenLegacy` might lose the `skipKoma` flag or `onOpenLegacy` callback, leading to a loop or crash.
**Fix**: Ensure `KomaChapterReader.kt` passes `skipKoma = true` and `onOpenLegacy` correctly, and `ReaderActivity.kt` handles `skipKoma` by finishing itself and returning to KOMA if `fromKoma` is true.

## Assembly & GitHub Tag
- Build the APK using `./gradlew assembleRelease` in `/home/user/yomikai_main`.
- Tag the GitHub release using the token in `/home/user/uploads/_.gh_secret.txt`.

---
**Immediate Next Steps**:
1. Analyze `GlensOcrEngine.kt` request sending vs `KomaOcrEngine.kt` slot/band/millis.
2. Draft fixes for the Lens Adapter and the 5-8 other bugs.
3. Apply fixes to the workspace.
4. Compile/Assemble the APK.
5. Push to GitHub and tag the release.
