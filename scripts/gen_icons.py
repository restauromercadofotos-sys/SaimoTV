#!/usr/bin/env python3
"""Gera o ícone e o banner da Sintonia TV sem depender de outro projeto."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

RES = Path(__file__).resolve().parents[1] / "app/src/main/res"
FUNDO = "#101716"
MENTA = "#73F2BE"
DENSIDADES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
BANNERS = {"xhdpi": 320, "xxhdpi": 480, "xxxhdpi": 640}


def simbolo(img, x, y, tamanho):
    desenho = ImageDraw.Draw(img)
    # Dois traços arredondados formam o S e lembram linhas de sinal.
    curvas = (
        (((84, 25), (65, 25), (47, 25), (36, 25)),
         ((36, 25), (14, 25), (13, 51), (35, 51)),
         ((35, 51), (45, 51), (55, 51), (65, 51))),
        (((36, 51), (47, 51), (56, 51), (67, 51)),
         ((67, 51), (89, 51), (86, 78), (64, 78)),
         ((64, 78), (45, 78), (33, 78), (16, 78))),
    )
    for traco in curvas:
        xy = []
        for inicio, controle1, controle2, fim in traco:
            for passo in range(25):
                t = passo / 24
                u = 1 - t
                a, b = (u**3 * p + 3*u*u*t * q + 3*u*t*t * r + t**3 * s
                        for p, q, r, s in zip(inicio, controle1, controle2, fim))
                xy.append((x + round(a * tamanho / 100), y + round(b * tamanho / 100)))
        grosso = max(2, round(tamanho * 0.118))
        desenho.line(xy, fill=MENTA, width=grosso, joint="curve")
        for a, b in (xy[0], xy[-1]):
            raio = grosso // 2
            desenho.ellipse((a - raio, b - raio, a + raio, b + raio), fill=MENTA)


for densidade, lado in DENSIDADES.items():
    pasta = RES / f"mipmap-{densidade}"
    n = lado * 4
    for circular in (False, True):
        img = Image.new("RGBA", (n, n))
        draw = ImageDraw.Draw(img)
        if circular:
            draw.ellipse((0, 0, n - 1, n - 1), fill=FUNDO)
        else:
            draw.rounded_rectangle((0, 0, n - 1, n - 1), radius=n // 5, fill=FUNDO)
        simbolo(img, n // 8, n // 8, n * 3 // 4)
        nome = "ic_launcher_round.png" if circular else "ic_launcher.png"
        img.resize((lado, lado), Image.Resampling.LANCZOS).save(pasta / nome)
    camada = round(lado * 108 / 48)
    n = camada * 4
    frente = Image.new("RGBA", (n, n))
    simbolo(frente, n // 5, n // 5, n * 3 // 5)
    frente.resize((camada, camada), Image.Resampling.LANCZOS).save(pasta / "ic_launcher_frente.png")
    Image.new("RGB", (camada, camada), FUNDO).save(pasta / "ic_launcher_fundo.png")

for densidade, largura in BANNERS.items():
    w = largura * 3
    h = w * 9 // 16
    banner = Image.new("RGB", (w, h), FUNDO)
    draw = ImageDraw.Draw(banner)
    font = ImageFont.truetype("DejaVuSans-Bold.ttf", round(w * 0.078))
    simbolo(banner, round(w * 0.08), round(h * 0.30), round(h * 0.40))
    x = round(w * 0.32)
    draw.text((x, h // 2), "SINTONIA", font=font, fill="white", anchor="lm")
    x += round(draw.textlength("SINTONIA ", font=font))
    assert x + draw.textlength("TV", font=font) < w * 0.94
    draw.text((x, h // 2), "TV", font=font, fill=MENTA, anchor="lm")
    banner.resize((largura, largura * 9 // 16), Image.Resampling.LANCZOS).save(
        RES / f"drawable-{densidade}" / "banner.png")
