# Исправления

## v1.9.75 — Критические баги + TTS в читалке книг

### Fix: Критические баги (коммит 2f0ea92)

#### BookParser.kt — Утечки ресурсов
- `parseEpub` / `extractEpubCover`: `ZipFile` обёрнут в `use {}` — устранена утечка файловых дескрипторов
- `extractPdfCover`: `PdfRenderer`, `Page`, `ParcelFileDescriptor` обёрнуты в вложенные `try/finally` — каждый ресурс гарантированно закрывается
- `extractPdfCover` (кэш обложки): `FileOutputStream` обёрнут в `use {}` — поток не зависает при исключении
- `parseEpub` (размер файла): проверка `size < 40MB` теперь стримится через `InputStream.read()` вместо `readBytes()` — страницы с тяжёлыми EPUB больше не вызывают OOM

#### BookChapter.kt — Гонка данных
- `createSection`: исправлен `totalPages = endPage` → добавлен параметр `totalPages` — диапазон страниц теперь корректен

#### CyrillicOcrEngine.kt — Race condition
- `close()`: мьютекс блокируется через `runBlocking { mutex.withLock {} }` вместо немедленного вызова `closeInternal()` — потоки TTS/OCR больше не сталкиваются при закрытии движка
- `closeInternal()`: `OcrTextCleanerStats.reset()` перенесён **внутрь** мьютекса

#### BooksLibraryScreen.kt — OOM + зависание
- **OOM обложки**: `coverBitmap` заменён на `coverPath` + ленивая загрузка через `BitmapFactory.decodeFile()` с `inSampleSize = 4` — память снижена в ~16 раз
- **Зависание импорта**: `importing = false` вынесен в `finally` блок — при ошибке импорта UI не зависает в состоянии загрузки

#### BooksStore.kt — Консистентность данных
- Запись файла: при ошибке `writeText` старый файл восстанавливается из бэкапа
- Кэш обложек: повреждённые файлы удаляются при обнаружении

#### BooksReaderScreen.kt — TTS lifecycle + производительность
- Битмапы страниц `recycle()` перенесён в `LaunchedEffect(page)` (при смене страницы), а не в `DisposableEffect` — исключён краш при перерисовке Compose
- `DisposableEffect(context)` правильно завершает TTS при смене контекста (поворот экрана)
- Regex для разбиения на предложения вынесен в `remember` — аллокации не происходят каждый рекомпоз
- `LazyColumn` получил `key = { it.hashCode() }` — стабильная навигация

---

### Feature: Полноценный TTS в читалке книг (коммит cb154e1)

#### BooksReaderScreen.kt
- **Два TTS-движка**: Системный (оффлайн, `TextToSpeech`) и Edge TTS (онлайн, `EdgeTts.synthesizeToFile()` + `MediaPlayer`)
- **Выбор движка**: `FilterChip` для быстрого переключения System / Edge в панели настроек
- **TtsVoicePickerDialog**: полный список голосов с секциями «🌐 Edge TTS» и «📱 Системные»
- **Кнопка Stop** (⏹): сброс позиции на начало главы, между Prev/Next и Play/Pause
- **Кнопка выбора голоса**: отображает текущее имя голоса, клик открывает диалог
- **Edge TTS цикл**: `synthesizeToFile()` → `MediaPlayer` → `OnCompletionListener` → следующее предложение
- Настройки (движок, голос, скорость, питч) сохраняются и восстанавливаются из `OcrPreferences`

#### OcrPreferences.kt — Новые ключи
- `bookTtsEngine()` — `"system"` / `"edge"`
- `bookVoiceSpec()` — спецификация голоса (пакет::имя / shortName)
- `bookVoiceLabel()` — отображаемое имя для UI

---

## Известные проблемы (не исправлены)

### OCR: Мелкий текст / водяные знаки читаются вслух
- `minComponentArea = 16` на карте 736×736 ≈ 42px оригинала — слишком мало
- Фильтр кропа `4×4 px` — пропускает водяные знаки
- `minAcceptConfidence = 0.25` / `shortTextMinConfidence = 0.12` — слишком низкие пороги
- `createHighContrast()` усиливает контраст полупрозрачных водяных знаков

### OCR: Латиница вместо кириллицы («шум» → «вим»)
- Модель PP-OCR может выдавать латинские символы вместо кириллических
- `allowed()` блокирует латиницу в CTC-декодере, но `fixLookalikesPerWord()` не конвертирует чистую латиницу в кириллицу
- `CyrillicTranslitFixer.autoFixCyrillic()` может вернуть латиницу как есть при определённых условиях

### OCR: Пропуск текста вверху экрана
- Крупный заголовочный текст часто не проходит порог уверенности
- Salvage-механизм спасает отклонённые строки, но не проверяет размер бокса
