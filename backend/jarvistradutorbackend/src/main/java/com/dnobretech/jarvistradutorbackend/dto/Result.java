package com.dnobretech.jarvistradutorbackend.dto;

import java.util.List;

public record Result(long inserted, long skipped, double avgQuality, int chapters, List<ExamplePair> examples ) {
}
