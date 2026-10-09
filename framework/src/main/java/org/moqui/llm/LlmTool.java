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

import org.moqui.context.ExecutionContext;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;

public interface LlmTool {
    String getName();
    String getDescription();
    Map<String, Object> getParametersSchema();
    Execution getExecution();

    /**
     * Function tools: when non-null, {@code strict} is sent with exactly this value; null (the default) omits it. A strict
     * tool must have a parameters schema that meets the structured-output rules: every object closed
     * ({@code additionalProperties:false}) and all its properties required. A schema that does not is refused before
     * anything is sent; the caller's schema is never rewritten.
     */
    default Boolean getStrict() { return null; }
    enum Execution { SERVER, CLIENT }

    Object execute(Map<String, Object> arguments, ExecutionContext ec);

    default Map<String, Object> enrichForClient(Map<String, Object> arguments, ExecutionContext ec) {
        return arguments;
    }

    interface Factory {
        LlmTool create();
    }

    static LlmTool request() { return create("org.moqui.impl.llm.RequestTool"); }
    static LlmTool writeUi() { return create("org.moqui.impl.llm.WriteUiTool"); }
    static LlmTool browse() { return create("org.moqui.impl.llm.BrowseTool"); }
    static LlmTool runService() { return create("org.moqui.impl.llm.RunServiceTool"); }
    static LlmTool findSkill() { return create("org.moqui.impl.llm.FindSkillTool"); }
    static LlmTool enterSim() { return create("org.moqui.impl.llm.EnterSimTool"); }
    static LlmTool pin() { return create("org.moqui.impl.llm.PinTool"); }
    static LlmTool findBasic() { return create("org.moqui.impl.llm.FindBasicTool"); }
    static LlmTool service(String serviceName) {
        return create("org.moqui.impl.llm.ServiceCallTool", new Class<?>[] { String.class, String.class },
                serviceName, null);
    }
    static LlmTool service(String serviceName, String functionName) {
        return create("org.moqui.impl.llm.ServiceCallTool", new Class<?>[] { String.class, String.class },
                serviceName, functionName);
    }
    static LlmTool client(String name, String description, Map<String, Object> jsonSchema) {
        return create("org.moqui.impl.llm.ClientPassThroughTool",
                new Class<?>[] { String.class, String.class, Map.class }, name, description, jsonSchema);
    }
    /** A client tool whose function definition is sent with the given strict flag (null omits it). */
    static LlmTool client(String name, String description, Map<String, Object> jsonSchema, Boolean strict) {
        return create("org.moqui.impl.llm.ClientPassThroughTool",
                new Class<?>[] { String.class, String.class, Map.class, Boolean.class }, name, description, jsonSchema, strict);
    }

    /** Impl classes live under src/main/groovy and are not visible to javac. */
    private static LlmTool create(String className) {
        return create(className, new Class<?>[0]);
    }
    private static LlmTool create(String className, Class<?>[] types, Object... args) {
        try {
            Class<?> cls = Class.forName(className);
            return (LlmTool) cls.getDeclaredConstructor(types).newInstance(args);
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            if (c instanceof RuntimeException) throw (RuntimeException) c;
            throw new RuntimeException(c);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Could not create " + className, e);
        }
    }
}
