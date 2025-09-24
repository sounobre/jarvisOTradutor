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

    public double lengthRatio(String src, String tgt) {
        double a = Math.max(1, normalize(src).length());                    // evita divisão por 0
        double b = Math.max(1, normalize(tgt).length());
        return b / a;                                                       // len(tgt)/len(src)
    }

    public boolean placeholdersPreserved(String src, String tgt) {
        var ms = PLACEHOLDERS.matcher(src);
        while (ms.find()) {                                                 // para cada placeholder no src
            String ph = ms.group();
            if (!tgt.contains(ph)) return false;                              // exige que apareça no tgt
        }
        return true;
    }
}
