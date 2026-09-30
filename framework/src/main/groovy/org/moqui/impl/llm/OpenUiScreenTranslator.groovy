package org.moqui.impl.llm

import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.script.ScriptEngine
import javax.script.ScriptEngineManager

/**
 * OpenUiScreenTranslator - Level A design
 * ==========================================
 * Translates an OpenUI Lang document (the same language rendered client-side by
 * Assist.qvue / AssistOpenUi.js / AssistOpenUiLibrary.js in moqui-runtime) into a
 * native Moqui Screen XML fragment (MNode tree), so the result can be rendered
 * server-side through the normal ScreenDefinition / FormInstance pipeline instead
 * of the Vue+Quasar client renderer.
 *
 * DESIGN DECISION (agreed): Level A only. This translator NEVER emits
 * <entity-find>, <service-call>, or <script> elements, and never accepts an
 * entity-name/service-name chosen by the model. The only "action" primitive is
 * the same one the client already uses: a same-origin HTTP request to an
 * *already-existing* developer-authored /apps/... transition. All real
 * entity-find/service-call logic lives in that pre-existing transition's own
 * XML, written by a developer, and is subject to the normal Moqui artifact
 * security (AT_ENTITY / AT_SERVICE / AT_XML_SCREEN_TRANS) exactly as it is today.
 * The model only chooses WHICH existing path to call and WHAT parameter values
 * to send - never what the transition does.
 *
 * This keeps the server-side translator's security surface IDENTICAL to what the
 * client-side OpenUI interpreter already allows today - it does not add any new
 * capability, it just changes where the same, already-validated request/render
 * step happens (server instead of browser).
 *
 * PARSING NOTE: OpenUI Lang's grammar/parser ("lang-core") is currently a JS UMD
 * module, used client-side only (see moqui-industrial/moqui-runtime,
 * base-component/webroot/screen/webroot/js/assist/AssistOpenUi.js). Rather than
 * re-implementing that grammar in Groovy (a second implementation to keep in
 * sync forever, and a likely source of parser-divergence bugs/security gaps),
 * this translator re-uses the *same* lang-core module server-side via a
 * javax.script.ScriptEngine (Moqui's ResourceFacadeImpl already uses
 * ScriptEngineManager for pluggable script engines - see
 * framework/src/main/groovy/org/moqui/impl/context/ResourceFacadeImpl.groovy -
 * so wiring a JS engine such as GraalJS in is consistent with existing
 * infrastructure, not a new pattern). The JS engine only ever PARSES text into a
 * JSON AST; it never executes network calls or touches Moqui's ExecutionContext.
 * That keeps the trust boundary narrow: the JS sandbox's only output is inert
 * data (a Map/List tree), which this class then walks to build MNode.
 *
 * STATUS: skeleton / first draft. The request-validation section below is a
 * direct, verified port of AssistOpenUi.js's validateActionPath() and
 * callRequest() rules (same file, ~lines 18-30 and ~198-234 as of the llm-client
 * branch merged with the unpoly commits). The AST-walking section
 * (buildScreenXml) is a stub pending the ScriptEngine wiring decision - see TODOs.
 */
class OpenUiScreenTranslator {
    protected final static Logger logger = LoggerFactory.getLogger(OpenUiScreenTranslator.class)

    /** Statement kinds the translator will ever emit as a data-binding action.
     *  Nothing else is legal; anything else found in the AST is a translation error. */
    static enum ActionKind { QUERY, MUTATION }

    // ============================================================
    // 1) Request validation - ported 1:1 from AssistOpenUi.js
    //    (validateActionPath + the request-shape rules in callRequest)
    // ============================================================

    /** Mirrors AssistOpenUi.js validateActionPath(path). Returns an error message,
     *  or null if the path is acceptable. Kept as close as possible to the
     *  client-side wording/logic on purpose: this must reject exactly what the
     *  client already rejects, so behavior is identical whichever side renders. */
    static String validateActionPath(String path) {
        if (!path || !path.startsWith('/')) return 'path must start with /'
        if (path.contains('://') || path.startsWith('//')) return 'path must not contain a host'
        if (path.contains('..')) return 'path must not contain ..'
        String lower = path.toLowerCase()
        if (lower.contains('javascript:') || lower.contains('data:')) return 'path scheme not allowed'
        return null
    }

    /**
     * Mirrors AssistOpenUi.js callRequest()'s shape rules, adapted to the
     * server-rendering context (there is no "agent mode" toggle here in the
     * sense of a chat loop, but the equivalent distinction is: is this screen
     * being rendered for an interactive, authenticated user request right now
     * (mutations allowed, same as "Script mode" clicking a Button), or is it
     * being (re)built/warmed/pre-rendered outside of a live user request, e.g.
     * background regeneration or cache warm-up (GET/HEAD only, same as the
     * client's "agent mode" restriction on POST)).
     *
     * @param method HTTP method the query/mutation statement declares
     * @param path the path from the query/mutation statement
     * @param isLiveInteractiveRequest true only when rendering in direct response
     *        to the current authenticated user's own request
     * @throws IllegalArgumentException with the same messages the client uses,
     *         so errors surfaced to a developer are recognizable either way
     */
    static void validateRequest(String method, String path, boolean isLiveInteractiveRequest) {
        String pathErr = validateActionPath(path)
        if (pathErr) throw new IllegalArgumentException(pathErr)

        // Screens (HTML) are never valid data sources; only the JSON /apps/ path space is.
        if (path.startsWith('/qapps')) throw new IllegalArgumentException('Use /apps (not /qapps) for JSON')

        String upperMethod = (method ?: 'GET').toUpperCase()
        if (upperMethod != 'GET' && upperMethod != 'HEAD' && !isLiveInteractiveRequest) {
            throw new IllegalArgumentException('Mutations are not run outside of a live interactive request')
        }
    }

