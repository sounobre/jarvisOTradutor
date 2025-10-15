// com.dnobretech.jarvistradutorbackend.epubimport.QualityFilter.java
package com.dnobretech.jarvistradutorbackend.epubimport;

import com.dnobretech.jarvistradutorbackend.util.TextNormalizer;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class QualityFilter {

    private final TextNormalizer norm;
    public QualityFilter(TextNormalizer norm){ this.norm = norm; }

    // {x}, %s, <tag>, ${x}
    private static final Pattern PLACEHOLDERS = Pattern.compile("(\\{[^}]+\\}|%s|%d|<[^>]+>|\\$\\{[^}]+\\})");

    public boolean placeholdersPreserved(String src, String tgt) {
        var ms = PLACEHOLDERS.matcher(src);
        while (ms.find()) {
            String ph = ms.group();
            if (!tgt.contains(ph)) return false;
        }
        return true;
    }

    public boolean isValid(String src, String tgt, double ratioMin, double ratioMax) {
        if (src == null || tgt == null) return false;
        String s = norm.normalize(src);
        String t = norm.normalize(tgt);
        if (s.isBlank() || t.isBlank()) return false;
        double r = lengthRatio(s, t);
        if (r < ratioMin || r > ratioMax) return false;
        return placeholdersPreserved(s, t);
    }

    public double score(String src, String tgt, double ratioMin, double ratioMax) {
        String s = norm.normalize(src);
        String t = norm.normalize(tgt);
        double r = lengthRatio(s, t);
        boolean ph = placeholdersPreserved(s, t);
        return qualityScore(r, ph, ratioMin, ratioMax);
    }

    public double lengthRatio(String src, String tgt) {
        double a = Math.max(1, src.length());
        double b = Math.max(1, tgt.length());
        return b / a;
    }

    public double qualityScore(double r, boolean placeholdersOk, double ratioMin, double ratioMax) {
        double rs;
        if (r <= ratioMin || r >= ratioMax) rs = 0.0;
        else if (r <= 1.0) rs = 1.0 - ((1.0 - r) / (1.0 - ratioMin));
        else rs = 1.0 - ((r - 1.0) / (ratioMax - 1.0));
        rs = Math.max(0.0, Math.min(1.0, rs));
        double ps = placeholdersOk ? 1.0 : 0.0;
        double q = 0.7 * rs + 0.3 * ps;
        return Math.round(q * 1000.0) / 1000.0;
    }
}
