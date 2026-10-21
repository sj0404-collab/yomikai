#!/usr/bin/env python3
"""Генератор частотного словаря словоформ RU для OCR (RuWordList).

Источники:
  * морфологический лексикон OpenCorpora (по словоформе в строке). Раньше
    брались частоты из hermitdave/FrequencyWords, но в корпусе субтитров
    много слипшихся токенов («КАКЖЕ», «СЕГОДНЯЯЯНЕ»): они попадали в
    словарь, прогор «КАКЖЕ» считался целым словом, и разбиение не работало;
  * ё/е-варианты каждой словоформы;
  * ё/е-варианты каждой отобранной формы (OCR читает ё непоследовательно);
  * формы, уже лежавшие в репозитории (литературная лексика классических
    текстов, которой в субтитрах нет);
  * ручные сиды: слова из device-регрессий пользователя и однобуквенные
    служебные слова, без которых не делятся слипшиеся прогоны («ЗАЭТИГОДЫЯ»).

Результат — один бинарный ресурс `data/src/main/resources/ru_words.bin`:
заголовок "RUW1", int32 LE — количество, далее int32 LE — отсортированные
хеши FNV-1a всех форм в нижнем регистре. Никаких Kotlin-файлов: 3 млн
строковых литералов раздували DEX на ~30 МБ и собирались в HashSet из 3 млн
строк при первом распознавании (OOM на телефоне).

Использование:
    python3 gen_ruwordlist.py /path/to/ru_lexicon.txt /path/to/repo
"""
import glob
import os
import re
import struct
import sys

FREQ_THRESHOLD = 20
REPO_OCR_DIR = "data/src/main/java/mihon/data/ocr"
RESOURCES_DIR = "data/src/main/resources"
BINARY_NAME = "ru_words.bin"

# Однобуквенные служебные слова: DP-разбиение разрешает их как отдельные
# сегменты («ЗА ЭТИ ГОДЫ Я»), поэтому они обязаны быть в словаре.
SINGLE_LETTER = ["А", "В", "И", "К", "О", "С", "У", "Я"]

# Сиды из device-регрессий: слова, на которых пользователь видел слипшийся или
# неразделённый текст. Часть из них редка в субтитрах (ПРОШТУДИРОВАЛ — 9
# вхождений), но встречается в книгах и подписях к манге.
SEEDS = """
СОДЕРЖАЛА ПРОШТУДИРОВАЛ НЕСМЕТНОЕ НАМЁКА НАМЕКА ПОДОБНОЕ КНИГА КНИГИ КНИГ
БАСКЕРВИЛЬ ВИКИР ДЕМОНОМ СГОВОРЕ ОХОТНИЧЬЕГО ОХОТНИЧИЙ ПОСВЯТИЛ ОТЦУ СЕМЬЕ
КОНЧИКА КОНЧИКОВ ТРЕНИРОВКА ОКОНЧЕНА ЖАРКО СМОГУ КОСНУТЬСЯ ПАРНЯ ВОЛОС
НЕУЖЕЛИ ВЫГЛЯДИТ ПРИБЫЛИ ВДРУГ МИРА СВОИХ МНОЖЕСТВО ГОДЫ ДЕРЖАЛА НЕЧТО
ГИЛЬОТИНЫ ЛЕЗВИЕМ ТЯЖЕСТЬ ШУМНО ЛОЖНО ОБВИНЁН ОБВИНЕН ПСА ПЁС ДОМА УФ БАНАРА
БЕЗУМИЕ ОТЧАЯНИЕ НАДЕЖДА СУДЬБА ЧУВСТВО ПАМЯТЬ МОЛЧАНИЕ ПРОШЛОЕ БУДУЩЕЕ
""".split()


def load_frequency(path):
    rows = []
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            head, _, tail = line.rpartition(" ")
            word, count = head.strip(), tail.strip()
            if word and count.isdigit():
                rows.append((word, int(count)))
    return rows


def is_wordform(word):
    if not 2 <= len(word) <= 24:
        return False
    if word.startswith("-") or word.endswith("-") or "--" in word:
        return False
    return all(("а" <= ch <= "я") or ch in "ёъыь" or ch == "-" for ch in word)


