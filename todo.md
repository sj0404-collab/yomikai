# Local OCR repair

- [x] Trace the local OCR engine, language configuration, image preprocessing, and result post-processing.
- [x] Reproduce the supplied Russian comic-panel failures: merged words, Latin/Cyrillic substitutions, UI-text capture, and false gibberish.
- [ ] Add a safe Russian-oriented comic-text preprocessing and recognition path without weakening other configured languages.
- [ ] Add regression coverage for representative Russian text-panel crops and reject low-confidence garbage.
- [ ] Build, test, and manually validate OCR results on the supplied screenshots.
- [x] Configure and trigger the Android APK build only through the repository's GitHub Actions runner; do not assemble APK locally.
- [ ] Commit and push the verified local OCR fix directly to `sj0404-collab/yomihon-custom` on `main`.
- [ ] Download the verified GitHub Actions release artifact and upload the APK to GoFile with the user's explicit authorization.

## Caption OCR correction after device validation

- [ ] Reproduce and fix dropped initials and vowel substitutions: `ОН БЫЛ ЛОЖНО ОБВИНЁН В СГОВОРЕ С ДЕМОНОМ` must not become `н был лжн вбинен в сговоре с демоном`.
- [ ] Reproduce and fix word-boundary and Cyrillic substitutions: `«ОХОТНИЧИЙ ПЁС» ДОМА БАСКЕРВИЛЕЙ.` must not become `ххтничий лес и дма фаскервилаей`.
- [ ] Preserve explicit spaces in `ВИКИР ВАН БАСКЕРВИЛЬ.` instead of fusing the name.
- [ ] Build the corrected APK exclusively through GitHub Actions and validate it against these supplied panels before a new external upload.

## Device validation after build 9d19650

- [ ] Reproduce and correct dropped or absent caption results, including the white caption `ПО СЛОВАМ «ОХОТНИЧЬЕГО ПСА», КОТОРЫЙ ПОСВЯТИЛ СЕБЯ ОТЦУ И СЕМЬЕ,` that currently returns no result.
- [ ] Correct erroneous hyphenation that splits intact words across OCR lines, for example `МНЕ ХО-РОШО`, `НЕУПРАВ-ЛЯЕМЫЙ`, `БЕС-ПОЛЕЗНЫЙ`, and `БЕР-ДИУМА`.
- [ ] Correct missed letters and word-boundary errors in clean captions, including `МНЕ ХОРОШО ЗНАКОМО ЭТО ИМЯ.`, `«ОХОТНИЧИЙ ПЁС» ДОМА БАСКЕРВИЛЕЙ.`, and `И СХОЖУ ТАМ С УМА.`
- [ ] Improve coverage for outlined Cyrillic dialogue and narration that currently returns no OCR result.
- [ ] Add targeted regression tests for the reported hyphen, missing-result, and word-boundary cases without fabricating text from non-Russian artwork.

## Release documentation

- [x] Create a versioned Markdown release report for every future GitHub Actions APK candidate, documenting source commit, build link, improvements, known limitations, test evidence, and unresolved device-validation examples.
- [x] Validate candidate `9a8e4f8` remotely in GitHub Actions run `33007959697`: focused Cyrillic OCR tests, complete unit tests, signed arm64 APK assembly, and quality-report artifact all succeeded.
- [x] Upload candidate `9a8e4f8` from GitHub Actions run `33007959697` to GoFile for device testing: `https://gofile.io/d/s7HstrXp`.
- [ ] Install and device-test candidate `9a8e4f8` against the reported false line-wrap hyphens and missing-result captions before treating the fix as accepted.

## Standalone local OCR overlay APK

- [ ] Inspect the requested Overlay Translator repository and confirm a compatible Android source baseline.
- [ ] Create a separate Android overlay APK instead of modifying the reader UI: the user explicitly launches it over another app.
- [ ] Request Android screen-capture and draw-over-other-apps permissions only after an explicit user action, and explain their purpose in-app.
- [ ] Let the user place and resize one capture frame over the actual page content; crop to that frame before local OCR so status bars, overlay controls, and content outside the frame are excluded.
- [ ] Run local Russian/Cyrillic OCR on the selected screen crop, render editable text in the overlay, and add optional Russian text-to-speech controls.
- [ ] Add a Markdown quality report for every overlay APK candidate and build the APK only through GitHub Actions.

