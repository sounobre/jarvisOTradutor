// com.dnobretech.jarvistradutorbackend.epubimport.LengthAligner.java
package com.dnobretech.jarvistradutorbackend.epubimport;

import com.dnobretech.jarvistradutorbackend.dto.AlignedPair;
import com.dnobretech.jarvistradutorbackend.dto.Block;
import com.dnobretech.jarvistradutorbackend.dto.Pair;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class LengthAligner implements Aligner {

    @Override
    public List<AlignedPair> align(List<Block> src, List<Block> tgt) {
        int n = Math.min(src.size(), tgt.size());
        List<AlignedPair> out = new ArrayList<>(n);
        for (int i=0;i<n;i++) {
            out.add(new AlignedPair(
                    src.get(i).text(), src.get(i),
                    tgt.get(i).text(), tgt.get(i)
            ));
        }
        return out;
    }
}
