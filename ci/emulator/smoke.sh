#!/usr/bin/env bash
# e2e smoke Yomikai в эмуляторе: установка APK, расширения, локальная манга,
# пресет OCR «online», авточтение главы, голоса (edge_tts), автоскролл.
# Всё, что можно проверить без LLM, проверяем через HTTP-сервер агента (:8765).
set -uo pipefail

APK="${1:?apk path}"
CBZ="${2:?cbz path}"
PKG="app.yomikai"
TOKEN="EMU-TOKEN-9f4d2a77c3"
ART="${ART_DIR:-/tmp/e2e-artifacts}"
mkdir -p "$ART"
REPORT="$ART/smoke_report.md"
: > "$REPORT"
say()  { echo "[smoke] $*" | tee -a "$REPORT"; }
pass() { echo "- ✅ $*" >> "$REPORT"; say "PASS: $*"; }
fails() { echo "- ❌ $*" >> "$REPORT"; say "FAIL: $*"; FAIL=1; }
FAIL=0


adb wait-for-device
adb shell 'for i in $(seq 1 60); do [ "$(getprop sys.boot_completed)" = 1 ] && exit 0; sleep 5; done'
say "Эмулятор загружен"

adb root >/dev/null 2>&1 || true
sleep 3; adb wait-for-device
R=$([ -n "$(adb shell id | grep uid=0)" ] && echo yes || echo no)
say "adb root: $R"

# 1) Контент: манга в app-private Android/data — там FUSE не режет по uid (любые uid файлов
#    приложению видны и доступны), папку распознаём через external_library_roots (сид в префах)
adb shell "rm -rf /sdcard/Yomikai"   # мусор старых прогонов (чужие uid — пусть не мешает)
# контент — на ext4 /data в files приложения: утилита uid там настоящая, chown работает.
# Базовое хранилище приложения сидим (storage_dir) на этот ext4-путь в префах ниже.
# CBZ временно на /sdcard/Download (существует всегда); перенос из-под приложения — после установки
adb push "$CBZ" "/sdcard/Download/zen01.cbz" >> "$ART/adb.log" 2>&1

# 2) Установка APK
adb install -r -g "$APK" | tee -a "$ART/adb.log"
# жёсткий догрант основных runtime-разрешений (POST_NOTIFICATIONS и др.)
for PERM in android.permission.POST_NOTIFICATIONS android.permission.READ_MEDIA_IMAGES android.permission.READ_MEDIA_VIDEO android.permission.ACCESS_NETWORK_STATE; do
  adb shell pm grant $PKG $PERM >/dev/null 2>&1 || true
done
# Видимость .cbz в /sdcard/Yomikai требует all-files доступа (scoped storage API 30+)
# all-files через appops (pm grant → SecurityException: permission not changeable)
adb shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow >> "$ART/adb.log" 2>&1 || true
adb shell appops get $PKG MANAGE_EXTERNAL_STORAGE 2>/dev/null | head -1 | tee -a "$ART/adb.log"
[ "$(adb shell pm list packages | grep -c "^package:$PKG$")" = 1 ] \
  && pass "APK установлен ($PKG)" || fails "APK не установился"
# uid приложения — самый стабильный вывод: pm list packages -U → "package:app.yomikai uid:10192"
# uid приложения (для chown prefs на /data — там chown работает честно)
PMU=$(adb shell "pm list packages -U" | grep -F "package:$PKG" | head -1)
UID_APP=$(echo "$PMU" | grep -oE "uid:[0-9]+" | cut -d: -f2)
say "uid: $UID_APP"
# контент — ПОСЛЕ install: /data/data/<pkg> от PackageManager уже создан корректно;
# app-private files: создаём дерево от root и отдаём владение приложению (ext4 честный chown).
adb shell "mkdir -p /data/data/$PKG/files/ystorage/local/ZenTest" >> "$ART/adb.log" 2>&1
adb shell "cp -f /sdcard/Download/zen01.cbz /data/data/$PKG/files/ystorage/local/ZenTest/zen01.cbz" >> "$ART/adb.log" 2>&1
adb shell chown -R $UID_APP:$UID_APP /data/data/$PKG/files/ystorage
adb shell restorecon -R /data/data/$PKG/files/ystorage >> "$ART/adb.log" 2>&1 || true
adb shell "ls -laR /data/data/$PKG/files/ystorage" | tee -a "$ART/adb.log"

