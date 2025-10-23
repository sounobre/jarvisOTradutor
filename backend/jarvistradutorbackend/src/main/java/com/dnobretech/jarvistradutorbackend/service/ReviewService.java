package com.dnobretech.jarvistradutorbackend.service;

import java.util.List;

public interface ReviewService {
    int approveCorpora(List<Long> ids, String reviewer);
    int rejectCorpora(List<Long> ids, String reviewer);
    int approveBookpairs(List<Long> ids, String reviewer);
    int rejectBookpairs(List<Long> ids, String reviewer);
    int commitCorporaApproved();
    int commitBookpairsApproved();
    /**
     * Revisa até 'limit' pares com final_score <= finalScoreMax e rev_status NULL/pending,
     * gravando rev_status ('good' | 'suspect' | 'bad'), rev_score (0..1), rev_comment,
     * rev_model ('gpt-j-6B') e rev_at (now()).
     * @return quantidade revisada
     */
    int reviewPairs(double maxFinalScore, int limit);
}
