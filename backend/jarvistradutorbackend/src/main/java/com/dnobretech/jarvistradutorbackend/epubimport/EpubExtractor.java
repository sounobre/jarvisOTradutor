package com.dnobretech.jarvistradutorbackend.epubimport;

import com.dnobretech.jarvistradutorbackend.dto.Block;
import lombok.extern.slf4j.Slf4j;
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
import java.util.regex.Pattern;

import static java.util.regex.Pattern.compile;

@Slf4j
@Component
public class EpubExtractor {

    private static final java.util.regex.Pattern EN_SPEECH_VERB =
            compile("\\b(said|asked|looked|replied|whispered|shouted|murmured|muttered|yelled|cried)\\b", Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern PT_SPEECH_VERB =
            compile("\\b(disse|perguntou|olhou|respondeu|sussurrou|gritou|murmurou|resmungou|berrou|exclamou)\\b", Pattern.CASE_INSENSITIVE);


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

            log.info("EpubExtractor: \n");
            log.info("level: " + level);
            log.info("\n");

            blocks = glueDialogueNarration(blocks);
            blocks = splitDialogueIfMixed(blocks);

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
        if (s == null) return null;

        // Normaliza aspas, apóstrofos e travessões
        s = s
                .replace('\u2018', '\'')   // ‘
                .replace('\u2019', '\'')   // ’
                .replace('\u201C', '"')    // “
                .replace('\u201D', '"')    // ”
                .replace('\u2013', '-')    // –
                .replace('\u2014', '—');   // — (mantenho o em-dash)

        // Limpa espaços duplicados
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

    private static List<String> glueDialogueNarration(List<String> in){
        List<String> out = new ArrayList<>(in.size());
        for (int i=0;i<in.size();i++){
            String cur = in.get(i);
            String next = (i+1<in.size()? in.get(i+1) : null);

            boolean nextIsSpeech = next != null && looksSpeechLead(next);
            boolean curIsShortNarr = looksShortNarration(cur);

            if (curIsShortNarr && nextIsSpeech) {
                out.add(cur + " " + next);
                i++; // consome o próximo
            } else {
                out.add(cur);
            }
        }
        return out;
    }

    private static boolean looksShortNarration(String s){
        if (s == null) return false;
        String t = s.trim();
        if (t.length() > 80) return false;
        String lower = t.toLowerCase();
        return EN_SPEECH_VERB.matcher(lower).find() || PT_SPEECH_VERB.matcher(lower).find();
    }

    private static final String QUOTES = "\"“”«»";
    private static boolean hasQuote(String s) {
        if (s == null) return false;
        for (int i=0;i<QUOTES.length();i++){
            if (s.indexOf(QUOTES.charAt(i)) >= 0) return true;
        }
        return false;
    }

    // fala típica PT começa com travessão/hífen + espaço
    private static boolean looksSpeechLead(String s){
        if (s == null) return false;
        String t = s.strip();
        return t.startsWith("—") || t.startsWith("-") || t.startsWith("\"") || t.startsWith("“");
    }

    // heurística: “parágrafo misto EN” = tem ao menos 1 frase antes e depois de uma abertura de aspa
    private static boolean hasDoubleQuote(String s) {
        if (s == null) return false;
        for (int i=0;i<QUOTES.length();i++){
            if (s.indexOf(QUOTES.charAt(i)) >= 0) return true;
        }
        return false;
    }

    private static List<String> splitDialogueIfMixed(List<String> in){
        List<String> out = new ArrayList<>(in.size() + 8);
        for (String p : in) {
            String t = p == null ? "" : p.trim();
            if (t.isBlank()) { out.add(p); continue; }

            // fala "pura" (travessão/aspas duplas no início) → não divide
            if (looksSpeechLead(t)) { out.add(p); continue; }

            // só tenta split se houver ASPAS DUPLAS (não apóstrofos)
            if (!hasDoubleQuote(t)) { out.add(p); continue; }

            int idx = indexOfFirstSpeechQuoteStart(t);
            if (idx <= 0) { out.add(p); continue; }

            String before = t.substring(0, idx).trim();
            String after  = t.substring(idx).trim();

            // Exigir material narrativo razoável antes e fala razoável depois
            if (before.length() >= 8 && after.length() >= 4) {
                out.add(before);
                out.add(after);
            } else {
                out.add(p);
            }
        }
        return out;
    }


    private static int indexOfFirstSpeechQuoteStart(String t) {
        int best = Integer.MAX_VALUE;
        for (int i = 0; i < QUOTES.length(); i++) {
            char q = QUOTES.charAt(i);
            int k = t.indexOf(q);
            while (k >= 0) {
                // Heurística: a aspa abre fala se for início de string OU precedida por espaço/pontuação.
                // E NÃO deve estar no meio de uma palavra (contração não usa aspas duplas de qualquer forma).
                boolean leftBoundary = (k == 0) || Character.isWhitespace(t.charAt(k - 1)) ||
                        ".!?…—-;:()[]{}".indexOf(t.charAt(k - 1)) >= 0;
                if (leftBoundary) {
                    best = Math.min(best, k);
                    break;
                }
                k = t.indexOf(q, k + 1);
            }
        }
        return best == Integer.MAX_VALUE ? -1 : best;
    }


}
