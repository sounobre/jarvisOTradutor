from ebooklib import epub, ITEM_DOCUMENT
from bs4 import BeautifulSoup
from transformers import MarianMTModel, MarianTokenizer
import pysbd
import logging, os, tempfile, shutil

logger = logging.getLogger("jarvis-tradutor")

def translate_epub(
    src_path: str,
    dst_path: str,
    model_name: str = "Helsinki-NLP/opus-mt-tc-big-en-pt",
    batch_size: int = 12,
    tok=None,
    model=None
):
    logger.info("Iniciando tradução do EPUB: %s", src_path)
    try:
        # lazy-load
        if tok is None or model is None:
            tok = MarianTokenizer.from_pretrained(model_name)
            model = MarianMTModel.from_pretrained(model_name)

        # garante pasta de saída
        out_dir = os.path.dirname(os.path.abspath(dst_path))
        os.makedirs(out_dir, exist_ok=True)

        # carrega epub
        book = epub.read_epub(src_path)
        seg = pysbd.Segmenter(language="en", clean=True)

        def batched(xs, n):
            for i in range(0, len(xs), n):
                yield xs[i:i+n]

        # ---- NORMALIZA ARQUIVOS DOS DOCUMENTOS (sempre dentro de "Text/")
        docs = list(book.get_items_of_type(ITEM_DOCUMENT))
        logger.info("Total de documentos XHTML: %d", len(docs))
        for doc in docs:
            did = getattr(doc, "id", None) or doc.get_id()
            fn = getattr(doc, "file_name", None)
            # se não tiver file_name, crie um em Text/<id>.xhtml
            if not fn:
                doc.file_name = f"Text/{did or 'doc'}.xhtml"
            else:
                # joga pra dentro de Text/ se estiver na raiz
                if "/" not in fn and "\\" not in fn:
                    doc.file_name = f"Text/{fn}"

        # ---- TRADUZ CONTEÚDO
        for item in docs:
            logger.info("Traduzindo doc id=%s file=%s", item.get_id(), item.file_name)
            # usar lxml se disponível (melhor para XHTML)
            try:
                soup = BeautifulSoup(item.get_content(), "lxml")
            except Exception:
                soup = BeautifulSoup(item.get_content(), "html.parser")

            text_nodes = list(soup.find_all(string=True))
            logger.info("Trechos de texto encontrados: %d", len(text_nodes))

            for node in text_nodes:
                original = (node or "").strip()
                if not original:
                    continue
                sents = seg.segment(original)
                translated = []
                for chunk in batched(sents, batch_size):
                    enc = tok(chunk, return_tensors="pt", padding=True, truncation=True)
                    gen = model.generate(**enc, max_length=512)
                    translated.extend(
                        tok.batch_decode(gen, skip_special_tokens=True, clean_up_tokenization_spaces=True)
                    )
                node.replace_with(" ".join(translated))

            item.set_content(str(soup).encode("utf-8"))

        # ---- GARANTE NAV/NCX (NAV dentro de Text/ para evitar ValueError em relpath)
        # NAV
        nav = None
        for it in book.get_items():
            if isinstance(it, epub.EpubNav):
                nav = it
                break
        if nav is None:
            nav = epub.EpubNav()
            book.add_item(nav)
        nav.file_name = "Text/nav.xhtml"  # <- chave do conserto

        # NCX
        ncx = None
        for it in book.get_items():
            if isinstance(it, epub.EpubNcx):
                ncx = it
                break
        if ncx is None:
            ncx = epub.EpubNcx()
            book.add_item(ncx)
        # ncx.file_name pode ficar na raiz
        if not getattr(ncx, "file_name", None):
            ncx.file_name = "toc.ncx"

        # ---- RECONSTRÓI TOC PLANO COM LINKS VÁLIDOS
        links = []
        for doc in book.get_items_of_type(ITEM_DOCUMENT):
            did = getattr(doc, "id", None) or doc.get_id() or os.path.splitext(os.path.basename(doc.file_name))[0]
            title = getattr(doc, "title", None) or did
            href = doc.file_name  # sempre algo tipo "Text/xxx.xhtml"
            # segurança extra
            if not href:
                href = f"Text/{did}.xhtml"
                doc.file_name = href
            links.append(epub.Link(href, title, did))
        book.toc = tuple(links)

        # ---- DEFINE SPINE
        book.spine = ["nav"] + list(book.get_items_of_type(ITEM_DOCUMENT))

        # ---- ESCREVE EM TEMP E MOVE
        tmp_fd, tmp_path = tempfile.mkstemp(suffix=".epub")
        os.close(tmp_fd)
        logger.info("Gravando EPUB temporário: %s", tmp_path)
        epub.write_epub(tmp_path, book)

        size = os.path.getsize(tmp_path)
        if size <= 0:
            raise RuntimeError("EPUB gerado com tamanho 0")
        shutil.move(tmp_path, dst_path)
        logger.info("Arquivo escrito com sucesso (%d bytes): %s", size, dst_path)

    except Exception:
        logger.exception("Falha durante a tradução/salvamento do EPUB")
        raise
