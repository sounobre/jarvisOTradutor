# worker/utils/epub_utils.py
from ebooklib import epub, ITEM_DOCUMENT
import os

def ensure_nav_ncx(book: epub.EpubBook):
    # NCX
    try: book.add_item(epub.EpubNcx())
    except Exception: pass

    # NAV -> garantir subdiretório
    try:
        nav = epub.EpubNav()
        if not getattr(nav, "file_name", None) or os.path.dirname(nav.file_name) == "":
            nav.file_name = "nav/nav.xhtml"
        book.add_item(nav)
    except Exception:
        pass

def rebuild_toc_flat(book: epub.EpubBook):
    links = []
    for doc in book.get_items_of_type(ITEM_DOCUMENT):
        href = getattr(doc, "file_name", None) or (doc.get_id() + ".xhtml")
        if not href:
            continue
        href = href.replace("\\", "/")
        title = getattr(doc, "title", None) or os.path.splitext(os.path.basename(href))[0]
        ident = doc.get_id() or os.path.splitext(os.path.basename(href))[0]
        links.append(epub.Link(href, title, ident))
    book.toc = tuple(links)

def rebuild_spine(book: epub.EpubBook):
    spine = ["nav"]
    for doc in book.get_items_of_type(ITEM_DOCUMENT):
        if getattr(doc, "file_name", None):
            spine.append(doc)
    book.spine = spine
