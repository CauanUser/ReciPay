"""Armazenamento dos comprovantes (fotos e PDFs) na pasta de dados do programa."""

import hashlib
import io
import shutil
import uuid
from pathlib import Path

from PIL import Image, ImageOps, UnidentifiedImageError

from recipay import config

EXTENSOES_IMAGEM = {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif"}
EXTENSOES_ACEITAS = EXTENSOES_IMAGEM | {".pdf"}
TAMANHO_MAXIMO = 15 * 1024 * 1024  # 15 MB por arquivo
LADO_MAXIMO = 1600  # px (D-04)
QUALIDADE_JPEG = 85
FILTRO_ARQUIVOS = "Comprovantes (*.jpg *.jpeg *.png *.webp *.bmp *.gif *.pdf)"


def hash_arquivo(caminho: Path) -> str:
    h = hashlib.sha256()
    with open(caminho, "rb") as f:
        for bloco in iter(lambda: f.read(1024 * 1024), b""):
            h.update(bloco)
    return h.hexdigest()


def caminho_absoluto(relativo: str) -> Path:
    """Os caminhos no banco são relativos à pasta de dados (facilita o backup)."""
    return config.pasta_dados() / relativo


def _reduzir_imagem(origem: Path) -> bytes:
    try:
        with Image.open(origem) as img:
            img = ImageOps.exif_transpose(img)  # corrige fotos de celular giradas
            if img.mode not in ("RGB", "L"):
                fundo = Image.new("RGB", img.size, "white")
                img = img.convert("RGBA")
                fundo.paste(img, mask=img.getchannel("A"))
                img = fundo
            img.thumbnail((LADO_MAXIMO, LADO_MAXIMO))
            saida = io.BytesIO()
            img.save(saida, "JPEG", quality=QUALIDADE_JPEG, optimize=True)
            return saida.getvalue()
    except (UnidentifiedImageError, OSError) as e:
        raise ValueError("O arquivo de imagem está danificado ou não é uma imagem válida.") from e


def guardar(origem: Path | str) -> tuple[str, str]:
    """Valida e copia o comprovante para a pasta do programa.

    Fotos são reduzidas e convertidas em JPEG; PDFs são mantidos como estão.
    Devolve (caminho relativo, hash SHA-256).
    """
    origem = Path(origem)
    if not origem.is_file():
        raise ValueError("Arquivo de comprovante não encontrado.")
    extensao = origem.suffix.lower()
    if extensao not in EXTENSOES_ACEITAS:
        raise ValueError("Formato não aceito. Use foto (JPG, PNG) ou PDF.")
    if origem.stat().st_size > TAMANHO_MAXIMO:
        raise ValueError("Comprovante maior que 15 MB.")

    nome = uuid.uuid4().hex
    destino_pasta = config.pasta_comprovantes()
    if extensao == ".pdf":
        with open(origem, "rb") as f:
            if f.read(5) != b"%PDF-":
                raise ValueError("O arquivo PDF está danificado ou não é um PDF.")
        destino = destino_pasta / f"{nome}.pdf"
        shutil.copyfile(origem, destino)
    else:
        destino = destino_pasta / f"{nome}.jpg"
        destino.write_bytes(_reduzir_imagem(origem))

    relativo = destino.relative_to(config.pasta_dados()).as_posix()
    return relativo, hash_arquivo(destino)


def e_pdf(relativo: str) -> bool:
    return relativo.lower().endswith(".pdf")
