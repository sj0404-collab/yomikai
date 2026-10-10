# Статус правок читалки/браузера и что ещё чинить

Файл создан по итогам разбора скриншотов авточтения (бразуер + читалка) и
жалоб пользователя. Обновляется по мере выполнения. Движемся по очереди:
**одна задача → коммит → пуш**, в конце — релиз с тегом и эмоциональные голоса.

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

### 2.1. Колоночный порядок чтения (RTL/LTR/вертикалка) — В РАБОТЕ
**Где:** `app/src/main/java/eu/kanade/tachiyomi/data/tts/AutoReadEngine.kt`, функция
`orderRegions` (в companion, ~строка 2963) + `groupIntoBands` (~строка 3011+).
**Суть жалобы:** страницу читает «лесенкой/зигзагом»: вверху слева-направо, внизу
справа-налево; при колонках разной ширины (одна шире, вторая уже, третья в полэкрана)
текст прыгает между колонками вместо того, чтобы дочитать колонку до конца.
**Как чинить:**
1. Добавить в companion функцию `splitIntoColumns(lines)`: сортируем по X, кластеризуем
   в колонки по перекрытию интервалов (пересечение / min(ширина) ≥ 0.3; центр в пределах
   одной колонки). Колонка = устойчивый X-интервал.
2. В `orderRegions` для `order != "vertical"`:
   - если колонок ≥ 2 И в каждой колонке ≥ 2 реплики → **column-major**: колонки
     упорядочить по направлению (RTL — справа налево, LTR — слева направо), внутри
     колонки — сверху вниз (`groupIntoBands` + `ReadingOrderSorter.sort`);
   - иначе — текущая полосовая логика (манга: ряды баблов).
3. Для `"vertical"` колонок не строим: вебтун читается сверху вниз одной лентой
   (`ReadingOrderSorter.sort` уже так делает).
4. Осторожно: классическая манга (ряды баблов) не должна уехать в column-major —
   порог «в каждой колонке ≥2 реплики» её защищает (в ряду баблы в разных X не образуют
   высоких колонок).

### 2.2. Рамки не стираются при ручном возврате на страницу
**Где:** `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt` (обработчик
смены страницы `onPageChanged`/`onTap`/navigate); `AutoReadEngine.kt`.
**Как чинить:**
- в `AutoReadEngine` добавить `fun clearVisuals()`: `_frameRegions.value = emptyList()`,
  `_currentRegion.value = null`, `frameGeometry = null`, `spokenLines.clear()`,
  `streamedRegionCount.set(0)`;
- вызывать из `ReaderActivity` при РУЧНОЙ смене страницы (листание пальцем/стрелками),
  когда автоцикл авточтения выключен;
- **не** вызывать во время автоцикла: `spokenLines` специально переживает страницы,
  чтобы не перечитывать хвост вебтуна.

### 2.3. Узкие/портретные страницы: слова недоговаривает, не все слова сканирует
**Где:** `AutoReadEngine.kt`, `downscaleForScan` (~строка 2584) и константа
`OCR_SCAN_MAX_EDGE = 1600` (~строка 2542); путь `readFrame` (downscale на стр. ~753).
**Суть:** широкий кадр читается идеально даже мелкий текст, узкий — режет слова.
**Как чинить:**
- в `downscaleForScan` поднять потолок до 2048 для портретных (height > width) и оставить
  1600 для ландшафтных (батарея/время);
- для очень узких источников (длинная сторона < 1600) делать мягкий апскейл ×1.5 перед
  скеаном: детектор и рекогнайзер справляются заметно лучше на мелком кегле;
- замерить `frameMs` (уже пишется `pref.autoReadLastMs`) до/после — если просадка
  скорости > 30%, откатить апскейл для быстрых движков (FAST).

### 2.4. Скриншоты: удаление «насовсем» + кнопка удаления записи
**Где:**
- `data/src/main/java/mihon/data/ocr/OcrScreenshotBuffer.kt` — нет `remove(id)`;
  `clear()/clearChapter()` удаляют только внутренние JPEG (`deleteImageFile`), копии в
  галерее `Pictures/Yomikai` остаются. Нужно: запомнить `galleryUri` в записи при
  публикации и удалять строку MediaStore вместе с файлом.
- `data/src/main/java/mihon/data/ocr/OcrScreenshotEntry.kt` — добавить поле `galleryUri: String? = null`.
- `app/src/main/java/eu/kanade/tachiyomi/ui/screenshots/ScreenshotTab.kt` — добавить
  per-entry кнопку удаления (иконка мусорного бака) и в диалоге «Очистить» предупреждать,
  что теперь удаляется и из галереи.
- `app/src/main/java/eu/kanade/tachiyomi/ui/screenshots/ScreenshotDetailScreen.kt` — кнопка
  удаления на детальном экране.
**Как чинить:**
1. `publishToGallery/publishEntryToGallery` → сохранять `Uri` в запись (`markPublished`),
   не забыть `persist()`.
