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
package org.moqui.impl.llm;

/**
 * Chat Completions for a provider that ends its stream when the last choice is finished, without sending the [DONE]
 * sentinel. Configure it explicitly on the profile that needs it (protocol="org.moqui.impl.llm.OpenAiCompatEofProtocol").
 * A stream that ends before a finish_reason is still an error.
 */
public class OpenAiCompatEofProtocol extends OpenAiCompatProtocol {
    @Override public String getName() { return "openai-compat-eof"; }
    @Override protected boolean requireDoneSentinel() { return false; }
}
