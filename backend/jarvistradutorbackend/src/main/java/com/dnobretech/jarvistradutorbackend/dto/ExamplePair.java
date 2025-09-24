package com.dnobretech.jarvistradutorbackend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExamplePair {
    private String src;
    private String tgt;
    private Double quality;
}