    /**
     * Mirrors the client's HTML-response guard (`isHtmlBody`): if whatever the
     * target transition returns looks like an HTML document rather than JSON,
     * treat it as a hard error rather than trying to render it as data. This
     * defends against a target path that turns out to resolve to a screen
     * rather than a JSON-emitting transition (misconfiguration, not something
     * the model can control, but still worth guarding server-side exactly like
     * the client does).
     */
    static boolean looksLikeHtml(String body, String contentType) {
        if (contentType && contentType.toLowerCase().contains('html')) return true
        if (!body) return false
        String head = body.trim()
        if (!head) return false
        String headLower = (head.length() > 32 ? head.substring(0, 32) : head).toLowerCase()
        return headLower.startsWith('<!doctype') || headLower.startsWith('<html') ||
                headLower.startsWith('<body') || headLower.startsWith('<head')
    }

    // ============================================================
    // 2) OpenUI Lang parsing (TODO: wire a ScriptEngine running lang-core)
    // ============================================================

    /**
     * TODO: this is the piece that needs a decision before it can be finished.
     * Options, roughly in order of preference:
     *   a) Register a JS ScriptEngine (e.g. GraalJS) with Moqui's existing
     *      ScriptEngineManager (see ResourceFacadeImpl), load the SAME lang-core
     *      UMD bundle already shipped to the browser, and call its parse
     *      function from Groovy to get the AST as a JSON-compatible structure
     *      (Map/List). This guarantees the server parses OpenUI Lang text
     *      identically to the client - no second grammar to maintain.
     *   b) Re-implement the grammar natively in Groovy/ANTLR. More work, a
     *      second implementation to keep in sync with every future widget-
     *      library change, and a plausible source of subtle parser-divergence
     *      security bugs (text that parses one way client-side and another
     *      way server-side). Not recommended unless (a) proves impractical.
     *
     * Whichever is chosen, the JS engine (if used) must run with NO Java
     * interop enabled (GraalJS: HostAccess.NONE / no Java class access from
     * script) - its only job is turning text into inert data, it should never
     * be able to reach the JVM's classpath, filesystem, or network.
     */
    Map parseOpenUiLang(String langText) {
        throw new UnsupportedOperationException(
            'OpenUI Lang parsing not yet wired - see TODO above; needs a ScriptEngine ' +
            'decision before this can run against real lang-core output.')
        // Sketch of (a), once a js engine is registered:
        // ScriptEngine engine = new ScriptEngineManager().getEngineByName('graal.js')
        // engine.eval(langCoreSource) // the same UMD bundle served to the browser
        // Object astJson = ((Invocable) engine).invokeFunction('parseOpenUiLangToJson', langText)
        // return (Map) new groovy.json.JsonSlurper().parseText((String) astJson)
    }

    // ============================================================
    // 3) AST -> Screen XML (MNode) - structure only, pending (2)
    // ============================================================

    /**
     * Walks the parsed AST's root Stack (and queryStatements/mutationStatements)
     * and returns a <screen> MNode fragment suitable for embedding, containing
     * only elements from the existing xml-form-3.xsd / screen-3.xsd widget
     * vocabulary (per the "no new widgets" constraint) - never <script>, never
     * a widget outside that vocabulary.
     *
     * Every query/mutation statement's method+path is run through
     * validateRequest() above BEFORE anything is emitted; a statement that
     * fails validation aborts the whole translation rather than being silently
     * dropped, so a bad/blocked statement can never be swapped for something
     * the model didn't actually ask for.
     */
    MNode buildScreenXml(Map ast, boolean isLiveInteractiveRequest) {
        MNode screenNode = new MNode("screen", null)
        MNode widgetsNode = screenNode.append("widgets", null)

        List<Map> queryStatements = (List<Map>) ast.get('queryStatements') ?: []
        List<Map> mutationStatements = (List<Map>) ast.get('mutationStatements') ?: []
        (queryStatements + mutationStatements).each { Map stmt ->
            String method = (String) stmt.get('method') ?: 'GET'
            String path = (String) stmt.get('path')
            validateRequest(method, path, isLiveInteractiveRequest) // throws on anything not allowed
        }

        Map root = (Map) ast.get('root')
        if (root != null) appendNode(widgetsNode, root)

        return screenNode
    }

    /** TODO: one branch per node typeName from AssistOpenUiLibrary.spec.json
     *  (Stack, Card, TextContent, Table, Col, Form, Input, Select, Link,
     *  BarChart/LineChart/AreaChart/PieChart, MarkDownRenderer, Mermaid, ...),
     *  each mapping to the closest existing xml-form-3.xsd / screen-3.xsd
     *  element. Left as a stub here since it's a long, mostly-mechanical
     *  mapping table best reviewed widget-by-widget against real screens. */
    protected void appendNode(MNode parent, Map node) {
        throw new UnsupportedOperationException('Node-by-node mapping table not yet written - next step.')
    }
}