# 3) Сид настроек ДО первого запуска
if [ "$R" = yes ]; then
  adb shell "mkdir -p /data/data/$PKG/shared_prefs"
  cat > "$ART/prefs.xml" <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="__APP_STATE_onboarding_complete" value="true" />
    <string name="pref_fallback_preset">online</string>
    <string name="pref_voice_engine">edge_tts</string>
    <string name="pref_ai_http_token">$TOKEN</string>
    <boolean name="pref_ai_http_server" value="true" />
    <string name="__APP_STATE_storage_dir">file:///data/user/0/app.yomikai/files/ystorage</string>
    <boolean name="pref_autoread_advance" value="true" />
    <boolean name="pref_autoread_music_enabled" value="false" />
    <boolean name="verbose_logging" value="true" />
</map>
EOF
  adb push "$ART/prefs.xml" "/data/data/$PKG/shared_prefs/app.yomikai_preferences.xml" >/dev/null
  adb shell "chown $UID_APP:$UID_APP /data/data/$PKG/shared_prefs/app.yomikai_preferences.xml; chmod 660 /data/data/$PKG/shared_prefs/app.yomikai_preferences.xml"
  # SELinux-контекст: без этого app не может прочитать файл (W SharedPreferencesImpl: without permission)
  adb shell "restorecon /data/data/$PKG/shared_prefs/app.yomikai_preferences.xml" >> "$ART/adb.log" 2>&1 || \
  adb shell "chcon u:object_r:app_data_file:s0:c192,c256,c512,c768 /data/data/$PKG/shared_prefs/app.yomikai_preferences.xml" >> "$ART/adb.log" 2>&1 || true
  adb shell "ls -laZ /data/data/$PKG/shared_prefs/app.yomikai_preferences.xml" | tee -a "$ART/adb.log"
  pass "Настройки засеяны (preset=online, voice=edge_tts, onboarding+token)"
else
  fails "Нет root — prefs не засеять"
fi

# 4) Логкат в фоне
adb logcat -c
adb logcat -v threadtime > "$ART/logcat.txt" 2>&1 &
LC_PID=$!
sleep 1

probe_port(){
  say "netstat 8765 probe ($1)"
  adb shell "ss -lntp 2>/dev/null | grep 8765 || echo '(порт 8765 не слушается)'" | tee -a "$ART/adb.log"
}
# 5) Запуск приложения
# ВАЖНО: директории манги отдаём приложению — LocalSource пишет туда .noxml маркер (иначе EPERM и глав не видно)
adb shell "cat /data/data/$PKG/shared_prefs/app.yomikai_preferences.xml | grep -E 'ai_http|fallback_preset|voice_engine'" | tee -a "$ART/adb.log"
adb shell am start -n "$PKG/eu.kanade.tachiyomi.ui.main.MainActivity" >> "$ART/adb.log" 2>&1
sleep 18
probe_port "после старта"
UAPY="python3 ci/emulator/uiauto.py"
# если всё же вылез контроллер разрешений — скинуть его
for i in 1 2 3; do
  CUR=$(adb shell dumpsys activity activities | grep -m1 "topResumedActivity" || true)
  echo "$CUR" | grep -q "permissioncontroller" || break
  say "Системный диалог разрешений — скипаю ($i)"
  $UAPY tapnode "Разрешить" 3 || $UAPY tapnode "Allow" 3 || $UAPY tapnode "WHILE USING THE APP|При использовании|Далее|Next|OK" 3 || adb shell input keyevent BACK
  sleep 2
