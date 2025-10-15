package com.dnobretech.jarvistradutorbackend.service.impl;

import com.dnobretech.jarvistradutorbackend.service.TMXImportService;
import com.dnobretech.jarvistradutorbackend.util.TextNormalizer;
import lombok.RequiredArgsConstructor;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.sql.DataSource;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class TMXImportServiceImpl implements TMXImportService {

    private final DataSource dataSource;
    private final TextNormalizer norm;
    private static final Pattern PLACEHOLDERS = Pattern.compile("(\\{[^}]+\\}|%s|%d|<[^>]+>|\\$\\{[^}]+\\})");

    @Value("${jarvis.tm.ratio-min:0.5}")
    private double ratioMin;
    @Value("${jarvis.tm.ratio-max:2.0}")
    private double ratioMax;

    public long importTmx(MultipartFile file, String srcLang, String tgtLang) throws Exception {
        final String srcL = normalizeLang(srcLang);
        final String tgtL = normalizeLang(tgtLang);

        // Conexão COPY
        Connection con = DataSourceUtils.getConnection(dataSource);
        CopyManager cm = con.unwrap(PGConnection.class).getCopyAPI();

        // Pipe: thread do COPY lê enquanto o parser escreve
        final PipedReader pr = new PipedReader(1 << 16); // 64KB buffer
        final PipedWriter pw = new PipedWriter(pr);

        // Lança thread do COPY (consumidor)
        var copyThread = new Thread(() -> {
            try (Reader r = pr) {
                cm.copyIn(
                        "COPY tm(src,tgt,lang_src,lang_tgt,quality) FROM STDIN WITH (FORMAT csv, HEADER true)",
                        r
                );
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "tmx-copy");
        copyThread.start();

        long rows = 0;
        try (InputStream raw = new BufferedInputStream(file.getInputStream());
             // Se .tmx.gz, troque por new GZIPInputStream(raw)
             Writer out = new BufferedWriter(pw, 1 << 16)) {

            // Cabeçalho CSV
            out.write("src,tgt,lang_src,lang_tgt,quality\n");

            XMLInputFactory f = XMLInputFactory.newFactory();
            // Segurança
            f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            try {
                f.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
            } catch (Exception ignore) {
            }
            // Performance
            f.setProperty(XMLInputFactory.IS_COALESCING, true); // junta textos adjacentes

            XMLStreamReader xr = f.createXMLStreamReader(raw, StandardCharsets.UTF_8.name());

            // Buffers por TU
            StringBuilder segSrc = null;
            StringBuilder segTgt = null;
            String curLang = null;
            boolean inSeg = false;

            long tus = 0; // contagem de TU processadas

            while (xr.hasNext()) {
                int ev = xr.next();

                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String name = xr.getLocalName();
                    if ("tu".equalsIgnoreCase(name)) {
                        segSrc = null;
                        segTgt = null;
                        curLang = null;
                    } else if ("tuv".equalsIgnoreCase(name)) {
                        curLang = normalizeLang(getAttr(xr, "xml:lang"));
                        if (curLang.isEmpty()) curLang = normalizeLang(getAttr(xr, "lang"));
                    } else if ("seg".equalsIgnoreCase(name)) {
                        inSeg = true;
                        // cria builder só quando vamos usar
                        if (curLang != null) {
                            if (isLangMatch(curLang, srcL) && segSrc == null) segSrc = new StringBuilder(128);
                            if (isLangMatch(curLang, tgtL) && segTgt == null) segTgt = new StringBuilder(128);
                        }
                    }
                } else if (ev == XMLStreamConstants.CHARACTERS) {
                    if (inSeg && curLang != null) {
                        CharSequence text = xr.getTextCharacters() != null ? xr.getText() : "";
                        if (text.length() > 0) {
                            if (isLangMatch(curLang, srcL) && segSrc != null) segSrc.append(text);
                            else if (isLangMatch(curLang, tgtL) && segTgt != null) segTgt.append(text);
                        }
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String name = xr.getLocalName();
                    if ("seg".equalsIgnoreCase(name)) {
                        inSeg = false;
                    } else if ("tu".equalsIgnoreCase(name)) {
                        tus++;
                        if (segSrc != null && segSrc.length() > 0 && segTgt != null && segTgt.length() > 0) {
                            String src = norm.normalize(maybeStrip(segSrc));
                            String tgt = norm.normalize(maybeStrip(segTgt));
                            if (!src.isBlank() && !tgt.isBlank()) {
                                double r = lengthRatio(src, tgt);
                                if (r >= ratioMin && r <= ratioMax && placeholdersPreserved(src, tgt)) {
                                    writeCsvLine(out, src, tgt, srcL, tgtL);
                                    rows++;
                                }
                            }
                        }
                        // limpa para o próximo TU
                        segSrc = null;
                        segTgt = null;
                        curLang = null;
                        inSeg = false;
                        if (tus % 100_000 == 0) {
                            // log simples de progresso
                            System.out.printf("TMX TU processadas: %,d | linhas válidas: %,d%n", tus, rows);
                        }
                    }
                }
            }
            xr.close();
        } finally {
            // finaliza e espera COPY concluir
            pw.close();
            copyThread.join();
            DataSourceUtils.releaseConnection(con, dataSource);
        }
        return rows;
    }

    // ---------- helpers de alto desempenho ----------

    private static String getAttr(XMLStreamReader xr, String qname) {
        if (qname == null) return null;
        // tenta qname com prefixo (xml:lang)
        for (int i = 0; i < xr.getAttributeCount(); i++) {
            String n = xr.getAttributeName(i).toString(); // inclui prefixo se houver
            if (qname.equalsIgnoreCase(n)) {
                return xr.getAttributeValue(i);
            }
        }
        // tenta só localName (lang)
        for (int i = 0; i < xr.getAttributeCount(); i++) {
            String n = xr.getAttributeLocalName(i);
            if (qname.equalsIgnoreCase(n)) {
                return xr.getAttributeValue(i);
            }
        }
        return null;
    }

    private static String normalizeLang(String s) {
        if (s == null) return "";
        return s.trim().replace('_', '-').toLowerCase(Locale.ROOT);
    }

    private static boolean isLangMatch(String a, String b) {
        if (a == null || b == null) return false;
        return a.equals(b) || a.startsWith(b + "-") || b.startsWith(a + "-");
    }

    // só strip tags se existir '<' (evita regex cara desnecessária)
    private static String maybeStrip(StringBuilder sb) {
        int idx = indexOfLt(sb);
        if (idx < 0) return sb.toString();
        // remove tags simples
        return sb.toString().replaceAll("<[^>]+>", "");
    }

    private static int indexOfLt(CharSequence cs) {
        for (int i = 0, n = cs.length(); i < n; i++) if (cs.charAt(i) == '<') return i;
        return -1;
    }

    private static void writeCsvLine(Writer out, String src, String tgt, String srcL, String tgtL) throws IOException {
        out.write('"');
        out.write(esc(src));
        out.write('"');
        out.write(',');
        out.write('"');
        out.write(esc(tgt));
        out.write('"');
        out.write(',');
        out.write('"');
        out.write(srcL);
        out.write('"');
        out.write(',');
        out.write('"');
        out.write(tgtL);
        out.write('"');
        out.write(',');
        // quality vazio
        out.write('\n');
    }

    private static String esc(String s) {
        return s.replace("\"", "\"\"");
    }

    public boolean placeholdersPreserved(String src, String tgt) {
        var ms = PLACEHOLDERS.matcher(src);
        while (ms.find()) {
            String ph = ms.group();
            if (!tgt.contains(ph)) return false;
        }
        return true;
    }

    private double lengthRatio(String src, String tgt) {
        double a = Math.max(1, src.length());
        double b = Math.max(1, tgt.length());
        return b / a;
    }
}