## New quality gate: Russian text fidelity

- [ ] Enforce UTF-8 end-to-end and add a regression that rejects mojibake and non-Cyrillic lookalike output when the source text is Russian.
- [ ] Prevent hallucinated pseudo-words such as `разiiiнение`, `сахар-самаар`, and `мама-нама`; retain the raw OCR or return a clearly low-confidence/no-result state instead of inventing a correction.
- [ ] Preserve whole detected sentences and large speech bubbles instead of returning a partial word result or `Нет результатов` when usable text exists.
- [ ] Preserve short valid utterances such as `а`, `а-а-а`, `а!`, and `а...`; do not reject them solely because they are short.
- [ ] Keep hyphens only for real orthographic hyphens or visual line-wraps that can be safely joined; never introduce a hyphen between recognized Cyrillic words.
- [x] Decide explicitly whether preprocessing plugins are safe: rejected. Preprocessing stays local, deterministic and UTF-8 aware (high-contrast retry inside `recognizeCrop`), and no third-party preprocessing plugin is enabled; a candidate that lowers ranking in `candidateQuality` simply loses to the unprocessed crop.
- [ ] Add a release report with positive and negative device examples before uploading the next APK candidate.

## Final one-build gate

- [x] Cancel the in-progress intermediate GitHub Actions run before any further APK build: `Tests` (build.yml) no longer assembles an APK at all, so intermediate builds cannot compete with the single release build.
- [x] Complete all requested OCR logic, safety filters, full-bubble rescue, short-utterance handling, hyphen handling, and plugin decision before triggering a release workflow.
- [ ] Finish the complete regression suite and inspect its results before the final build; no APK is to be built from an unverified commit.
- [ ] Trigger exactly one final GitHub Actions APK build after all tests and the release Markdown report are complete.
- [ ] Upload only that final APK to GoFile and clearly report its single final commit, run, SHA-256, positive results, and remaining limitations.

## CTC coverage and line salvage

- [x] Count blank steps inside the recognized span and expose them as `coverage` so dropped initials and vowels stop passing as a good result.
- [x] Normalize the blank class probability with softmax so confidence thresholds mean what they claim; keep the raw mean as a scale-invariant score.
- [x] Salvage a caption line token by token instead of erasing the whole line when one garbage token appears.
- [x] Never partially salvage a line that still contains a mixed-script token; return it unchanged.
- [x] Return a line without any Cyrillic letter unchanged so Latin signs and sound effects are not lost.
- [x] Add `CtcScoringTest` and `filterGarbageTokens` regressions next to the existing `OcrTextCleanerTest`.

## Single release build gate

- [x] Stop `build.yml` from assembling APKs: it is a test gate for pull requests and manual dispatch only.
- [x] Make `release.yml` the only place that produces an APK, and run the full regression suite there before `assembleRelease`.
- [x] Check out with `fetch-depth: 0` so `getLatestCommitCount`/`getLatestCommitSha`/commit-time build stamps are real and the tag version is baked into the APK.
- [x] Fail the build unless exactly one release APK exists, its `versionName` matches the tag, `versionCode` is not below the 10907 floor and arm64-v8a native code is present.
- [x] Publish SHA-256 and size in the release body and in the rendered quality report; the release is no longer a draft.

## Модульные плагины OCR/голосов и пресеты областей

