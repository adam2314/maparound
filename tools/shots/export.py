"""From the raw screenshots to what each place needs: Google Play's framed screenshots, the
website's 720×1280 WebP, the guides' 720 px wide WebP (see SHOTS.md)."""
from pathlib import Path

try:
    from PIL import Image, ImageDraw, ImageFilter, ImageFont
except ImportError:
    raise SystemExit("The shots need Pillow: python -m pip install pillow")

REPO = Path(__file__).resolve().parents[2]
PLAY = REPO / "docs" / "store" / "screenshots"
SITE = REPO / "docs" / "assets"
GUIDES = {"en": REPO / "docs" / "guide" / "img", "he": REPO / "docs" / "he" / "guide" / "img"}

W, H = 1080, 1920
GREEN = (27, 107, 74)  # the brand green (#1B6B4A)
SHADOW = (10, 40, 28)
SHOT_W, RADIUS, TOP = 940, 40, 300


def font(size):
    for f in ("C:/Windows/Fonts/seguisb.ttf", "/System/Library/Fonts/SFNS.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"):
        if Path(f).exists():
            return ImageFont.truetype(f, size)
    return ImageFont.load_default()


def blur(img, rects):
    """Rows that mustn't be published (the GPS screen's position on a real phone), blurred."""
    for r in rects:
        box = tuple(int(v) for v in r)
        img.paste(img.crop(box).filter(ImageFilter.GaussianBlur(14)), box[:2])
    return img


def nine_by_sixteen(img):
    """Google Play and the site want 9:16 at most; a taller screen loses rows at the top."""
    w, h = img.size
    tall = round(w * 16 / 9)
    return img if h <= tall else img.crop((0, h - tall, w, h))


def play(raw, caption, number, name, bars):
    """The caption on the brand green above the screen, its corners rounded, the bars cut off."""
    top, bottom = bars
    crop = raw.crop((0, top, raw.width, raw.height - bottom))
    h = round(crop.height * SHOT_W / crop.width)
    crop = crop.resize((SHOT_W, h), Image.LANCZOS)
    canvas = Image.new("RGB", (W, H), GREEN)
    d = ImageDraw.Draw(canvas)
    f = font(58)
    lines, line = [], ""
    for word in caption.split():
        trial = (line + " " + word).strip()
        if d.textlength(trial, font=f) <= 940 or not line:
            line = trial
        else:
            lines.append(line)
            line = word
    lines.append(line)
    y = (TOP - 72 * len(lines)) // 2 + 4
    for ln in lines:
        d.text(((W - d.textlength(ln, font=f)) / 2, y), ln, font=f, fill=(255, 255, 255))
        y += 72
    x = (W - SHOT_W) // 2
    room = H - TOP - 40
    if h > room:  # a screen taller than the frame: its top goes
        crop = crop.crop((0, h - room, SHOT_W, h))
        h = room
    mask = Image.new("L", crop.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, crop.width - 1, crop.height - 1), RADIUS, fill=255)
    shadow = Image.new("L", (W, H), 0)
    ImageDraw.Draw(shadow).rounded_rectangle((x, TOP + 10, x + SHOT_W, TOP + 10 + h), RADIUS, fill=110)
    canvas = Image.composite(Image.new("RGB", (W, H), SHADOW), canvas, shadow.filter(ImageFilter.GaussianBlur(18)))
    canvas.paste(crop, (x, TOP), mask)
    PLAY.mkdir(parents=True, exist_ok=True)
    dest = PLAY / f"{number:02d}-{name}.png"
    canvas.save(dest, optimize=True)
    return dest


def site(raw, name):
    """The whole screen, status bar and all, 720×1280."""
    img = nine_by_sixteen(raw).resize((720, 1280), Image.LANCZOS)
    dest = SITE / f"{name}.webp"
    img.save(dest, "WEBP", quality=82, method=6)
    return dest


def guide(raw, name, lang, bars, area=None):
    """The screen without the status bar (or just [area], x1 y1 x2 y2), 720 px wide."""
    top, _ = bars
    img = raw.crop(area) if area else raw.crop((0, top, raw.width, raw.height))
    img = img.resize((720, round(img.height * 720 / img.width)), Image.LANCZOS)
    folder = GUIDES[lang]
    folder.mkdir(parents=True, exist_ok=True)
    dest = folder / f"{name}.webp"
    img.save(dest, "WEBP", quality=82, method=6)
    return dest
