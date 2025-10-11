package com.dnobretech.jarvistradutorbackend.service.impl;

import com.dnobretech.jarvistradutorbackend.domain.Author;
import com.dnobretech.jarvistradutorbackend.domain.Book;
import com.dnobretech.jarvistradutorbackend.domain.Series;
import com.dnobretech.jarvistradutorbackend.dto.CatalogImportResult;
import com.dnobretech.jarvistradutorbackend.enums.BookType;
import com.dnobretech.jarvistradutorbackend.repository.AuthorRepository;
import com.dnobretech.jarvistradutorbackend.repository.BookRepository;
import com.dnobretech.jarvistradutorbackend.repository.SeriesRepository;
import com.dnobretech.jarvistradutorbackend.service.CatalogExcelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogExcelServiceImpl implements CatalogExcelService {

    private final SeriesRepository seriesRepo;
    private final BookRepository bookRepo;
    private final AuthorRepository authorRepo; // <-- ADICIONE

    // --------- IMPORT ---------
    @Transactional
    @Override
    public CatalogImportResult importExcel(MultipartFile file) throws IOException {
        try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
            Sheet sh = wb.getSheetAt(0);
            if (sh.getPhysicalNumberOfRows() < 2) {
                return new CatalogImportResult(0,0,0);
            }

            Map<String,Integer> col = headerIndex(sh.getRow(0));
            int created = 0, updated = 0, skipped = 0;

            for (int r = 1; r <= sh.getLastRowNum(); r++) {
                Row row = sh.getRow(r);
                if (row == null) {
                    continue;
                }

                String serieName   = str(row, col, "SerieOuUniverso");
                String authorName  = str(row, col, "Autor");
                String country     = str(row, col, "PaisAutor");
                String period      = str(row, col, "PeriodoAtivo");
                if (serieName == null || serieName.isBlank()) {
                    skipped++; continue;
                }

                // 1) resolve/cria AUTHOR
                Author author = resolveOrCreateAuthor(authorName, country, period);

                // 2) upsert SERIES por (name + author)
                Series series = seriesRepo.findByNameIgnoreCaseAndAuthor(serieName.trim(), author)
                        .orElseGet(() -> {
                            String slug = ensureUniqueSeriesSlug(makeSeriesBaseSlug(serieName, author.getName()));
                            return Series.builder()
                                    .name(serieName.trim())
                                    .slug(slug)
                                    .author(author)
                                    .createdAt(Instant.now())
                                    .updatedAt(Instant.now())
                                    .build();
                        });

                log.info("Série: " + series);
                // atualiza metadados (sem mexer em slug se já existir)
                series.setUpdatedAt(Instant.now());
                if (series.getId() == null) {
                    series = seriesRepo.save(series);
                }

                // 2) localizar / upsert Book
                String isbn = str(row, col, "ISBN13_BR");
                Optional<Book> opt = Optional.empty();

                String vol = str(row, col, "NumeroNaSerie");
                String originalEn = str(row, col, "TituloOriginalEN");
                if(originalEn.equalsIgnoreCase("Coraline")){
                    log.info("chegou");
                }
                if (opt.isEmpty() && vol != null && !series.getName().equalsIgnoreCase("standalone")) {
                    opt = bookRepo.findBySeriesAndVolumeNumber(series, vol);
                }

                if (opt.isEmpty() && originalEn != null && !originalEn.isBlank()){
                    opt = bookRepo.findBySeriesAndOriginalTitleEnIgnoreCase(series, originalEn);
                }

                Series sRef = series;
                Book b = opt.orElseGet(() -> Book.builder()
                        .series(sRef).createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build());

                // mapear campos
                b.setVolumeNumber(vol);
                b.setOriginalTitleEn(originalEn);
                b.setTitlePtBr(str(row, col, "TituloPTBR"));
                b.setType(parseType(str(row, col, "Tipo")));
                b.setYearOriginal(toInt(row, col, "AnoPublicacaoOriginal"));
                b.setYearBr(toInt(row, col, "AnoPublicacaoBR"));
                b.setPublisherBr(str(row, col, "EditoraBR"));
                b.setTranslatorBr(str(row, col, "TradutorBR"));
                b.setIsbn13Br(isbn);
                b.setDownloaded(toBool(row, col, "Baixado"));
                b.setPathEn(str(row, col, "Caminho versão em Inglês"));
                b.setPathPt(str(row, col, "Caminho versão em Português"));
                b.setPairsImported(toBool(row, col, "Já foi feita a importação dos pares"));
                b.setUpdatedAt(Instant.now());

                boolean isNew = (b.getId() == null);
                bookRepo.save(b);
                if (isNew) {
                    created++;
                } else{
                    updated++;
                }
            }
            return new CatalogImportResult(created, updated, skipped);
        }
    }

    // --------- EXPORT ---------
    @Transactional(readOnly = true)
    @Override
    public byte[] exportExcel() throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("Catalogo");
            int r = 0;
            Row header = sh.createRow(r++);
            writeHeader(header);

            List<Book> books = bookRepo.findAll();
            for (Book b : books) {
                Series s = b.getSeries();
                Author a = (s != null ? s.getAuthor() : null);

                Row row = sh.createRow(r++);
                write(row, 0,  a != null ? nvl(a.getName()) : "");
                write(row, 1,  a != null ? nvl(a.getCountry()) : "");
                write(row, 2,  a != null ? nvl(a.getActivePeriod()) : "");
                write(row, 3,  s != null ? nvl(s.getName()) : "");
                write(row, 4, b.getVolumeNumber());
                write(row, 5,  nvl(b.getOriginalTitleEn()));
                write(row, 6,  nvl(b.getTitlePtBr()));
                write(row, 7,  b.getType() != null ? b.getType().name() : "");
                writeNum(row, 8,  b.getYearOriginal());
                writeNum(row, 9,  b.getYearBr());
                write(row, 10, nvl(b.getPublisherBr()));
                write(row, 11, nvl(b.getTranslatorBr()));
                write(row, 12, nvl(b.getIsbn13Br()));
                writeBool(row,13, b.getDownloaded());
                write(row, 14, nvl(b.getPathEn()));
                write(row, 15, nvl(b.getPathPt()));
                writeBool(row,16, b.getPairsImported());
            }

            for (int i=0;i<17;i++) sh.autoSizeColumn(i);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(1<<20);
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    // ---------- helpers ----------
    private static Map<String,Integer> headerIndex(Row header) {
        Map<String,Integer> map = new HashMap<>();
        for (int i=0;i<header.getLastCellNum();i++) {
            String h = header.getCell(i) != null ? header.getCell(i).getStringCellValue() : null;
            if (h != null) map.put(h.trim(), i);
        }
        // validação mínima
        List<String> required = List.of("TituloOriginalEN","TituloPTBR");
        for (String r : required)
            if (!map.containsKey(r))
                throw new IllegalArgumentException("Header ausente: " + r);
        return map;
    }

    private static String str(Row r, Map<String,Integer> col, String h) {
        Integer i = col.get(h); if (i==null) return null;
        Cell c = r.getCell(i); if (c==null) return null;
        c.setCellType(CellType.STRING);
        String s = c.getStringCellValue();
        return s != null ? s.trim() : null;
    }
    private static Integer toInt(Row r, Map<String,Integer> col, String h) {
        String s = str(r,col,h);
        try {
            return (s==null||s.isBlank()) ? null : Integer.parseInt(s.replaceAll("[^0-9-]",""));
        } catch (Exception e){
            return null;
        }
    }
    private static Boolean toBool(Row r, Map<String,Integer> col, String h) {
        String s = Optional.ofNullable(str(r,col,h)).orElse("").toLowerCase(Locale.ROOT);
        return switch (s) {
            case "1","true","t","sim","s","y","yes" -> true;
            case "0","false","f","nao","não","n","no" -> false;
            default -> null;
        };
    }
    private static BookType parseType(String s) {
        if (s==null) return null;
        try { return BookType.valueOf(s.trim().toUpperCase(Locale.ROOT)); }
        catch (Exception e){ return BookType.OUTRO; }
    }
    private static String nvl(String s){ return s==null? "" : s; }

    private static void writeHeader(Row r){
        String[] h = {
                "Autor","PaisAutor","PeriodoAtivo","SerieOuUniverso","NumeroNaSerie",
                "TituloOriginalEN","TituloPTBR","Tipo","AnoPublicacaoOriginal","AnoPublicacaoBR",
                "EditoraBR","TradutorBR","ISBN13_BR","Baixado","Caminho versão em Inglês",
                "Caminho versão em Português","Já foi feita a importação dos pares"
        };
        for (int i=0;i<h.length;i++) r.createCell(i).setCellValue(h[i]);
    }
    private static void write(Row r, int i, String v){ r.createCell(i, CellType.STRING).setCellValue(v==null? "" : v); }
    private static void writeNum(Row r, int i, Integer v){ if (v==null) write(r,i,""); else r.createCell(i, CellType.NUMERIC).setCellValue(v); }
    private static void writeBool(Row r, int i, Boolean v){ write(r,i, v==null? "" : (v? "true":"false")); }

    // ========== SLUG HELPERS (série + autor) ==========

    /** garante unicidade consultando o repo; se colidir, acrescenta sufixo estável curto (hash) */
    private String ensureUniqueSeriesSlug(String base) {
        if (!seriesRepo.existsBySlug(base)) return base;
        String suff = shortHash(base);
        String candidate = base + "-" + suff;
        if (!seriesRepo.existsBySlug(candidate)) return candidate;

        // fallback incremental raríssimo
        int i = 2;
        while (seriesRepo.existsBySlug(candidate + "-" + i)) i++;
        return candidate + "-" + i;
    }




    private static String shortHash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(input.getBytes(StandardCharsets.UTF_8));
            // 8 hex chars é suficiente para diferenciar casos raros
            StringBuilder sb = new StringBuilder(8);
            for (int i=0;i<4;i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) { return "x"; }
    }

    //----------------------

    private Author resolveOrCreateAuthor(String name, String country, String period) {
        if (name == null || name.isBlank()) {
            // Autor desconhecido (se quiser, crie "Desconhecido")
            name = "Autor Desconhecido";
        }
        // slug básico
        String slug = slugify(name);
        // tenta por slug (ou por name+country se preferir)
        String finalName = name;
        return authorRepo.findBySlug(slug).orElseGet(() -> {
            var a = Author.builder()
                    .name(finalName.trim())
                    .slug(ensureUniqueAuthorSlug(slug))
                    .country(nvlEmptyToNull(country))
                    .activePeriod(nvlEmptyToNull(period))
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
            return authorRepo.save(a);
        });
    }

    private String ensureUniqueAuthorSlug(String base) {
        if (!authorRepo.existsBySlug(base)) return base;
        int i = 2;
        while (authorRepo.existsBySlug(base + "-" + i)) i++;
        return base + "-" + i;
    }

    private static String makeSeriesBaseSlug(String seriesName, String authorName) {
        String s = slugify(seriesName);
        String a = slugify(authorName);
        return a.isBlank() ? s : (s + "--" + a);
    }

    private static String slugify(String x) {
        if (x == null) return "";
        String s = java.text.Normalizer.normalize(x, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}","")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+","-")
                .replaceAll("(^-|-$)","");
        return s;
    }

    private static String nvlEmptyToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
    // (demais helpers permanecem)

}