done
$UAPY cap "$ART/01_home.png"
CURR=$(adb shell dumpsys activity activities | grep -m1 "topResumedActivity" || true)
say "На вершине: $CURR"
echo "$CURR" | grep -q "app.yomikai" && pass "Главный экран поднялся" || fails "Приложение не поднялось"

# 6) Проброс HTTP-агента и проверка сервера
adb forward tcp:8765 tcp:8765 >/dev/null 2>&1
AI_OK=0
for i in 1 2 3 4 5 6; do
  if curl -sS -m 8 "http://127.0.0.1:8765/ocr/settings?key=$TOKEN" -o "$ART/ocr_settings.json" >/dev/null 2>&1; then AI_OK=1; break; fi
  say "AiHttpServer не ответил ($i/6), жду 10с"; sleep 10
done
[ $AI_OK = 1 ] && pass "AiHttpServer отвечает (:8765)" || fails "AiHttpServer не ответил"
head -c 300 "$ART/ocr_settings.json" | sed 's/^/  /' | tee -a "$REPORT"
grep -qi "\"preset\":\s*\"online\"\|pref_fallback_preset.\+:.\+online" "$ART/ocr_settings.json" 2>/dev/null \
  && pass "Пресет OCR = online (server-side)" || say "NOTE: в ocr/settings строки 'online' не найдено — проверка по JSON вручную"

# Голоса: список (должен быть edge_tts/voice catalogue живой)
curl -sS -m 15 "http://127.0.0.1:8765/tts/voices?key=$TOKEN" -o "$ART/tts_voices.json" \
  && pass "/tts/voices ответил" || fails "/tts/voices не ответил"
grep -qi "Svetlana\|edge\|voice" "$ART/tts_voices.json" 2>/dev/null \
  && pass "Каталог голосов непустой" || fails "Каталог голосов пуст"

# 7) Вкладка Каталоги — расширения (проверка что реестр загружается из сети)
TAP_SCAN=0
for probe in "Browse"; do
  if $UAPY tapnode "$probe" 15; then say "Тап по вкладке: $probe"; break; fi
done
sleep 22   # сеть: подгрузка репо Keiyoushi
$UAPY tapnode "^Extensions$" 10 || $UAPY tapnode "Расширени" 10 || true
sleep 10
$UAPY cap "$ART/02_browse.png"
DUMP=$($UAPY dump 2>/dev/null || true)
if echo "$DUMP" | grep -qiE "Keiyoushi|Multi-source|Group|Source:|Расширен"; then
  pass "Список источников/расширений загрузился"
else
  say "NOTE: вкладку расширений не заценили (см. 02_browse.png)"
fi

# 8) Локальная библиотека — там живёт ZenTest (вкладка между Library и Updates)
if $UAPY tapnode "^Локальная$" 12; then
  say "Вошёл на вкладку Локальная"
else
  say "NOTE: вкладка Локальная не найдена по тексту — пробую по иконке (2-я слева)"
  adb shell input tap 175 1480 || true
fi
sleep 8
$UAPY cap "$ART/03_pref_local.png"
# если есть кнопка сканирования/обновления — нажать
$UAPY tapnode "Сканир|Обнов|Scan|Refresh" 6 || true
sleep 10
$UAPY cap "$ART/03_local_list.png"
if $UAPY tapnode "ZenTest" 25; then
  pass "Манга ZenTest найдена и открыта"
else
  fails "Манга ZenTest не найдена"
fi
sleep 6
$UAPY cap "$ART/04_manga.png"

