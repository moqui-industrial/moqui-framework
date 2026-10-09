/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LlmContentLogprob {
    public String token;
    public Double logprob;
    public List<Integer> bytes;
    public List<Map<String, Object>> topLogprobs;

    public LlmContentLogprob copy() {
        LlmContentLogprob copy = new LlmContentLogprob();
        copy.token = token;
        copy.logprob = logprob;
        if (bytes != null) copy.bytes = new ArrayList<>(bytes);
        if (topLogprobs != null) {
            copy.topLogprobs = new ArrayList<>();
            for (Map<String, Object> value : topLogprobs)
                copy.topLogprobs.add(value != null ? new LinkedHashMap<>(value) : null);
        }
        return copy;
    }
}
