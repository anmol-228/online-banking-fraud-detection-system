package com.sepro.obfds.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/** A page of results, kept free of Spring Data internals so the API shape stays stable. */
public record PageResponse<T>(
        List<T> content, int page, int size, long totalElements, int totalPages, boolean last) {

    public static <S, T> PageResponse<T> from(Page<S> page, List<T> mappedContent) {
        return new PageResponse<>(
                mappedContent,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast());
    }
}
