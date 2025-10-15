package com.dnobretech.jarvistradutorbackend.service;

import java.util.List;

public interface ReviewService {
    int approveCorpora(List<Long> ids, String reviewer);
    int rejectCorpora(List<Long> ids, String reviewer);
    int approveBookpairs(List<Long> ids, String reviewer);
    int rejectBookpairs(List<Long> ids, String reviewer);
    int commitCorporaApproved();
    int commitBookpairsApproved();
}
