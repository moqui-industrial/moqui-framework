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

import org.moqui.util.SystemBinding;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Services an administrator has chosen to offer to turns of the gateway as tools, by exact name. The setting
 * {@code llm_gateway_service_tools} is a list of {@code tool=service} pairs separated by commas, for example
 * {@code lookup_part=mantle.product.ProductServices.get#Part}. It is empty by default, and then no service is offered; a
 * browser can only ask for a tool by the name in this list, never name a service. The service runs as the person, under the
 * authorization checks of the person, like any service tool.
 */
public final class GatewayServiceTools {
    private GatewayServiceTools() { }

    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");
    private static final java.util.Set<String> BUILT_IN = new java.util.HashSet<>(java.util.Arrays.asList(
            "request", "write_ui", "browse", "run_service", "find_skill", "enter_sim", "pin", "find_basic"));

    /** Tool name to service name, in the order of the setting; a malformed pair or a name of a built-in tool is left out. */
    public static Map<String, String> configured() {
        String raw = SystemBinding.getPropOrEnv("llm_gateway_service_tools");
        if (raw == null || raw.isBlank()) return Collections.emptyMap();
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : raw.split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String tool = pair.substring(0, eq).trim();
            String service = pair.substring(eq + 1).trim();
            if (service.isEmpty() || !NAME.matcher(tool).matches() || BUILT_IN.contains(tool)) continue;
            out.put(tool, service);
        }
        return out;
    }
}
