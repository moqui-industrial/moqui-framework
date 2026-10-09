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

public final class LlmContentAnnotation {
    public String type;
    public String url;
    public String title;
    public Integer startIndex;
    public Integer endIndex;
    public Map<String, Object> payload;

    public LlmContentAnnotation copy() {
        LlmContentAnnotation copy = new LlmContentAnnotation();
        copy.type = type;
        copy.url = url;
        copy.title = title;
        copy.startIndex = startIndex;
        copy.endIndex = endIndex;
        if (payload != null) copy.payload = new LinkedHashMap<>(payload);
        return copy;
    }
}
