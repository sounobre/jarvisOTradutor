# worker/services/translate_epub.py
from ebooklib import epub, ITEM_DOCUMENT
from bs4 import BeautifulSoup
import pysbd, tempfile, os, shutil, logging
from .models import get_model, load_model
from ..core.config import settings
from ..utils.epub_utils import ensure_nav_ncx, rebuild_toc_flat, rebuild_spine

log = logging.getLogger("jarvis-tradutor")

def translate_epub_file(src_path, dst_path):
    tok, model = get_model()
    seg = pysbd.Segmenter(language="en", clean=True)
    book = epub.read_epub(src_path)

    def batched(xs, n):
        for i in range(0, len(xs), n):
            yield xs[i:i+n]

    for item in list(book.get_items_of_type(ITEM_DOCUMENT)):
        try:
            soup = BeautifulSoup(item.get_content(), "lxml")
        except Exception:
            soup = BeautifulSoup(item.get_content(), "html.parser")

        text_nodes = list(soup.find_all(string=True))
        for node in text_nodes:
            original = node.strip()
            if not original:
                continue
            sents = seg.segment(original)
            translated = []
            for chunk in batched(sents, 12):
                enc = tok(chunk, return_tensors="pt", padding=True, truncation=True)
                out = model.generate(**enc, max_length=512)
                translated.extend(tok.batch_decode(out, skip_special_tokens=True, clean_up_tokenization_spaces=True))
            node.replace_with(" ".join(translated))

        item.set_content(str(soup).encode("utf-8"))

    # robustez NAV/TOC/SPINE
    ensure_nav_ncx(book)
    rebuild_toc_flat(book)
    rebuild_spine(book)

    tmp_fd, tmp_path = tempfile.mkstemp(suffix=".epub")
    os.close(tmp_fd)
    epub.write_epub(tmp_path, book)
    size = os.path.getsize(tmp_path)
    if size <= 0:
        raise RuntimeError("EPUB gerado com tamanho 0")
    shutil.move(tmp_path, dst_path)
    return size