- [x] Описать OCR-движки декларативно (`OcrPlugins`): id, модель, требования (сеть, пак моделей, LiteRT, ключ, адрес сервера), порядок во fallback-цепочке, поддержка областей. Реестр не создаёт движки и не трогает мьютексы `OcrEngineLocks`, поэтому кэш моделей и блокировки остались прежними.
- [x] Перенести семантику пресетов `pref_fallback_preset` (auto/online/offline/single) из зашитых списков `OcrRepositoryImpl` в данные реестра и покрыть их тестами.
- [x] Зафиксировать миграционные значения enum (`LEGACY`, `FAST`, `TESSERACT`) как алиасы локального кириллического плагина.
- [x] Описать голосовые движки декларативно (`VoicePlugins`): системный TTS, Google Web, ElevenLabs, ONNX (sherpa-onnx/Piper) с требованиями и признаком доступности; голоса ONNX берутся из реального `OnnxTts.CATALOG` и помечаются по факту установки модели.
- [x] Вынести числовые параметры детектора и признания результата из констант `CyrillicOcrEngine` в `OcrTuning` и добавить пресеты типа контента: манга, манхва/вебтун, комикс, сбалансированный.
- [x] Гарантировать, что пресет `BALANCED` побайтово повторяет прежние константы (отдельный тест), поэтому без явного выбора пресета поведение приложения не меняется.
- [x] Добавить точные переопределения пресета (`OcrTuningOverrides`) с клампингом диапазонов: сохранённая старой версией настройка не может сломать распознавание.
- [x] Подключить профиль к движку через провайдер `() -> OcrTuning`, чтобы смена пресета применялась без пересоздания движка и без повторной загрузки моделей.
- [x] Экран настроек с деревом разделов: `SettingsOcrScreen` (пресет типа контента, область, порядок чтения, точная подстройка, движки и цепочка фолбэков) и `SettingsVoicePluginsScreen` (реестр голосовых движков + выбор движка и голоса), вложенный `SettingsOcrPluginsScreen` с требованиями и доступностью каждого OCR-плагина. Все три экрана зарегистрированы в `SettingsMainScreen` и в поиске по настройкам, добавлено 52 ключа в базовый `strings.xml`.
- [ ] Пресеты областей в UI читалки: быстрый выбор манга/манхва/комикс и отображение зон страницы.
- [x] Подключение AI-чата к модульной системе: реестр бэкендов `AiBackends` (online / local / runner) с единой проверкой готовности, раздел настроек «AI-ассистент» и переход на вкладку AI из настроек. Агент получил инструменты `reader_status`, `ocr_preset` и `plugins_list` — они читают те же реестры и настройки, что и экраны, поэтому ответ агента не расходится с настройками пользователя. Правила области и точной подстройки вынесены в `OcrRegionRules` (один источник истины для движка, настроек и агента), проверка сети — в `isNetworkAvailable` (core/common). Имена инструментов защищены от перехвата самодельными плагинами: `AiPlugins.RESERVED_TOOL_NAMES`.

## Unified Yomihon APK: OCR and floating voice controls

- [x] Port only the working local OCR changes from the overlay branch into `yomihon-custom` without copying its standalone APK shell or cloud paths.
- [x] Add Yomihon-native floating `Голос` and `Выбрать голос` controls outside the OCR result card, with the existing copy and close actions preserved.
- [x] Connect the voice picker to installed Russian system TTS voices and persist the selected voice locally through the existing `TtsSettingsDialog` and `OcrPreferences.voiceName()` path.
- [x] Keep UTF-8/Cyrillic fidelity, full-bubble rescue, short utterances, safe line-wrap joining, and no pseudo-word hallucination as one shared quality gate.
- [x] Run the complete regression suite before triggering exactly one signed release APK build in GitHub Actions: `release.yml` now runs migrations, the focused Cyrillic OCR tests, the CTC scoring tests and the full unit-test suite before `assembleRelease`, so an unverified commit cannot produce an APK. Local sandbox compilation stays blocked because Android SDK is unavailable; the GitHub runner remains the authoritative build/test environment.
- [ ] Upload only the verified Yomihon release APK and its Markdown quality report to GoFile.

## v1.9.91: единый бейдж, OCR всей области, мгновенные скрины, полный кеш главы

