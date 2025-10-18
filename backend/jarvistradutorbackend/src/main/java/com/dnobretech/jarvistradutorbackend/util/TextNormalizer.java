package com.dnobretech.jarvistradutorbackend.util;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.regex.Pattern;

@Component
public class TextNormalizer {
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");   // 1+ espaços
    private static final Pattern PLACEHOLDERS = Pattern.compile(          // {x}, %s, <tag>, etc
            "(\\{[^}]+\\}|%s|%d|<[^>]+>|\\$\\{[^}]+\\})");

    public String normalize(String s) {
        if (s == null) return "";
        String t = s.trim();                                                // tira espaços extremos
        t = Normalizer.normalize(t, Normalizer.Form.NFKC);                  // normaliza unicode
        t = MULTI_SPACE.matcher(t).replaceAll(" ");                         // colapsa espaços
        return t;
    }

    public String normalizeDialogue(String s) {
        if (s == null) return "";
        String t = normalize(s); // sua normalize atual (trim + NFKC + espaços)

        // ASPAS DUPLAS tipográficas -> ASCII
        t = t.replace('“','"').replace('”','"').replace('«','"').replace('»','"');
        // APÓSTROFOS tipográficos -> ASCII (NÃO REMOVER!)
        t = t.replace('’','\'').replace('‘','\'');

        // travessão U+2014 -> hífen (opcional, se quiser uniformizar)
        t = t.replace('\u2014', '-');

        // remover travessão/hífen líder típico de fala, mas **não** mexer em apóstrofos
        t = t.replaceAll("^(\\-|—)\\s*", "");

        // reticências: normalizar spacing
        t = t.replaceAll("\\s*\\.\\.\\.\\s*", " ... ");

        // colapsa espaços novamente
        t = t.replaceAll("\\s+", " ").trim();
        return t;
    }
}
