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
package org.moqui.impl.llm

/** What a diagnostic view of stored content may show of a media location. */
final class MediaView {
    private MediaView() { }

    /**
     * What a diagnostic view may show of a media location: a data URI as its type and size, a URL without user info,
     * query and fragment (a signed URL carries its credential there), any other reference shortened. The value itself
     * is available only to the replay of its own conversation.
     */
    static String safeMedia(String value) {
        if (value == null) return null
        if (value.startsWith('data:')) {
            int comma = value.indexOf(',')
            String head = comma > 0 ? value.substring(0, comma) : value.take(40)
            return head + ',(' + Math.max(value.length() - (comma + 1), 0) + ' characters not shown)'
        }
        try {
            URI uri = new URI(value)
            if (uri.scheme != null && uri.rawAuthority != null) {
                String host = uri.host ?: ''
                return uri.scheme + '://' + host + (uri.port > 0 ? ':' + uri.port : '') + (uri.rawPath ?: '') + (uri.rawQuery != null || uri.rawFragment != null ? '?(query not shown)' : '')
            }
        } catch (URISyntaxException ignored) { }
        value.length() > 200 ? value.take(200) + '...' : value
    }
}