- [x] Единый бейдж 🔊 при озвучке реплики: `OcrBubbleVoiceOverlay` рисует ровно один значок у текущего бабла (`isSpeaking = readingActive` в `ReaderActivity`); вне озвучки остаётся только перетаскиваемый значок.
- [x] Все OCR-модели распознают всю видимую область целиком: `AutoReadEngine.readFrame` для полностраничных (AI) движков больше не режет кадр на баблы и не распознаёт кропы по отдельности — результат всей области сразу делится на реплики; YOLO-баллоны остались запасным путём для пустого результата.
- [x] Фоновый скрин в минимально читаемом разрешении: `downscaleForScan` (длинная сторона 1600px) в `readFrame` и `captureInstantScreenshot` — «часики» сканирования быстрые, текст не теряется (детектор и так жмёт до 736px).
- [x] Все слова распознаются: лимит баблов на кадр поднят с 14 до 40, основной путь возвращает все слова видимой области.
- [x] Читалка открывает и кеширует ВСЕ страницы: `HttpPageLoader` префетчит оставшиеся страницы всей главы (не только 4), текущая страница приоритетнее фоновых; `ChapterCache` поднят с 100 МБ до 1 ГБ.
- [x] Автоочистка кеша после прочтения: `ReaderActivity.pruneReadPagesFromCache` + `ChapterCache.removeImageFromCache` — прочитанные позади страницы освобождаются (кроме одной ближайшей).
- [x] Локальная проверка: `:app:compileDebugKotlin`, `:app:testDebugUnitTest` (вкл. `AutoReadEngineCleanTest`), `:data:testDebugUnitTest`, `verifySqlDelightMigration` — все зелёные.
- [x] Отчёт качества `docs/ocr-releases/2026-09-24-v1.9.91.md` и `CURRENT.md`.
- [x] Push на `main` и тег `v1.9.91` → единственная подписанная сборка в GitHub Actions `release.yml`; SHA-256 APK подтверждён, релиз опубликован.
- [ ] (по явному запросу) залить `yomikai-v1.9.91.apk` и отчёт на GoFile для проверки на устройстве.

## v1.9.92: скриншоты с картинкой, добор текста при онлайн-OCR, быстрый скан

- [x] Настоящие скриншоты: `OcrScreenshotBuffer`/`OcrScreenshotEntry` сохраняют JPEG-кадр (`filesDir/screenshots_images/<id>.jpg`); файлы удаляются при вытеснении, очистке и очистке главы; `diskSizeBytes()` учитывает картинки.
- [x] Кто реально распознал: `engineUsed` в записи берётся из `result.ocrModel` (включая сработавший фолбэк), а не из настройки.
- [x] Добор текста при онлайн-OCR: если онлайн-движок вернул <3 осмысленных строк (ватермарка вместо реплики), кадр дочитывается локально (YOLO + Cyrillic OCR) и результат доклеивается; дубли режутся историей.
- [x] Таймаут кадра снижен 45→25 с; `ReaderViewModel.autoScanAndSpeak` получил тот же таймаут (раньше был бесконечный) — «висение» онлайн-движка больше не блокирует авточтение.
- [x] UI: миниатюры JPEG в карточках вкладки «Скриншоты»; на детальном экране реальный кадр с оверлеем регионов (coil3), сетка-заглушка для старых записей.
- [x] Локальная проверка: `:app:compileDebugKotlin`, `:app:testDebugUnitTest` (вкл. `AutoReadEngineCleanTest`, `OcrRegionTapTest`), `:data:testDebugUnitTest`, `verifySqlDelightMigration` — все зелёные.
- [x] Отчёт качества `docs/ocr-releases/2026-09-24-v1.9.92.md` и `CURRENT.md`.
- [x] Push на `main` и тег `v1.9.92` → единственная подписанная сборка в GitHub Actions `release.yml`; SHA-256 APK подтверждён, релиз опубликован.
- [ ] (по явному запросу) залить `yomikai-v1.9.92.apk` и отчёт на GoFile для проверки на устройстве.

## v1.9.93: быстрее локальный и онлайн OCR, ML Kit перестаёт выдавать «крякозябры»