2. `fun remove(id: Long)` / `fun removeAll(ids: Set<Long>)`: удалить внутренний файл +
   `contentResolver.delete(galleryUri)` + выкинуть запись, `version++`, persist.
3. UI-кнопки дергать это в `rememberCoroutineScope().launch(Dispatchers.IO)`.

### 2.5. Скриншоты: мультивыбор + «отправить в архив»
**Где:** `ScreenshotTab.kt` (сейчас у таба НЕТ ScreenModel — состояние локальное).
**Как чинить:**
- локальный `var selected by remember { mutableStateOf(setOf<Long>()) }`,
  `selectionMode = selected.isNotEmpty()`;
- `TopAppBar` в режиме выделения: счётчик, «В архив», «Удалить», «Отмена»
  (как `LibrarySelectionToolbar`, паттерн — `presentation/library/components/LibraryToolbar.kt`);
- тап по карточке в selection-режиме = toggle; `Modifier.selectedBackground` для подсветки;
- «В архив» = `publishEntryToGallery` для каждого выбранного (галерея
  `Pictures/Yomikai`) + записать `galleryUri` в запись (или удалить запись из буфера —
  по смыслу «отправить в архив» — тогда это move: опубликовать + `remove(ids)`);
- back-handler выходит из режима выделения.

### 2.6. Вебтун-авточтение: плавная скорость, без рывков, «режим рассказчика»
**Где:**
- `ReaderActivity.kt` ~2259–2313 (`scrollTargets`, CONFLATED-канал, `phraseMode`)
  и ~1925–1939 (`startAutoReadLoop` включает штатную автопрокрутку);
- `WebtoonViewer.kt` `scrollDownByFraction` (мягкий scroll при `usePageTransitions`);
- `AutoReadEngine.kt` `pauseAfterLineMs` (120..800 мс).
**Суть:** скорость «не прибавляется», падение рывком, кадры без текста стоят.
**Как чинить:**
- В consumer-корутине канала прокручивать НЕ одним `smoothScrollBy` на реплику, а
  дробить: `dy / N` шагов по 16 мс (N = 10..20), со торможением на стыке (уже есть
  коэффициент `remain` в цикле прокрутки — переиспользовать);
- длину прокрутки оценивать из ДЛИНЫ реплики (`ttsTimeoutMs`/`pauseAfterLineMs` уже
  значат речь): длинная реплика → больше времени на прокрутку → скорость ровная;
- кадры без текста: не стопорить цикл — продолжать мягкую прокрутку с той же скоростью
  (режим «сказки»): `while (readEngine.lastFrameHadText == false)` продолжать шаги;
- скорость из `speechRate` уже приходит — домножить, чтобы «+» реально ускоряла ленту.

### 2.7. Эмоциональные голоса (после всех тестов, к релизу)
**Где:**
- `app/src/main/java/eu/kanade/tachiyomi/data/tts/` — `TtsSpeaker`, `AutoReadEngine.speakAndAwait`;
- `SpeechMarkup` (разметка реплик), `mihon/domain/.../VoicePreset`;
- UI выбора: `SettingsHubScreen.kt` → `SoundAutoReadTab` (~строка 623).
**Задача:** голоса не «механически зачитывают», а вживаются в роль и передают эмоции
(страх, утешение, злость, шёпот). Минимум:
1. Детектор эмоции по тексту реплики (лексика + пунктуация: «!!», «...», «?!», слова
   «боюсь», «прости», «нет!») — функция `guessEmotion(text)` в companion `AutoReadEngine`
   рядом с `dominantLanguage`.
2. Проброс эмоции в TTS: для системного движка — `speechPitch`/`speechRate` под эмоцию
   (страх: pitch↑ rate↑; утешение: pitch↓ rate↓; шёпот: rate↓); для Edge TTS — стиль
   через SSML (`mstts:express-as`), см. `bookTtsEngine`/`edgeVoice` prefs.
3. Метка эмоции в `SpokenRegion.marks` для оверлея (если наложение включено).
4. Переключатель «Эмоциональные голоса» в `SoundAutoReadTab`.

---

## ЧАСТЬ 3. РЕЛИЗ И ТЕГИ (после всех правок)

1. Прогнать `./gradlew :app:compileDebugKotlin` и юнит-тесты (фильтр рекламы —
   `/tmp/opencode/adblock.test.js`, 13 проверок; порядок чтения — добавить тест
   `AutoReadEngineTest` на колонки в `app/src/test/`).
2. `git tag -a v1.9.x -m "..."` и `git push origin v1.9.x` (история коммитов —
   по одному на задачу, см. `git log --oneline`).
3. Проверить на устройстве сценарии из жалоб:
   - браузер: промо-карточка не читается, звуков нет;
   - читалка: «а…»/«мгм» читаются, манхуа переводится, ⇄ меняет языки;
   - рамки исчезают при возврате на страницу вручную;
   - скриншот удаляется насовсем; выделение → архив;
   - вебтун листается ровно, «режим сказки» на пустых кадрах.
