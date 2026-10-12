#!/usr/bin/env python3
"""Генератор частотного словаря словоформ RU для OCR (RuWordList).

Источники:
  * OpenSubtitles через hermitdave/FrequencyWords (тот же источник, что и у
    словаря Latin_to_Cyrillic в dictionaries/): словоформы с частотой >= 200
    вхождений в корпусе;
  * ё/е-варианты каждой отобранной формы (OCR читает ё непоследовательно);
  * формы, уже лежавшие в репозитории (литературная лексика классических
    текстов, которой в субтитрах нет);
  * ручные сиды: слова из device-регрессий пользователя и однобуквенные
    служебные слова, без которых не делятся слипшиеся прогоны («ЗАЭТИГОДЫЯ»).

Файлы кладутся в data/src/main/java/mihon/data/ocr/RuWordListPartNNN.kt —
по 5000 форм на файл (тот же формат, что у vocabFastPart*: arrayOf-литералы
разбиты по файлам, чтобы не упереться в лимит размера метода JVM).

Использование:
    python3 gen_ruwordlist.py /path/to/ru_full.txt /path/to/repo
"""
import glob
import os
import re
import sys

FREQ_THRESHOLD = 200
PART_SIZE = 5000
REPO_OCR_DIR = "data/src/main/java/mihon/data/ocr"

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


def build(frequency_path, repo):
    rows = load_frequency(frequency_path)
    selected = {w for w, c in rows if c >= FREQ_THRESHOLD and is_wordform(w)}
    forms = set()
    for word in selected:
        forms |= with_yo_variants(word)
    forms |= repo_forms(repo)
    forms |= {w.upper() for w in SEEDS if w}
    forms |= set(SINGLE_LETTER)
    return sorted(forms)


def write_parts(repo, forms):
    out_dir = os.path.join(repo, REPO_OCR_DIR)
    for stale in glob.glob(os.path.join(out_dir, "RuWordListPart*.kt")):
        os.remove(stale)
    index = 0
    for start in range(0, len(forms), PART_SIZE):
        part = forms[start:start + PART_SIZE]
        path = os.path.join(out_dir, f"RuWordListPart{index:03d}.kt")
        with open(path, "w", encoding="utf-8") as handle:
            handle.write('@file:Suppress("SpellCheckingInspection")\n\n')
            handle.write("package mihon.data.ocr\n\n")
            handle.write("/**\n")
            handle.write(" * Generated file — do not edit manually.\n")
            handle.write(" * Частотные словоформы RU (OpenSubtitles/FrequencyWords, частота >= "
                         f"{FREQ_THRESHOLD}), ё/е-варианты, сиды регрессий.\n")
            handle.write(" * Пересборка: scripts/gen_ruwordlist.py\n")
            handle.write(" */\n")
            handle.write(f"internal fun ruWordListPart{index:03d}(): Array<String> = arrayOf(\n")
            for word in part:
                handle.write(f'    "{word}",\n')
            handle.write(")\n")
        index += 1
    return index


def main():
    frequency_path, repo = sys.argv[1], sys.argv[2]
    forms = build(frequency_path, repo)
    parts = write_parts(repo, forms)
    print(f"форм: {len(forms)}, файлов: {parts}")


if __name__ == "__main__":
    main()
