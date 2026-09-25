# Текущая сессия агента

- Дата: 2026-09-25
- Репозиторий: `/home/runner/hub-work/yomikai/code`
- Ветка: `main`
- Последний подтверждённый коммит: `567b111 feat(ai): per-book AI session, learned rules and split model roles`

## Контекст

Пользователь поручил:
- хранить историю и состояние текущей сессии агента в репозитории;
- сохранять diff локального клона;
- автоматически создавать коммит и отправлять исправления;
- запускать все сборки через GitHub Actions (локальные сборки запрещены);
- обновлять `AGENT_SESSION.md` при каждом открытии или начале работы;
- при первом AI/авточтении книги создавать отдельную книжную сессию, обучать её
  через сайт или ручной ввод и применять правила областей, чтения и голосов;
- проверять и показывать фактические модели OCR, чата и оркестратора, разрешая
  выбрать оркестратор независимо.

## Выполнено в этой сессии

### Знания и правила книги

- `BookLearning.kt` (новый): виды правил (`reading_order`, `region`, `bubble`,
  `transcription`, `voice`, `advice`, `fact`, `site`, `summary`, `note`), русские
  и английские псевдонимы, `sanitize()` (вырезает `@tool …{`, XML-подобные
  `tool_call`, управляющие символы, режет длину), разбор списков «- …» / «1. …»,
  `readingOrderOf()`, проверка `isHttpUrl()` (только http/https) и
  `normalizeSource()`.
- `BookKnowledge.kt`: добавлены `sessionId`/`sessionStartedAt`, `rules`,
  `sources`; `ensureSession()` создаёт сессию книги один раз; `learn()` пишет
  правила с источником и датой, дублирует `site`/`summary`/`readingOrder` в
  старые поля; `addSource()` пишет provenance без текста страницы;
  `renderRules()` отдаёт правила как ДАННЫЕ; `renderAdvice()`, `rulesOf()`,
  `readingOrderOf()`, `summaryLine()`. Старые файлы читаются как есть (у новых
  полей значения по умолчанию).

### Агент и инструменты

- `AiAgent.kt`: инструмент `book_learn` (URL или текст, `user=true` для слов
  пользователя), onboarding-блок для новой сессии книги, документация инструмента,
  `knownToolNames`.
- `AiPlugins.kt`: `web_search`, `web_fetch`, `book_recall`, `book_remember`,
  `book_learn` добавлены в `RESERVED_TOOL_NAMES`.
- `ReaderAiChatOverlay.kt`: строка «Сессия книги», создание сессии при входе,
  подсказка модели про присланные ссылки (`book_learn`), маршрутизация чата
  через настройки оркестратора.

### Авточтение

- `AutoReadEngine.kt`: правила книги читаются один раз на кадр
  (`loadBookRules()`); выученный `reading_order` важнее пресета, с откатом на
  старое поле `Book.readingOrder`; правило «только облачки»
  (`bubblesOnly()`) поднимает YOLO-детектор баллонов в основной путь.
  `region`, `transcription`, `voice` применяются через advice в AI-уточнении
  озвучки — буквального применения рамки из текста правила пока нет.

### Раздельные модели

- `AiModelRoles.kt` (новый): роли OCR/Чат/Оркестратор, честный статус, пометка
  «как у чата» и «модель выбирает сам бэкенд».
- `AiBackends.kt`: `Plugin.supportsModelChoice` (у `local` и `runner` — false,
  потому что модель там своя), `resolve(..., modelOverride)`.
- `AiAssistant.kt`: `chatFull(..., modelOverride)`; для Zen модель берётся из
  каталога, для OpenRouter — любой id.
- `OcrPreferences.kt`: `aiOrchestratorBackend()` и `aiOrchestratorModel()`.
- `AiModelPickerDialog.kt`: выбор бэкенда и модели оркестратора, поле модели
  отключается для бэкендов без выбора модели.

### Прочее

- `ReaderActivity.kt`: `aiChatVisible` на уровне activity, `ensureBookAiSession()`
  при первом авточтении и при входе в AI-чат; при создании сессии показывается
  тост со сводкой, сам чат не навязывается поверх кадра.
- `ReaderAiChatOverlay.kt`: быстрые действия «Источник книги» и «Правила книги»
  (подставляют готовую формулировку в поле ввода), сводка сессии
  обновляется после каждого ответа.
- Тесты: `BookLearningTest.kt` (17 проверок), `AiModelRolesTest.kt` (6 проверок).

## Diff локального клона

- `git diff`: изменения десяти файлов плюс четыре новых (`BookLearning.kt`,
  `AiModelRoles.kt`, `BookLearningTest.kt`, `AiModelRolesTest.kt`) — полный
  diff сохранён в коммите этого изменения.
- `git diff --cached`: пусто перед коммитом.
- Сборка локально не запускалась: по `MANIFEST.md` проверка только через
  GitHub Actions.

## Следующий шаг

1. Дождаться результатов GitHub Actions (`spotlessCheck`, unit-тесты, сборка).
2. Если сборка упадёт — чинить по логу workflow, не запуская Gradle локально.
3. Дальше по плану из `todo.md`: отдельный явный ввод текста/ссылки в UI и
   буквальное применение `region`-правил.

Секреты и значения токенов в этот файл не записываются.
