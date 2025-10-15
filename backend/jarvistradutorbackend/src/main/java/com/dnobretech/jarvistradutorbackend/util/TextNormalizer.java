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
}