- [x] Локальный OCR быстрее: тайлы детектора 2×2 запускаются только когда основной проход нашёл меньше `tilingMinTextBoxes` боксов (пресеты: BALANCED 8, MANGA 6, MANHWA 12, MANHUA 8, COMIC 10) — вместо 5 проходов на кадр.
- [x] Локальный OCR быстрее: верификатор PP-OCRv5 пропускается (`verifierSkipConfidence` 0.82) при уверенной v3 И чистой кириллице после правки омоглифов — устройство-защита от «уверенного мусора» сохраняется через `isAcceptableCyrillicOcrText`.
- [x] ML Kit: строки чистятся конвейером кириллического движка (`OcrTextCleaner.cleanMlKitLine`) — правка омоглифов, отсев mojibake/диакритики и мусорных токенов, чистые кириллица/ASCII сохраняются; движок остаётся в фолбэке.
- [x] Онлайн OCR быстрее: GLens 1500→1200 px/JPEG 78, ZenFree 2048→1080/78, Google AI и OpenRouter 1500→1080/78; OwOCR (self-hosted) не трогали.
- [x] Новые тесты: `cleanMlKitLine` ×6 и `tile gating follows content density` в `OcrTextCleanerTest`/`OcrPluginsTest`.
- [x] Локальная проверка: `:data:compileDebugKotlin`, `:data:testDebugUnitTest` (`--rerun-tasks`), `:app:compileDebugKotlin`, `:app:testDebugUnitTest`, `verifySqlDelightMigration` — все зелёные.
- [x] Отчёт качества `docs/ocr-releases/2026-09-24-v1.9.93.md` и `CURRENT.md`.
- [x] Push на `main` (`97d62e3`) и тег `v1.9.93` → единственная подписанная сборка в GitHub Actions `release.yml` (run 36004992680); SHA-256 `6eb75b511715b023df5c04f8ae59153894d47fbeccbd88ec8c6ea488b2f39775`, размер 54027792 байт, релиз опубликован.
- [ ] (по явному запросу) залить `yomikai-v1.9.93.apk` и отчёт на GoFile для проверки на устройстве.

## Книжная AI-сессия: веб- и ручное обучение, выбор моделей

- [ ] При первом запуске AI или авточтения в книге автоматически создавать и открывать отдельную книжную сессию AI-чата, связанную с `bookId`; сессия должна возобновляться при следующем открытии книги и не смешиваться с глобальным чатом.
- [ ] Дать книжной сессии два источника знаний: самостоятельный поиск/чтение сайта через разрешённый web-инструмент и ручную отправку пользователем URL или текста с инструкцией.
- [ ] После чтения источника сохранять в `BookKnowledge` дату, источник и извлечённые правила: порядок чтения, рамки и области страницы, баблы/облачка, расшифровку текста, роли персонажей и подходящие голоса.
- [ ] Применять сохранённые правила к последующим OCR-кадрам, авточтению, переводу и озвучке; показывать пользователю, какие правила применены.
- [ ] Разделить и сохранять выбор моделей для OCR, AI-чата и оркестратора; показывать фактически выбранную модель для каждой операции и запрещать молчаливую подмену модели.
- [ ] В режиме простого общения или вспомогательного ассистента в чате позволять выбрать любую модель оркестратора независимо от модели OCR и авточтения.
- [ ] Добавить тесты на создание/восстановление книжной сессии, импорт веб- и ручного знания, применение правил к авточтению и маршрутизацию моделей.
- [ ] Санитизировать внешние страницы и ручной текст перед сохранением, чтобы они не могли подменять системный промпт или инструкции безопасности.
- [ ] Собирать и тестировать изменения только через GitHub Actions.

## Состояние на 2026-09-25: читалка, модели, оверлей (v1.9.99 — v1.9.102)

### Реализовано и проверено в CI

- [x] Крэш при открытии главы: `resolveSelectionCaptures` бросал
      `IllegalStateException` из корутины, фоновый авто-поиск издания обёрнут в
      `runCatching`. Подтверждено по логу устройства (Itel, Android 13).
- [x] Остановка авточтения: ожидание запуска фразы сокращено с полного таймаута
      (мин. 8 с) до 1,2 с; `started` стал `MutableStateFlow` (была гонка).
