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

import java.util.LinkedHashMap;
import java.util.Map;

public final class LlmResponseEvent {
    public String type;
    public Long sequenceNumber;
    public String responseId;
    public String itemId;
    public Integer outputIndex;
    public Integer contentIndex;
    public boolean terminal;
    public Map<String, Object> payload;

    public LlmResponseEvent copy() {
        LlmResponseEvent copy = new LlmResponseEvent();
        copy.type = type;
        copy.sequenceNumber = sequenceNumber;
        copy.responseId = responseId;
        copy.itemId = itemId;
        copy.outputIndex = outputIndex;
        copy.contentIndex = contentIndex;
        copy.terminal = terminal;
        if (payload != null) copy.payload = new LinkedHashMap<>(payload);
        return copy;
    }
}
