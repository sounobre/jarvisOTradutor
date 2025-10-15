package com.dnobretech.jarvistradutorbackend.service;



import com.dnobretech.jarvistradutorbackend.dto.BookpairInboxRow;

import java.util.List;

public interface BookpairInboxService {
    List<BookpairInboxRow> list(String status, Long seriesId, Long bookId, String sourceTag, int page, int size);
    long count(String status, Long seriesId, Long bookId, String sourceTag);
    int approve(long id, String reviewer);
    int reject(long id, String reviewer, String reason);
    int consolidateApproved(); // move aprovados para TM/TM_OCCURRENCE/TM_EMBEDDINGS
}
