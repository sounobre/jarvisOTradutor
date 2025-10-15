package com.dnobretech.jarvistradutorbackend.epubimport;

import com.dnobretech.jarvistradutorbackend.dto.Block;
import nl.siegmann.epublib.domain.Book;
import nl.siegmann.epublib.domain.Resource;
import nl.siegmann.epublib.epub.EpubReader;
import nl.siegmann.epublib.service.MediatypeService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Component
public class EpubExtractor {

    public List<Block> extractBlocks(MultipartFile file, String level) throws Exception {
        Book book;
        try (InputStream in = file.getInputStream()) {
            book = new EpubReader().readEpub(in);
        }

        List<Block> out = new ArrayList<>();
        int spineIdx = 0;

        for (Resource res : book.getContents()) {
            if (res.getMediaType() != MediatypeService.XHTML) {
                spineIdx++;
                continue;
            }

            String html = new String(
                    res.getData(),
                    res.getInputEncoding() != null ? Charset.forName(res.getInputEncoding()) : StandardCharsets.UTF_8
            );
            String chapterTitle = safeTitle(book, spineIdx);

            // Coleta parágrafos + divs “de texto”
            Document doc = Jsoup.parse(html);
            List<String> blocks = new ArrayList<>();

            for (Element el : doc.select("p, li")) {
                var t = clean(el.text());
                if (!t.isBlank()) blocks.add(t);
            }
            for (Element el : doc.select("div:not(:has(p,li))")) {
                var t = clean(el.text());
                if (!t.isBlank()) blocks.add(t);
            }

            // paragraph-level
            if (!"sentence".equalsIgnoreCase(level)) {
                for (int b = 0; b < blocks.size(); b++) {
                    String text = blocks.get(b);
                    if (!looksFooter(text)) {
                        out.add(new Block(text, spineIdx, b, 0, chapterTitle));
                    }
                }
            } else {
                // sentence-level
                int bIdx = 0;
                for (String p : blocks) {
                    if (looksFooter(p)) {
                        bIdx++;
                        continue;
                    }
                    String[] sents = p.split("(?<=[.!?…])\\s+");
                    int sIdx = 0;
                    for (String s : sents) {
                        String t = clean(s);
                        if (!t.isBlank()) {
                            out.add(new Block(t, spineIdx, bIdx, sIdx, chapterTitle));
                            sIdx++;
                        }
                    }
                    bIdx++;
                }
            }

            spineIdx++;
        }

        // pós-processo: remove duplicado consecutivo e ruído (mantém posição!)
        return dedupeConsecutive(out);
    }

    private static List<Block> dedupeConsecutive(List<Block> in) {
        List<Block> out = new ArrayList<>(in.size());
        Block prev = null;
        for (Block b : in) {
            if (prev == null || !b.text().equals(prev.text())) out.add(b);
            prev = b;
        }
        return out;
    }

    private static String clean(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    private static boolean looksFooter(String s) {
        String noDigits = s.replaceAll("\\d", "");
        return s.length() <= 5 || noDigits.isBlank();
    }

    private static String safeTitle(Book book, int spineIdx) {
        try {
            var tocRefs = book.getTableOfContents().getTocReferences();
            if (tocRefs != null && spineIdx >= 0 && spineIdx < tocRefs.size()) {
                String t = tocRefs.get(spineIdx).getTitle();
                if (t != null && !t.isBlank()) return t.trim();
            }
        } catch (Exception ignore) {
        }
        return "spine-" + spineIdx;
    }


    private List<String> htmlToBlocks(String html, String level) {
        Document doc = Jsoup.parse(html);
        List<String> out = new ArrayList<>();
        for (Element el : doc.select("p, li")) {
            String t = clean(el.text());
            if (!t.isBlank()) out.add(t);
        }
        for (Element el : doc.select("div:not(:has(p,li))")) {
            String t = clean(el.text());
            if (!t.isBlank()) out.add(t);
        }
        if ("sentence".equalsIgnoreCase(level)) {
            List<String> sent = new ArrayList<>();
            for (String p : out) {
                for (String s : p.split("(?<=[.!?…])\\s+")) {
                    s = s.trim();
                    if (!s.isBlank()) sent.add(s);
                }
            }
            return sent;
        }
        return out;
    }


    private List<String> postProcessBlocks(List<String> in) {
        List<String> tmp = new ArrayList<>(in.size());
        String prev = null;
        for (String s : in) {
            if (!s.equals(prev)) tmp.add(s);
            prev = s;
        }
        List<String> out = new ArrayList<>(tmp.size());
        for (String s : tmp) {
            String noDigits = s.replaceAll("\\d", "");
            boolean looksFooter = s.length() <= 5 || noDigits.isBlank();
            if (!looksFooter) out.add(s);
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>(out);
        return new ArrayList<>(seen);
    }
}
