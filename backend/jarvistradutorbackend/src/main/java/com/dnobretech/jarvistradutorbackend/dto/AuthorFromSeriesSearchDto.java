package com.dnobretech.jarvistradutorbackend.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AuthorFromSeriesSearchDto {
    private Long id;
    private String name;
}