def with_yo_variants(word):
    """ё и е в OCR неразличимы: словарь обязан знать обе записи."""
    out = {word}
    if "е" in word:
        out.add(word.replace("е", "ё"))
    if "ё" in word:
        out.add(word.replace("ё", "е"))
    return out


def repo_forms(repo):
    forms = set()
    pattern = os.path.join(repo, REPO_OCR_DIR, "RuWordListPart*.kt")
    for path in glob.glob(pattern):
        for word in re.findall(r'"([^"]+)"', open(path, encoding="utf-8").read()):
            if word.isalpha() or "-" in word:
                forms.add(word.upper())
    return forms


def load_lexicon(path):
    """Морфологический лексикон (OpenCorpora): по словоформе в строке.

    Именно он, а не частотник, решает, что считать словом: в корпусе
    субтитров полно слипшихся токенов («КАКЖЕ», «СЕГОДНЯЯНЕ»), и в словарь
    они попадали вместе с настоящими словами. Из-за этого прогор «КАКЖЕ»
    считался целым словом, и разбиение переставало работать.
    """
    forms = set()
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            word = line.strip()
            if is_wordform(word):
                forms.add(word)
    return forms


def build(lexicon_path, repo):
    lexicon = load_lexicon(lexicon_path)
    forms = set()
    for word in lexicon:
        forms |= with_yo_variants(word)
    forms |= {w.lower().replace("ё", "е") for w in SEEDS if w}
    forms |= {w.lower() for w in SINGLE_LETTER}
    # Прежний словарь больше не подтягивается: OpenCorpora и есть литературная
    # лексика, а перенос старых файлов делал сборку неидемпотентной — слипшийся
    # токен, однажды попавший в словарь, воспроизводился при каждой пересборке,
    # и «КАКЖЕ» снова и снова считался целым словом.
    # Всё хранится в нижнем регистре: [RuWordList.upper] строит прописные формы
    # в рантайме. Хранение обоих регистров удваивало и словарь, и память.
    forms = {f.replace("ё", "е") for f in forms if f}
    return sorted(forms)


def write_binary(repo, forms):
    """Кладёт формы одним бинарным ресурсом вместо 603 файлов Kotlin.

    Раньше генератор писал `RuWordListPartNNN.kt`: 3 млн строковых литералов
    попадали в DEX (~30 МБ в APK) и при первом распознавании собирались в
    HashSet из 3 млн строк — на телефоне это OutOfMemoryError. Теперь пишем
    отсортированные 32-битные хеши FNV-1a: 12 МБ файла, 12 МБ памяти, поиск
    бинарный, и ни одной строки в куче.
    """
    out_dir = os.path.join(repo, RESOURCES_DIR)
    os.makedirs(out_dir, exist_ok=True)
    for stale in glob.glob(os.path.join(repo, REPO_OCR_DIR, "RuWordListPart*.kt")):
        os.remove(stale)
    hashes = sorted({signed_int(fnv1a(form, casefold=True)) for form in forms})
    path = os.path.join(out_dir, BINARY_NAME)
    with open(path, "wb") as handle:
        handle.write(b"RUW1")
        handle.write(struct.pack("<i", len(hashes)))
        handle.write(struct.pack(f"<{len(hashes)}i", *hashes))
    return len(hashes)


def signed_int(value):
    return value - 2 ** 32 if value >= 2 ** 31 else value


def fnv1a(word, casefold=False):
    """FNV-1a по code units — ровно как в RuWordList.hash().

    Русские формы целиком в BMP, поэтому code point == UTF-16 code unit.
    """
    if casefold:
        word = word.lower()
    h = 2166136261
    for ch in word:
        h ^= ord(ch)
        h = (h * 0x01000193) & 0xFFFFFFFF
    return h


def main():
    lexicon_path, repo = sys.argv[1], sys.argv[2]
    forms = build(lexicon_path, repo)
    count = write_binary(repo, forms)
    print(f"форм: {len(forms)}, хешей: {count} -> {BINARY_NAME}")


if __name__ == "__main__":
    main()