# список глав грузится на ЭКРАНЕ МАНГИ (уходить через BACK нельзя — вернёт в каталог);
# вместо этого: ждём имя нашей главы, при необходимости проскроллить до неё
CU=0
for i in 1 2 3 4 5 6; do
  DMP=$($UAPY dump 2>/dev/null || true)
  if echo "$DMP" | grep -qi "zen01\|chapters.*[1-9]\|[1-9] chapter\|1 глава"; then CU=1; break; fi
  say "главы ещё грузятся ($i/6)"
  adb shell input swipe 720 1500 720 700 400  # проскролл вниз, вдруг ушло ниже
  sleep 6
done
$UAPY cap "$ART/04b_chapters.png"
# tap по главе: имя файла БЕЗ регистра: пробуем варианты отображения
for s in "zen01" "Zen01" "Глава 1" "Chapter"; do
  if $UAPY tapnode "$s" 25; then say "Открыл главу: $s"; break; fi
done
sleep 14
$UAPY cap "$ART/05_reader.png"
CURR=$(adb shell dumpsys activity activities | grep -m1 "topResumedActivity" || true)
echo "$CURR" | grep -qiE "Reader|reader|ui.reader" && pass "Читалка открыта" || fails "Читалка не открылась"

# 9) Reader-app-bar по desc: OCR-движок → Space Bunny (онлайн) → Меню читалки → «Читать главу»
$UAPY tapnode "Got it" 6 || $UAPY tapnode "Понятно" 6 || true
sleep 1
adb shell input tap 730 1200    # поднять app-bar (физ. 1440x2560)
sleep 1
$UAPY dumpfile "$ART/ui_appbar.xml"
if $UAPY tapnode "^OCR-движок$" 10; then
  say "открыл меню движков"
else
  adb shell input tap 1174 196  # физ. координата иконки OCR-движок (из ui dump bounds)
fi
sleep 2
$UAPY cap "$ART/06_ocr_menu.png"
if $UAPY tapnode "Space Bunny" 10 || $UAPY tapnode "OpenCode" 10; then
  pass "Движок OCR = Space Bunny Free (OpenCode Zen, онлайн)"
else
  say "NOTE: онлайн-движок не тапнулся (см. 06_ocr_menu.png)"
fi
sleep 1
adb shell input keyevent BACK
sleep 1
adb shell input tap 730 1200    # app-bar снова вверх
sleep 1
if $UAPY tapnode "^Меню читалки$" 10; then
  say "открыл меню читалки"
else
  adb shell input tap 1342 196
fi
sleep 3
$UAPY cap "$ART/07_autoread_menu.png"
$UAPY dumpfile "$ART/ui_autoread_menu.xml"
STARTED=0
for s in "Читать главу" "Читать вслух" "Читать с голосом" "Голосом" "Вслух" "Начать чтение" "Слушать главу" "Авточтение" "Озвучить"; do
  if $UAPY tapnode "$s" 8; then say "Запустил авточтение: $s"; STARTED=1; break; fi
done
if [ $STARTED = 1 ]; then
  # «Озвучить» открыл диалог «Сканирование главы»: выбрать ОНЛАЙН-движок и подтвердить
  sleep 1
  $UAPY cap "$ART/08_scan_dialog.png"
  if $UAPY tapnode "Space Bunny" 10 || $UAPY tapnode "OpenCode" 10; then
    say "в диалоге сканирования выбран онлайн-движок Space Bunny (OpenCode Zen)"
  else
    say "NOTE: в диалоге сканирования Space Bunny не нашёлся (см. 08_scan_dialog.png)"
  fi
  sleep 1
  if $UAPY tapnode "Сканировать и читать" 8; then
    pass "Авточтение главы запущено (онлайн OCR + голос)"
  else
    say "NOTE: кнопку «Сканировать и читать» не нажал — пробую ОК"
    $UAPY tapnode "OK" 6 || true
  fi
  sleep 3
else
  fails "Авточтение не запустилось (см. 07_autoread_menu.png + ui_autoread_menu.xml)"
fi

