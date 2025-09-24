package com.dnobretech.jarvistradutorbackend.dto;

import java.util.List;

public record ResumeResult(
        long processedLines,
        long newOffset,
        long totalCopied,
        List<ExamplePair> examples              // <- NOVO
) {}
