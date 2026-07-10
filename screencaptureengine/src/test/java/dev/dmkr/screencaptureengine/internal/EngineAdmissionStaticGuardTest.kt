package dev.dmkr.screencaptureengine.internal

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineAdmissionStaticGuardTest {
    @Test
    fun admissionHasExactPartialApiStateAndOpaqueTokenInventory() {
        assertEquals(emptyList<String>(), violations(Files.readString(admissionFile)))
    }

    @Test
    fun guardRejectsEveryAuthorityExpansionFamily() {
        val source = Files.readString(admissionFile)
        val mutations = mapOf(
            "extra top-level token" to source.replace(
                "internal class ActiveAdmission",
                "internal class ForgedAdmission\n\ninternal class ActiveAdmission",
            ),
            "wrong package" to source.replace(
                "package dev.dmkr.screencaptureengine.internal",
                "package dev.dmkr.screencaptureengine.internal.other",
            ),
            "added import" to source.replace(
                "package dev.dmkr.screencaptureengine.internal",
                "package dev.dmkr.screencaptureengine.internal\n\nimport kotlin.Unit",
            ),
            "top-level token extension" to source.replace(
                "internal class ActiveAdmission",
                "internal fun ActiveAdmission.copy() = this\n\ninternal class ActiveAdmission",
            ),
            "implicit-internal member" to source.replace(
                "    private sealed interface State",
                "    fun release() = Unit\n\n    private sealed interface State",
            ),
            "comment brace cannot hide member" to source.replace(
                "    private sealed interface State",
                "    // {\n    fun hiddenAuthority() = Unit\n    // }\n\n    private sealed interface State",
            ),
            "string brace cannot hide member" to source.replace(
                "    private sealed interface State",
                "    private val open = \"{\"\n    fun hiddenByString() = Unit\n" +
                        "    private val close = \"}\"\n\n    private sealed interface State",
            ),
            "char brace cannot hide member" to source.replace(
                "    private sealed interface State",
                "    private val open = '{'\n    fun hiddenByChar() = Unit\n" +
                        "    private val close = '}'\n\n    private sealed interface State",
            ),
            "extra state subtype" to source.replace(
                "        data object Poisoned : State",
                "        data object Reusable : State\n\n        data object Poisoned : State",
            ),
            "multiline property" to source.replace(
                "    private var state: State = State.Vacant",
                "    private val\n        extraAuthority = Any()\n    private var state: State = State.Vacant",
            ),
            "extra initializer" to source.replace(
                "    private sealed interface State",
                "    init { Unit }\n\n    private sealed interface State",
            ),
            "formatted vacant reset" to source.replaceFirst(
                "            state = State.Poisoned",
                "            state =\n                State.Vacant",
            ),
            "multiline constructor" to source.replace(
                "internal class ActiveAdmission",
                "internal class ActiveAdmission\ninternal constructor()",
            ),
            "supertype" to source.replace(
                "internal class ActiveAdmission",
                "internal class ActiveAdmission : Runnable",
            ),
            "token property" to source.replace(
                "internal class ActiveAdmission",
                "internal class ActiveAdmission {\n    val authority = Any()\n}",
            ),
            "data token" to source.replace(
                "internal class ActiveAdmission",
                "internal data class ActiveAdmission(val id: Long)",
            ),
            "value token" to source.replace(
                "internal class ActiveAdmission",
                "internal value class ActiveAdmission(val id: Long)",
            ),
            "copy hook" to tokenBodyMutation(source, "fun copy() = this"),
            "component hook" to tokenBodyMutation(source, "operator fun component1() = this"),
            "clone hook" to tokenBodyMutation(source, "fun clone() = this"),
            "equals hook" to tokenBodyMutation(source, "override fun equals(other: Any?) = false"),
            "hash hook" to tokenBodyMutation(source, "override fun hashCode() = 0"),
            "identity text hook" to tokenBodyMutation(source, "override fun toString() = \"active\""),
            "serialization hook" to tokenBodyMutation(source, "fun writeReplace(): Any = this"),
            "serialization interface" to source.replace(
                "internal class ActiveAdmission",
                "internal class ActiveAdmission : java.io.Serializable",
            ),
            "parcelable interface" to source.replace(
                "internal class ActiveAdmission",
                "internal class ActiveAdmission : android.os.Parcelable",
            ),
        )

        mutations.forEach { (family, mutated) ->
            val findings = violations(mutated)
            assertTrue("$family mutation escaped the guard", findings.isNotEmpty())
            if (family.endsWith("brace cannot hide member")) {
                assertTrue("$family hid the function inventory", "engine function inventory" in findings)
            }
        }
    }

    @Test
    fun dormantLeafHasNoExistingProductionConsumer() {
        val root = Path.of("src/main")
        val consumers = Files.walk(root).use { paths ->
            paths.filter {
                Files.isRegularFile(it) && (it.toString().endsWith(".kt") || it.toString().endsWith(".java"))
            }
                .filter { it != admissionFile }
                .filter { isAdmissionConsumer(Files.readString(it)) }
                .toList()
        }

        assertTrue("Unexpected admission wiring: $consumers", consumers.isEmpty())
        assertTrue(
            isAdmissionConsumer(
                "package other; import dev.dmkr.screencaptureengine.internal.EngineAdmission; " +
                    "class JavaConsumer { EngineAdmission admission; }",
            ),
        )
        assertTrue(
            isAdmissionConsumer(
                "package other\nimport dev.dmkr.screencaptureengine.internal.ActiveAdmission\n" +
                    "internal class KotlinConsumer(val admission: ActiveAdmission)",
            ),
        )
        assertFalse(isAdmissionConsumer("// EngineAdmission\nclass Documentation"))
        assertFalse(isAdmissionConsumer("class Literal { String value = \"RetiringAdmission\"; }"))
    }

    private fun violations(source: String): List<String> = buildList {
        val code = source.lexicalCode()
        val packages = packageDeclaration.findAll(code).map { it.groupValues[1] }.toList()
        if (packages != listOf(ADMISSION_PACKAGE)) add("package inventory")
        if (importDeclaration.containsMatchIn(code)) add("import inventory")
        val topLevelDeclarations = code.topLevelDeclarationSegments(topLevelDeclarationStart)
        val topLevelNames = topLevelDeclarations
            .mapNotNull { topLevelName.find(it)?.groupValues?.get(1) }
        if (topLevelDeclarations.size != 3 ||
            topLevelNames != listOf("ActiveAdmission", "RetiringAdmission", "EngineAdmission")
        ) {
            add("top-level declaration inventory")
        }
        val activeSegment = code.segmentBetween("internal class ActiveAdmission", "internal class RetiringAdmission")
        val retiringSegment = code.segmentBetween("internal class RetiringAdmission", "internal class EngineAdmission")
        if (activeSegment.compact() != "internal class ActiveAdmission") add("active token declaration")
        if (retiringSegment.compact() != "internal class RetiringAdmission") add("retiring token declaration")

        val engineBody = code.bodyAfter("internal class EngineAdmission")
        if (engineBody == null) {
            add("engine declaration")
            return@buildList
        }
        if (!Regex("(?m)^internal class EngineAdmission \\{$").containsMatchIn(code)) add("engine header")

        val stateBody = engineBody.bodyAfter("private sealed interface State")
        if (stateBody == null) {
            add("state declaration")
            return@buildList
        }
        if (!Regex("(?m)^\\s{4}private sealed interface State \\{$").containsMatchIn(engineBody)) {
            add("state header")
        }
        val stateNames = stateBody.topLevelDeclarationSegments(stateDeclarationStart)
            .mapNotNull { stateName.find(it)?.groupValues?.get(1) }
        if (stateNames != expectedStates) add("state subtype inventory")
        if (stateBody.compact() != expectedStateBody) add("state subtype shape")

        val stateStart = engineBody.indexOf("private sealed interface State")
        val stateOpen = engineBody.indexOf('{', stateStart)
        val stateEnd = engineBody.matchingBrace(stateOpen)
        val outerBody = engineBody.removeRange(stateStart, stateEnd + 1)
        val members = outerBody.topLevelDeclarationSegments(memberDeclarationStart)
        val properties = members.filter { propertyHeader.containsMatchIn(it.compact()) }
        val functions = members.filter { functionHeader.containsMatchIn(it.compact()) }
        if (members.size != properties.size + functions.size) add("extra engine member")
        if (functions.any { !it.compact().startsWith("internal fun ") }) add("function visibility")

        if (properties.mapNotNull { propertyName.find(it)?.groupValues?.get(1) } != listOf("lock", "state")) {
            add("engine property inventory")
        }
        if (!properties.getOrNull(0).orEmpty().compact().startsWith("private val lock = Any()")) add("lock shape")
        if (!properties.getOrNull(1).orEmpty().compact().startsWith("private var state: State = State.Vacant")) {
            add("state property shape")
        }
        val signatures = functions.map {
            functionSignature.find(it.compact())?.destructured?.let { match ->
                "${match.component1()}(${match.component2().compact()}):${match.component3().compact()}"
            } ?: "unparsed"
        }
        if (signatures != expectedFunctions) add("engine function inventory")

        tokenForbidden.forEach { forbidden ->
            if (forbidden.containsMatchIn(activeSegment) || forbidden.containsMatchIn(retiringSegment)) {
                add("token authority: ${forbidden.pattern}")
            }
        }
        productionForbidden.forEach { forbidden ->
            if (forbidden.containsMatchIn(code)) add("dependency/API: ${forbidden.pattern}")
        }
        if (Regex("state\\s*=\\s*\\(?\\s*State\\.Vacant\\s*\\)?").containsMatchIn(code)) add("reuse transition")
    }

    private fun String.bodyAfter(declaration: String): String? {
        val declarationStart = indexOf(declaration)
        if (declarationStart < 0) return null
        val open = indexOf('{', declarationStart)
        if (open < 0) return null
        val close = matchingBrace(open)
        return if (close > open) substring(open + 1, close) else null
    }

    private fun String.matchingBrace(open: Int): Int {
        var depth = 0
        for (index in open until length) {
            when (this[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return index
            }
        }
        return -1
    }

    private fun String.topLevelDeclarationSegments(start: Regex): List<String> {
        val starts = buildList {
            var offset = 0
            var depth = 0
            lineSequence().forEach { line ->
                if (depth == 0 && start.containsMatchIn(line)) add(offset)
                depth += line.count { it == '{' } - line.count { it == '}' }
                offset += line.length + 1
            }
        }
        return starts.mapIndexed { index, begin -> substring(begin, starts.getOrElse(index + 1) { length }) }
    }

    private fun String.segmentBetween(start: String, end: String): String {
        val begin = indexOf(start)
        if (begin < 0) return ""
        val finish = indexOf(end, begin + start.length)
        return if (finish < 0) "" else substring(begin, finish)
    }

    private companion object {
        val admissionFile: Path =
            Path.of("src/main/java/dev/dmkr/screencaptureengine/internal/EngineAdmission.kt")
        const val ADMISSION_PACKAGE = "dev.dmkr.screencaptureengine.internal"
        val packageDeclaration = Regex("(?m)^\\s*package\\s+([A-Za-z_][\\w.]*)\\s*$")
        val importDeclaration = Regex("(?m)^\\s*import\\s+")
        val admissionNames = Regex("\\b(?:EngineAdmission|ActiveAdmission|RetiringAdmission)\\b")
        val memberDeclarationStart = Regex(
            "^\\s*.*\\b(?:val|var|fun|class|object|interface|typealias|constructor|init)\\b",
        )
        val topLevelDeclarationStart = Regex(
            "^\\s*.*\\b(?:val|var|fun|class|object|interface|typealias|constructor|init)\\b",
        )
        val topLevelName = Regex("(?:class|object|interface|typealias)\\s+(\\w+)")
        val stateDeclarationStart = Regex("^\\s*.*\\b(?:class|object|interface|typealias)\\b")
        val propertyName = Regex("\\b(?:val|var)\\s+(\\w+)")
        val propertyHeader = Regex(
            "^(?:(?:public|private|protected|internal|lateinit|const|override)\\s+)*(?:val|var)\\b",
        )
        val functionHeader = Regex(
            "^(?:(?:public|private|protected|internal|suspend|operator|inline|tailrec|infix|" +
                    "external|override)\\s+)*fun\\b",
        )
        val functionSignature = Regex("\\bfun\\s+(\\w+)\\s*\\((.*?)\\)\\s*:\\s*([^=]+?)\\s*=")
        val stateName = Regex("(?:data object|class|object|interface|sealed class)\\s+(\\w+)")
        val expectedStates = listOf("Vacant", "Occupied", "Retiring", "Poisoned")
        val expectedFunctions = listOf(
            "acquire():ActiveAdmission?",
            "markRetiring(admission: ActiveAdmission):RetiringAdmission",
            "poison(admission: ActiveAdmission):Unit",
            "poison(admission: RetiringAdmission):Unit",
        )
        val expectedStateBody = """
            data object Vacant : State
            class Occupied( val admission: ActiveAdmission, ) : State
            class Retiring( val admission: RetiringAdmission, ) : State
            data object Poisoned : State
        """.compact()
        val tokenForbidden = listOf(
            Regex("\\b(?:val|var|fun|constructor)\\b"),
            Regex("\\b(?:data|value)\\s+class\\b"),
            Regex("\\b(?:equals|hashCode|toString|copy|component\\d+|clone)\\s*\\("),
            Regex("\\b(?:Serializable|Externalizable|Parcelable|Parcelize)\\b"),
            Regex("\\b(?:readObject|writeObject|readResolve|writeReplace)\\s*\\("),
            Regex(":"),
            Regex("[{}]"),
        )
        val productionForbidden = listOf(
            Regex("\\bandroid\\."),
            Regex("\\bkotlinx\\.coroutines\\b"),
            Regex("\\bjava\\.util\\.concurrent\\.atomic\\b"),
            Regex("\\bBoolean\\b"),
            Regex("\\(\\s*\\)\\s*->"),
            Regex("\\bcallback\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(?:reflection|java\\.lang\\.reflect|kotlin\\.reflect)\\b"),
            Regex("\\b(?:release|reset|clear|reuse)\\s*\\("),
            Regex("\\bScreenCapture\\w*\\b"),
            Regex("\\b\\w*Provider\\w*\\b"),
        )

        fun tokenBodyMutation(source: String, member: String): String = source.replace(
            "internal class ActiveAdmission",
            "internal class ActiveAdmission {\n    $member\n}",
        )

        fun isAdmissionConsumer(source: String): Boolean = admissionNames.containsMatchIn(source.lexicalCode())
    }
}

private fun String.compact(): String = replace(Regex("\\s+"), " ").trim()

private fun String.lexicalCode(): String {
    val code = StringBuilder(length)
    var index = 0
    var mode = LexicalMode.Code
    var blockDepth = 0

    fun mask(count: Int) {
        repeat(count) {
            val character = this@lexicalCode[index++]
            code.append(if (character == '\n' || character == '\r') character else ' ')
        }
    }

    while (index < length) {
        when (mode) {
            LexicalMode.Code -> when {
                startsWith("//", index) -> {
                    mask(2)
                    mode = LexicalMode.LineComment
                }

                startsWith("/*", index) -> {
                    mask(2)
                    blockDepth = 1
                    mode = LexicalMode.BlockComment
                }

                startsWith("\"\"\"", index) -> {
                    mask(3)
                    mode = LexicalMode.RawString
                }

                this[index] == '"' -> {
                    mask(1)
                    mode = LexicalMode.StringLiteral
                }

                this[index] == '\'' -> {
                    mask(1)
                    mode = LexicalMode.CharLiteral
                }

                else -> code.append(this[index++])
            }

            LexicalMode.LineComment -> {
                val lineEnd = this[index] == '\n' || this[index] == '\r'
                mask(1)
                if (lineEnd) mode = LexicalMode.Code
            }

            LexicalMode.BlockComment -> when {
                startsWith("/*", index) -> {
                    mask(2)
                    blockDepth++
                }

                startsWith("*/", index) -> {
                    mask(2)
                    if (--blockDepth == 0) mode = LexicalMode.Code
                }

                else -> mask(1)
            }

            LexicalMode.RawString -> {
                if (startsWith("\"\"\"", index)) {
                    mask(3)
                    mode = LexicalMode.Code
                } else {
                    mask(1)
                }
            }

            LexicalMode.StringLiteral,
            LexicalMode.CharLiteral -> {
                val closing = if (mode == LexicalMode.StringLiteral) '"' else '\''
                when {
                    this[index] == '\\' && index + 1 < length -> mask(2)
                    this[index] == closing -> {
                        mask(1)
                        mode = LexicalMode.Code
                    }

                    else -> mask(1)
                }
            }
        }
    }
    return code.toString()
}

@Suppress("unused") // Entries are consumed through the qualified state machine above.
private enum class LexicalMode {
    Code,
    LineComment,
    BlockComment,
    StringLiteral,
    RawString,
    CharLiteral,
}