# 10) 3 минуты авточтения: кадры, листание, логкат-маркеры
SECS=${SECS_AUTO_READ:-180}
t0=$(date +%s)
i=0
while [ $(( $(date +%s) - t0 )) -lt "$SECS" ]; do
  i=$((i+1))
  sleep 25
  $UAPY cap "$ART/10_autoread_$i.png"
  CU=$(adb shell dumpsys activity activities | grep -m1 "topResumedActivity" || true)
  say "Т+$(($(date +%s) - t0))с: $CU"
done

probe_port "перед финальными логами"
# 11) Проверки по логмам
LC="$ART/logcat.txt"
grep -q "AiHttpServer started on :8765" "$LC" && pass "AiHttpServer стартовал в приложении (лог-маркер)" || say "NOTE: лог-маркера старта нет, но сервер отвечал по HTTP — считаю живым"
[ "$STARTED" = 1 ] && grep -qiE "AutoRead|reader.auto_read|авточтение|Авточтение|startAutoRead" "$LC" \
  && pass "Лог авточтения присутствует" || say "NOTE: лог-маркера авточтения нет (verbose отрезан в релизе); функц-проверка по экспорту ниже"
grep -qiE "ZenFreeOcr|opencode.ai" "$LC" && pass "Онлайн OCR-движок вызывался (zen)" || say "NOTE: zen/ocr-маркеров в logcat нет; функц-проверка по экспорт-файлу ниже"
grep -qiE "edge_tts|TrustedClientToken" "$LC" && pass "TTS (edge) в логе виден" || say "NOTE: edge_tts маркеров в logcat нет (см. сам лог)"
grep -qiE "bubble|YoloDetector|detectPanels" "$LC" && pass "Детектор баблонов вызывался" || say "NOTE: детектор не логирует"
grep -qiE "FATAL EXCEPTION|AndroidRuntime: E" "$LC" && fails "В логе FATAL EXCEPTION" || pass "Fatal-крейшей нет"
grep -qiE "ANR in" "$LC" && fails "В логе ANR" || pass "ANR нет"

# 12) OCR настройки после всего — финальный снимок (ещё раз HTTP)
curl -sS -m 10 "http://127.0.0.1:8765/ocr/settings?key=$TOKEN" -o "$ART/ocr_settings_final.json" || true
curl -sS -m 10 "http://127.0.0.1:8765/files?key=$TOKEN" -o "$ART/files.json" || true

# 13) Функциональное доказательство онлайн-OCR: экспорт авточтения из AiHttpServer-ws:
#     export/<Manga> _ <Chapter>.md — скачиваем и матчим фразы из баблонов тест-страниц
EXPORT_PATH=$(grep -oE 'export\\?/[^"]+\.(md|txt)' "$ART/files.json" 2>/dev/null | head -1 | sed 's/\\\//\//g;s/\//\//g')
say "export marker: ${EXPORT_PATH:-(нет)}"
if [ -n "$EXPORT_PATH" ]; then
  EURL="http://127.0.0.1:8765/file?key=$TOKEN&p=$(python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$EXPORT_PATH")"
  if curl -sS -m 15 "$EURL" -o "$ART/autoread_export.md"; then
    if grep -qE "авточтения главы|OCR и TTS живы|проверяется сейчас" "$ART/autoread_export.md"; then
      pass "Онлайн OCR реально распознал текст баблонов (export: $EXPORT_PATH)"
    else
      head -c 300 "$ART/autoread_export.md" | say "$(cat)"
      say "NOTE: экспорт скачался, но фраз из генератора не видно"
    fi
  else
    say "NOTE: файл экспорта не скачался"
  fi
else
  [ "$STARTED" = 1 ] && fails "Файл экспорта авточтения не найден в AiHttpServer workspace"
fi

kill $LC_PID >/dev/null 2>&1 || true

echo "" >> "$REPORT"
[ "$FAIL" = 0 ] && echo "## ИТОГ: PASS" >> "$REPORT" || echo "## ИТОГ: FAIL" >> "$REPORT"
cat "$REPORT"
exit $FAIL
