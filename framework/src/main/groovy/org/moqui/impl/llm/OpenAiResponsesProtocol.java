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
 * Backward-compatible class name for existing llm-facade profiles.
 * Prefer {@link OpenResponsesProtocol} for new profiles.
 */
@Deprecated
public class OpenAiResponsesProtocol extends OpenResponsesProtocol {
    @Override public String getName() { return "openai-responses"; }

    @Override
    public java.util.Set<org.moqui.llm.LlmProtocol.Capability> getCapabilities() {
        java.util.EnumSet<org.moqui.llm.LlmProtocol.Capability> capabilities =
                java.util.EnumSet.copyOf(super.getCapabilities());
        capabilities.add(org.moqui.llm.LlmProtocol.Capability.RETRIEVE);
        capabilities.add(org.moqui.llm.LlmProtocol.Capability.CANCEL);
        capabilities.add(org.moqui.llm.LlmProtocol.Capability.LIST_INPUT_ITEMS);
        capabilities.add(org.moqui.llm.LlmProtocol.Capability.COUNT_INPUT_TOKENS);
        return capabilities;
    }

    public static java.util.Map<String, Object> buildRequestBody(ProtocolRequest request) {
        return OpenResponsesCodec.buildRequestBody(request, true);
    }

    /** OpenAI's Responses endpoint ends a stream after the terminal event and does not send [DONE]. */
    @Override
    protected boolean requireSseSentinel() { return false; }

    @Override
    protected java.util.Map<String, Object> buildRequestBodyForProtocol(ProtocolRequest request) {
        return OpenResponsesCodec.buildRequestBody(request, true);
    }

    public static class StreamAssembler extends OpenResponsesProtocol.StreamAssembler {
        public StreamAssembler(ProtocolRequest request) { super(request); }
    }
}
