#!/usr/bin/env python3
"""Генератор тестовой CBZ-манги с русскими пузырями для e2e-прогона OCR/TTS авточтения."""
import os, sys, zipfile, textwrap
from PIL import Image, ImageDraw, ImageFont

OUT_DIR = sys.argv[1] if len(sys.argv) > 1 else "/tmp/cbz"
os.makedirs(OUT_DIR, exist_ok=True)

PAGE_W, PAGE_H = 1080, 1700
FONT_PATH = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"

SPEECH = [
    "Привет! Это тест авточтения главы.",
    "Онлайн сканер должен найти этот пузырь.",
    "Голос тоже проверяется сейчас.",
    "Если слышишь меня — OCR и TTS живы.",
    "Мы читаем мангу голосом сегодня.",
    "Страница листается сама дальше.",
    "Враг прячется за старой стеной.",
    "Герой поднимает свой меч вверх.",
    "Ветер шумит над тихой рекой.",
    "Кот смотрит в окно молча долго.",
    "Сообщение принято. Начинаем атаку.",
    "Откуда взялся этот странный шум?",
    "Я не пойму, куда ты идёшь ночью.",
    "Ночь темна, однако путь открыт.",
    "Хвала солнцу, дождь прошёл быстро.",
    "Кто там стучится в мою дверь?",
]
ROWS_PER_PAGE = 4

def make_page(idx, lines):
    img = Image.new("RGB", (PAGE_W, PAGE_H), (38, 40, 44))
    d = ImageDraw.Draw(img)
    font = ImageFont.truetype(FONT_PATH, 44)
    fbig = ImageFont.truetype(FONT_PATH, 64)
    # рамка-панель
    d.rectangle([24, 24, PAGE_W - 24, PAGE_H - 24], outline=(210, 210, 210), width=6)
    # заголовок страницы (нарратор, не в пузыре)
    d.text((60, 70), f"Глава 1 — страница {idx}", font=fbig, fill=(250, 250, 250))
    y = 240
    bw, bh = PAGE_W - 140, (PAGE_H - 420) // ROWS_PER_PAGE - 40
    for li, line in enumerate(lines):
        x0, y0 = 70, y + li * (bh + 40)
        x1, y1 = x0 + bw, y0 + bh
        # пузырь: белый эллипс с чёрным контуром — то, что ищет детектор баблонов
        d.ellipse([x0, y0, x1, y1], fill=(255, 255, 255), outline=(0, 0, 0), width=5)
        # текст по центру пузыря, максимум две строки
        words = line.split()
        half = (len(words) + 1) // 2
        t1 = " ".join(words[:half]); t2 = " ".join(words[half:])
        tw1 = d.textlength(t1, font=font); tw2 = d.textlength(t2, font=font) if t2 else 0
        ty = y0 + bh // 2 - (48 if t2 else 24)
        d.text(((PAGE_W - tw1) / 2, ty), t1, font=font, fill=(10, 10, 10))
        if t2:
            d.text(((PAGE_W - tw2) / 2, ty + 52), t2, font=font, fill=(10, 10, 10))
    # маленькая подпись внизу
    d.text((60, PAGE_H - 120), f"Тестовая страница №{idx} (генератор e2e)", font=font, fill=(180, 180, 180))
    path = os.path.join(OUT_DIR, f"{idx:03d}.jpg")
    img.save(path, "JPEG", quality=92)
    return path

files = []
for p in range(4):
    files.append(make_page(p + 1, SPEECH[p * ROWS_PER_PAGE:(p + 1) * ROWS_PER_PAGE]))

cbz = os.path.join(OUT_DIR, "zen01.cbz")
with zipfile.ZipFile(cbz, "w", zipfile.ZIP_DEFLATED) as z:
    for f in files:
        z.write(f, os.path.basename(f))
print("CBZ:", cbz, os.path.getsize(cbz), "bytes")
