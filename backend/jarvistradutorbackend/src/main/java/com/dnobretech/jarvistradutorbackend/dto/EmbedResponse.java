package com.dnobretech.jarvistradutorbackend.dto;

import lombok.ToString;

import java.util.List;


public record EmbedResponse(String model, int dims, List<double[]> vectors) {
}
