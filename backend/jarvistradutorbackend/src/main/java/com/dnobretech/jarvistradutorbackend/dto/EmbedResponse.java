package com.dnobretech.jarvistradutorbackend.dto;

import java.util.List;

public record EmbedResponse(String model, int dims, List<double[]> vectors) {
}
