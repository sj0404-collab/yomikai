# Статус правок читалки/браузера и что ещё чинить

Файл создан по итогам разбора скриншотов авточтения (бразуер + читалка) и
жалоб пользователя. Обновляется по мере выполнения. Движемся по очереди:
**одна задача → коммит → пуш**, в конце — релиз с тегом.

---

## ЧАСТЬ 1. СДЕЛАНО (уже в master, запушено)

### 1.1. AdBlock во встроенном браузере ✅
**Файлы:**
- `app/src/main/java/eu/kanade/tachiyomi/ui/webbrowser/BrowserAdBlock.kt` — **новый файл**: сетевой список домен/маркеров рекламы (`AD_DOMAINS`, `AD_PATH_MARKERS`), `shouldInterceptRequest` → пустышка, косметический контент-скрипт `CONTENT_SCRIPT` (вырезает промо-карточки, telegram-оверлеи, липкие баннеры, рекламные iframe; MutationObserver + интервал 1.5 с ловят динамически подселяемую рекламу), тумблер `isEnabled/setEnabled`, `inject/setEnabled(view)`.
- `app/src/main/java/eu/kanade/tachiyomi/ui/webbrowser/BrowserTab.kt` — `shouldInterceptRequest` в `webViewClient`, инъекция в `onPageStarted`/`onPageFinished`, строка «AdBlock: вкл/выкл» в FAB-меню (импорт `androidx.compose.material.icons.outlined.Block`).
- `app/src/main/java/eu/kanade/tachiyomi/ui/webbrowser/MiniOverlayService.kt` — тот же перехват + инъекция в мини-плеере.

**Что решает:** промо-карточка «В паках больше выбора» (+звуки «тук-тук») больше не лезет в диалог авточтения и не читается вслух. Расширения Chrome в WebView невозможны — блокировщик нативный.

### 1.2. Тумблер «Наложение на странице» ✅
**Файлы:**
- `domain/src/main/java/mihon/domain/ocr/service/OcrPreferences.kt` — pref `readerOverlayEnabled()` (`pref_reader_overlay_enabled`, по умолчанию **true**).
- `app/src/main/java/eu/kanade/presentation/reader/components/AutoReadHighlight.kt` — ранний выход, если выключено.
- `app/src/main/java/eu/kanade/presentation/reader/components/OcrBubbleVoiceOverlay.kt` — ранний выход.
- `app/src/main/java/eu/kanade/presentation/reader/appbars/ReaderTopBar.kt` — строка тумблера в меню OCR.
- `app/src/main/java/eu/kanade/presentation/reader/OcrBubbleSettingsDialog.kt` — чекбокс «Наложение на странице».

**Что решает:** рамки реплик, номера и значки 🔊 выключаются одним тумблером.

### 1.3. Тумблер «Читать каждую букву» ✅
**Файлы:**
- `OcrPreferences.kt` — pref `autoReadReadEverything()` (`pref_autoread_read_everything`, по умолчанию **true**).
- `app/src/main/java/eu/kanade/tachiyomi/data/tts/AutoReadEngine.kt` (~строка 990) — при включённом тумблере фильтр `isMeaningful` заменён на «строка с ≥1 буквой»; одиночные «а…», «мгм», вздохи читаются. Промо-текст сайта по-прежнему вырезается `cleanOcrGarbage`.
- UI: `ReaderTopBar.kt` (строка «Читать каждую букву»), `OcrBubbleSettingsDialog.kt` (чекбокс).

### 1.4. Переводчик страницы: реальная работа + стрелка «⇄» ✅
**Файлы:**
- `AutoReadEngine.kt` — гейт перевода переписан: было `translate && language != target`
  (эталонный язык из настроек по умолчанию «ru» == цели «ru» → **китайская манхуа молча
  НЕ переводилась**). Стало: при источнике «auto» язык определяется по самому тексту кадра
  (`dominantLanguage()` — кириллица/латиница/кана/хан/хангыль), иначе сравнивается ручная пара.
- `OcrPreferences.kt` — пары уже существовали: `autoReadTranslateSource()` / `translateTarget()`.
- `ReaderTopBar.kt` — «⇄ Обменять языки» (как в переводчике; `auto`-источник при обмене
  заменяется языком реплик кадра), «Перевод перед озвучкой» — тумблер.
- `OcrBubbleSettingsDialog.kt` — подпись пары «источник → цель».

### 1.5. Дубли рамок «лесенки» ✅
**Файл:** `AutoReadEngine.kt` — `dropOverlappingDuplicates()` + `overlapRatio()` (IoU):
одинаковый текст боксами с IoU > 0.25 больше не рисует вторую рамку поверх отмеченной.

---

## ЧАСТЬ 2. ОСТАЛОСЬ СДЕЛАТЬ (по очереди, каждая — свой коммит)

