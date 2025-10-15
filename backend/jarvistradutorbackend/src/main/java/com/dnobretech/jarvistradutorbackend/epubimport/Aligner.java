// com.dnobretech.jarvistradutorbackend.epubimport.Aligner.java
package com.dnobretech.jarvistradutorbackend.epubimport;

import com.dnobretech.jarvistradutorbackend.dto.AlignedPair;
import com.dnobretech.jarvistradutorbackend.dto.Block;
import com.dnobretech.jarvistradutorbackend.dto.Pair;
import java.util.List;

public interface Aligner {
    List<AlignedPair> align(List<Block> src, List<Block> tgt);
}
