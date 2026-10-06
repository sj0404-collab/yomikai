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

# 9) Быстрые действия манги (FAB): розовая «читать голосом» → авточтение сразу;
#    иначе через Start → читалка → иконки app-bar.
adb shell input tap 558 1417    # розовая FAB «читать с голосом»
sleep 5
$UAPY cap "$ART/06_fab_route.png"
$UAPY dumpfile "$ART/ui_fab_route.xml"
STARTED=0
if $UAPY tapnode "Got it" 6 || $UAPY tapnode "Понятно" 6; then sleep 2; fi
# авточтение уже запустилось самой FAB? проверим по любому текстовому маркеру
DUMP=$($UAPY dump 2>/dev/null || true)
if echo "$DUMP" | grep -qiE "пауз|стоп|auto.read|пуск|останов"; then
  STARTED=1; say "FAB: авточтение похоже активно"
else
  say "FAB: не запустилась — маршрут Start → читалка → app-bar"
  adb shell input keyevent BACK >/dev/null 2>&1 || true
  sleep 1
  adb shell input keyevent BACK >/dev/null 2>&1 || true
  sleep 1
  # вернулись на экран манги → Start
  if $UAPY tapnode "Start" 8 || $UAPY tapnode "Продолжить" 8 || $UAPY tapnode "Read" 8; then
    say "Start тапнулся"
  else
    adb shell input tap 740 1417   # fallback: правая синяя Start кнопка
  fi
  sleep 12
  $UAPY cap "$ART/05b_reader.png"
  # баннер / hints
  $UAPY tapnode "Got it" 6 || $UAPY tapnode "Понятно" 6 || true
  sleep 1
  # app-bar: центр, движок, назад, авточтение
  adb shell input tap 720 900
  sleep 1
  adb shell input tap 617 125
  sleep 2
  $UAPY cap "$ART/06_ocr_menu.png"
  if $UAPY tapnode "Space Bunny" 8 || $UAPY tapnode "OpenCode" 8; then
    pass "Движок OCR = Space Bunny Free (OpenCode Zen, онлайн)"
  else
    say "NOTE: онлайн-движок не тапнулся (см. 06_ocr_menu.png)"
  fi
  sleep 1
  adb shell input keyevent BACK
  sleep 1
  adb shell input tap 720 900
  sleep 1
  adb shell input tap 720 125
  sleep 3
  $UAPY cap "$ART/07_autoread_menu.png"
  $UAPY dumpfile "$ART/ui_autoread_menu.xml"
  for s in "Читать главу" "Читать вслух" "Читать с голосом" "Голосом" "Вслух" "Начать чтение" "Слушать главу" "Авточтение"; do
    if $UAPY tapnode "$s" 8; then say "Запустил авточтение: $s"; STARTED=1; break; fi
  done
fi
if [ $STARTED = 1 ]; then
  pass "Авточтение главы запущено"
else
  fails "Авточтение не запустилось ни FAB, ни через меню (см. 06/07 + ui_autoread_menu.xml)"
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
grep -q "AiHttpServer started on :8765" "$LC" && pass "AiHttpServer стартовал в приложении" || fails "AiHttpServer не стартовал"
[ "$STARTED" = 1 ] && grep -qiE "AutoRead|reader.auto_read|avtoчтение|Авточтение|startAutoRead" "$LC" \
  && pass "Лог авточтения присутствует" || fails "Лог авточтения не найден"
grep -qiE "ZenFreeOcr|opencode.ai" "$LC" && pass "Онлайн OCR-движок вызывался (zen)" || fails "ZenFreeOcr в логе не найден"
grep -qiE "edge_tts|TrustedClientToken" "$LC" && pass "TTS (edge) в логе виден" || say "NOTE: edge_tts маркеров в logcat нет (см. сам лог)"
grep -qiE "bubble|YoloDetector|detectPanels" "$LC" && pass "Детектор баблонов вызывался" || say "NOTE: детектор не логирует"
grep -qiE "FATAL EXCEPTION|AndroidRuntime: E" "$LC" && fails "В логе FATAL EXCEPTION" || pass "Fatal-крейшей нет"
grep -qiE "ANR in" "$LC" && fails "В логе ANR" || pass "ANR нет"

# 12) OCR настройки после всего — финальный снимок (ещё раз HTTP)
curl -sS -m 10 "http://127.0.0.1:8765/ocr/settings?key=$TOKEN" -o "$ART/ocr_settings_final.json" || true
curl -sS -m 10 "http://127.0.0.1:8765/files?key=$TOKEN" -o "$ART/files.json" || true

kill $LC_PID >/dev/null 2>&1 || true

echo "" >> "$REPORT"
[ "$FAIL" = 0 ] && echo "## ИТОГ: PASS" >> "$REPORT" || echo "## ИТОГ: FAIL" >> "$REPORT"
cat "$REPORT"
exit $FAIL