- [x] Разбор ремарок `SpeechCue`: `(*шёпотом*)` больше не даёт куски «(» и «)»,
      `*Ааа!*` и `[Ааа!]` озвучиваются, `*Привет* мир` не теряет слово.
- [x] Регулярки и ICU-движок Android: неэкранированная `}` в классах символов
      роняла приложение в `<clinit>` на Android 13+ (три места, аудит пуст).
- [x] Модели Zen: эндпоинт по семейству (`/chat/completions`, `/responses`,
      `/messages`, путь Gemini), разбор всех форм ответа, актуальный список
      бесплатных моделей вместо устаревших.
- [x] «Размышляют null»: размышления читаются у всех семейств (Claude — блок
      `thinking`, Responses — отдельный `output`, Gemini — флаг `thought`),
      литерал `"null"` отсекается. Подтверждено скрином с устройства.
- [x] 403 Zen отбрасывает модель, а не весь чат: ротация доходит до рабочей
      `space-bunny-free`. Именно это открыло чат в v1.9.100.
- [x] Отчёт о возможностях не обещает лишнего: доступность Zen выводится из
      фактического ответа, а не из наличия сети.
- [x] Читалка: кнопка «Стоп» в чате (гасит ход агента, озвучку и авточтение),
      очередь сообщений при занятом агенте, очистка поля после отправки.
- [x] Инструменты агента: `turn_page` (next/prev/first/last) и `auto_read`
      (on/off/status) — раньше агент физически не мог листать и просил скриншоты.
- [x] Мусор `@tool` в «Размышлениях» (незакрытый `@tool page_text {`) больше не
      показывается читателю.
- [x] Расход токенов: `web_search` ограничен тремя вызовами на ход (на скрине
      было 8 вызовов и 33 775 токенов на фразу «продолжить постранично»).
- [x] Ядро моста озвучки: `TtsPluginBridge` решает, что уходит в плагин, а что
      остаётся на голосе; состояния «выключен / скачан / включён» и перебор
      вариантов по кругу. Разметка звуков отличает звук от междометия.
- [x] Все сборки — только через GitHub Actions (правило 8 `MANIFEST.md`).

### Не сделано, осознанно

- [ ] Плагин озвучки не воспроизводит звук: есть только решение моста и его
      тесты. Сами звуки требуют либо файлов от владельца, либо работающего
      провайдера — выдумывать их и URL нельзя.
- [ ] Модели не проверялись вживую: нет устройства и ключей читателя. Проверка
      на устройстве — за пользователем.
- [ ] Glens не продублирован в списке моделей ИИ: это protobuf-движок
      распознавания, он не умеет отвечать на вопросы о картинке, поэтому как
      чат-модель не заработает.

### В работе: оверлей читалки

Порядок обязательный — сначала новый оверлей, потом удаление старого, иначе
читатель останется без оверлея вообще.

- [ ] Захват экрана через `MediaProjection` в оверлее (в `OcrOverlayService`
      захвата нет вообще: 925 строк, только текст из буфера/ручного ввода/STT).
- [ ] Показ страницы книги в рамке оверлея + листание.
- [ ] ИИ-чат внутри оверлея с видением текущей страницы: агент сейчас умеет
      видеть страницу только из `ReaderActivity`; сервису нужен свой источник
      картинки.
- [ ] Вкладка лаунчера со списком приложений телефона; тап по приложению —
      окно захвата экрана и оверлей.
- [ ] Удалить старые оверлеи: `OcrOverlayService` и отдельное приложение
      `overlay-reader` (2886 строк, свой `settings.gradle`, вне сборки).

### В работе: баги и фичи из скринов

- [ ] Файлы, созданные агентом, нельзя открыть — нужен вывод/экспорт на
      устройство.
- [ ] Авточтение зависает на нечитаемом тексте; там, где текста нет, должна
      озвучиваться сцена голосом.
- [ ] Аудио по настроению сцены (мрачный → позитивный → меланхолия → ужасы) с
      пресетами и возможностью создать свой — продолжение работы над плагином
      озвучки.