### 2.1. Колоночный порядок чтения (RTL/LTR/вертикалка) ✅
**Файл:** `AutoReadEngine.kt` — `splitIntoColumns()` + `isDenseColumn()` +
`orderWithinColumn()` (порог перекрытия интервалов ~0.3, константы `COLUMN_*`).
`orderRegions` переписан: лента/манхуа с абзацами-колонками читается колонка за
колонкой (RTL — справа налево, LTR — слева направо), классическая манга рядами
баблов осталась рядовой (защита «≥2 реплик в колонке»); `vertical` читается одной
лентой.
**Тесты:** `app/src/test/java/eu/kanade/tachiyomi/data/tts/AutoReadEngineOrderTest.kt`
(5 тестов: RTL ряды, RTL/LTR колонки, вертикаль, одна реплика).

### 2.2. Рамки не стираются при ручном возврате на страницу ✅
**Файлы:** `AutoReadEngine.kt` — `clearVisuals()` (сбрасывает `_frameRegions`,
`_currentRegion`, `frameGeometry`, `spokenLines`, `streamedRegionCount`);
`ReaderActivity.kt` — `LaunchedEffect(state.currentPage)` срабатывает при ПАУЗЕ
цикла и ручном перелистывании + `onReaderScrolledManually()` вручную гонит тот же
сброс; `WebtoonViewer.kt` — хук `onScrolled` зовёт `onReaderScrolledManually()`.
Во время автоцикла сброса нет: `spokenLines` специально переживает страницы.

### 2.3. Узкие/портретные страницы: слова недоговаривает ✅
**Файл:** `AutoReadEngine.kt` — `downscaleForScan`: потолок 2048 для портретных
(`OCR_SCAN_MAX_EDGE_PORTRAIT`), 1600 для ландшафтных; апскейл мелких кадров
(длинная сторона < 1000) до ×1.5 (билинейный, `OCR_UPSCALE_MAX`).

### 2.4. Скриншоты: удаление «насовсем» ✅
**Файлы:** `OcrScreenshotEntry.galleryUri`; `OcrScreenshotBuffer` —
`markGalleryUri()`, `remove(id)`, `removeAll(ids)`, `deleteEverywhere()`
(content:// через MediaStore + удаление внутреннего JPEG); `ScreenshotDetailScreen`
— кнопка удаления.

### 2.5. Скриншоты: мультивыбор + «отправить в архив» ✅
**Файлы:** `ScreenshotTab.kt` — selection-режим (TopAppBar: счётчик, «В архив»,
«Удалить», «Отмена»), тап = toggle с подсветкой, back-выход; «В архив» =
публикация в галерею `Pictures/Yomikai` + move из буфера.

### 2.6. Вебтун-авточтение: плавная скорость ✅
**Файлы:** `WebtoonViewer.scrollByPx`; `ReaderActivity.smoothWebtoonScroll`
(тики 16 мс, ease-out, длительность = доля экрана × 1200 мс / speechRate);
`advanceAutoReadViewer(smooth = phraseMode)` + канал scrollTargets-consumer
использует плавную прокрутку; кадры без текста продолжают мягко листаться.

### 2.7. Эмоциональные голоса ✅
**Файлы:**
- `OcrPreferences.kt` — pref `emotionalVoices()` (`pref_emotional_voices`, по
  умолчанию **true**).
- `AutoReadEngine.kt` — `enum class Emotion` (pitch/rate/glyph: страх 1.3/1.4 —
  выше и быстрее, утешение 0.85/0.82 — ниже и медленнее, гнев, грусть, радость,
  шёпот, удивление) + `guessEmotion(text)` в companion — лексика и пунктуация
  (исключения «не страшно» с границей слова `\b`, чтобы «мне страшно» не ушло в
  комфорт). Реплики `readFrame` и wholePageMode передают `prosody` в
  `speakAndAwait(..., prosody)` при включённом тумблере.
- `TtsSpeaker.kt` — `speakAs(..., emotionProsody)` → системный движок домножает
  `speechRate`/`speechPitch`, Edge — prosody rate/pitch.
- `SettingsHubScreen.kt` — тумблер «Эмоциональные голоса» в `SoundAutoReadTab`.
**Тесты:** `AutoReadEngineEmotionTest.kt` (7 тестов).

---

## ЧАСТЬ 3. РЕЛИЗ И ТЕГИ (после всех правок)

1. Прогнать `./gradlew :app:compileDebugKotlin` и юнит-тесты (все 354 зелёные;
   фильтр рекламы — `/tmp/opencode/adblock.test.js`, 13 проверок).
2. `git tag -a v1.9.x -m "..."` и `git push origin v1.9.x` (история коммитов —
   по одному на задачу, см. `git log --oneline`).
3. Проверить на устройстве сценарии из жалоб:
   - браузер: промо-карточка не читается, звуков нет;
   - читалка: «а…»/«мгм» читаются, манхуа переводится, ⇄ меняет языки;
   - рамки исчезают при возврате на страницу вручную;
   - скриншот удаляется насовсем; выделение → архив;
   - вебтун листается ровно, «режим сказки» на пустых кадрах;
   - реплики-эмоции звучат по-разному (страх/утешение), тумблер в
     Авточтение → «Эмоциональные голоса».
