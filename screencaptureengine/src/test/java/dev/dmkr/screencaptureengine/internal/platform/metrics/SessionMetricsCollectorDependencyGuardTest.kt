package dev.dmkr.screencaptureengine.internal.platform.metrics

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMetricsCollectorDependencyGuardTest {
    @Test
    fun packageImportsAndConsumersKeepTheLeafClosed() {
        val productionRoot = Path.of("src/main/java")
        val production = Files.walk(productionRoot).use { paths ->
            paths.iterator().asSequence()
                .filter { path -> path.toString().endsWith(".kt") || path.toString().endsWith(".java") }
                .associateWith { path -> Files.readString(path) }
        }
        val packageFiles = production
            .filterKeys { path -> path.toString().endsWith(".kt") }
            .filterValues { source ->
                codeOnly(source).lineSequence().any { line -> line.trim() == "package $METRICS_PACKAGE" }
            }
        assertEquals(expectedPackageFiles, packageFiles.keys.map { it.fileName.toString() }.toSet())
        val leafFiles = packageFiles.filterKeys { path -> path.fileName.toString() !in oldPathExclusions }
        assertTrue(isExactLeafInventory(leafFiles.keys.map { it.fileName.toString() }.toSet()))
        val combinedCode = leafFiles.values.joinToString("\n", transform = ::codeOnly)

        assertEquals(allowedImports, leafFiles.values.flatMap(::imports).toSet())
        assertFalse(Regex("\\btypealias\\b").containsMatchIn(combinedCode))
        assertFalse(imports(combinedCode).any { declaration -> declaration.contains("*") || " as " in declaration })
        assertFalse(Regex("\\bpublic\\b").containsMatchIn(combinedCode))
        forbidden.forEach { token -> assertFalse("Forbidden code token: $token", combinedCode.contains(token)) }
        assertFalse(Regex("\\b(close|dispose)\\s*\\(").containsMatchIn(combinedCode))
        assertEquals(1, Regex("provider!!\\.metrics\\b").findAll(combinedCode).count())
        assertEquals(1, Regex("\\.collect\\s*\\{").findAll(combinedCode).count())

        val consumers = production
            .filterKeys { path -> path !in leafFiles.keys }
            .filterValues { source -> productionConsumer.containsMatchIn(codeOnly(source)) }
        assertTrue("Dormant leaf gained a production consumer: ${consumers.keys}", consumers.isEmpty())
    }

    @Test
    fun factsTagsProofAndSinkHaveExactShapes() {
        val root = Path.of("src/main/java/dev/dmkr/screencaptureengine/internal/platform/metrics")
        val fact = Files.readString(root.resolve("SessionMetricsFact.kt"))
        val collector = Files.readString(root.resolve("SessionMetricsCollector.kt"))
        val collectorCode = codeOnly(collector)

        assertEquals(factInventory, directFactSubtypeIdentities(fact))
        assertEquals(factInventory, directFactSubtypeIdentities(fact + collector))
        assertTrue(directFactSubtypes(collector).isEmpty())
        assertEquals(expectedTagShape, constructorShape(fact, "SessionMetricsFactTag"))
        assertEquals(expectedProofShape, constructorShape(fact, "SessionMetricsBarrierProof"))
        assertEquals(listOf("SessionMetricsFactSink"), funInterfaces(fact + collector))
        assertTrue(
            codeOnly(fact).contains(
                "internal fun interface SessionMetricsFactSink {\n    fun accept(fact: SessionMetricsFact)\n}",
            ),
        )
        listOf(
            "provider!!.metrics",
            "collection.collect",
            "collectionReturned && barrierProved",
            "sourceReferencesRetained",
            "sourceReleaseCount",
        ).forEach { invariant ->
            assertTrue("Missing collector invariant: $invariant", collectorCode.contains(invariant))
        }
    }

    @Test
    fun mutationsExerciseEveryGuardRouteAndCommentsDoNotTriggerIt() {
        val root = Path.of("src/main/java/dev/dmkr/screencaptureengine/internal/platform/metrics")
        val fact = Files.readString(root.resolve("SessionMetricsFact.kt"))
        val collector = Files.readString(root.resolve("SessionMetricsCollector.kt"))
        val oldObservation = Files.readString(root.resolve("CaptureMetricsObservation.kt"))

        val rogue = "\n@Deprecated(\"fixture\")\nprivate class Rogue : SessionMetricsFact"
        assertTrue(directFactSubtypeIdentities(fact + rogue) != factInventory)
        val nestedDuplicate = "\nobject Other\n{\n private class Available : SessionMetricsFact\n}"
        assertTrue("$METRICS_PACKAGE.Other.Available" in directFactSubtypeIdentities(fact + nestedDuplicate))
        val multilineSupertypes = """
            class Multi :
                Runnable,
                SessionMetricsFact,
                AutoCloseable
        """.trimIndent()
        assertTrue("$METRICS_PACKAGE.Multi" in directFactSubtypeIdentities("$fact\n$multilineSupertypes"))
        val genericMention = "class GenericMention : Wrapper<SessionMetricsFact>"
        assertFalse("$METRICS_PACKAGE.GenericMention" in directFactSubtypeIdentities("$fact\n$genericMention"))
        assertTrue(imports("$fact\n   import dev.example.*").toSet() != allowedImports)
        assertTrue(imports("$fact\n\timport dev.example.Value as Alias").toSet() != allowedImports)
        assertTrue(Regex("\\btypealias\\b").containsMatchIn(codeOnly("$fact\n  typealias Alias = String")))
        assertTrue(Regex("\\bpublic\\b").containsMatchIn(codeOnly("$fact\n @Ann public class PublicLeak")))
        assertTrue(funInterfaces("$fact\ninternal fun interface SecondSink").size != 1)
        assertFalse(isExactLeafInventory(expectedLeafFiles + "MovedHelper.kt"))

        val nullableTag = fact.replace("val session: SessionIdentity", "private var session: SessionIdentity?")
        assertTrue(constructorShape(nullableTag, "SessionMetricsFactTag") != expectedTagShape)
        val renamedProof = fact.replace(
            "val attachment: ControllerMetricsAttachmentIdentity,\n)",
            "val wrongAttachment: ControllerOperationIdentity?,\n)",
        )
        assertTrue(constructorShape(renamedProof, "SessionMetricsBarrierProof") != expectedProofShape)

        assertTrue(productionConsumer.containsMatchIn(codeOnly("class Moved { val fact: SessionMetricsFact? = null }")))
        assertFalse(productionConsumer.containsMatchIn(codeOnly(oldObservation)))
        assertTrue(
            productionConsumer.containsMatchIn(
                codeOnly("$oldObservation\nprivate val leakedTag: SessionMetricsFactTag? = null"),
            ),
        )
        assertTrue(
            productionConsumer.containsMatchIn(
                codeOnly(
                    "package example; import $METRICS_PACKAGE.SessionMetricsFact; " +
                            "class JavaConsumer { SessionMetricsCollector collector; }",
                ),
            ),
        )
        assertFalse(productionConsumer.containsMatchIn(codeOnly("// SessionMetricsFact is only documentation")))
        assertFalse(
            codeOnly("/* SessionController PlatformFailure close() */")
                .let { code -> forbidden.any(code::contains) },
        )
        assertFalse(codeOnly("val text = \"SessionController close()\"").contains("SessionController"))
        assertFalse(codeOnly("/* outer /* SessionController */ PlatformFailure */").contains("SessionController"))
        assertFalse(codeOnly("val raw = \"\"\"SessionController\"\"\"").contains("SessionController"))
        assertFalse(codeOnly("val marker = 'S'").contains("'S'"))
        assertFalse(codeOnly("val escaped = \"before \\\" SessionController\"").contains("SessionController"))
        assertTrue(codeOnly("val controller: SessionController? = null").contains("SessionController"))
        assertTrue(Regex("\\b(close|dispose)\\s*\\(").containsMatchIn(codeOnly("fun close() = Unit")))
        assertEquals(2, Regex("provider!!\\.metrics\\b").findAll(codeOnly("$collector\nprovider!!.metrics")).count())
        assertEquals(2, Regex("\\.collect\\s*\\{").findAll(codeOnly("$collector\nflow.collect {")).count())
        assertFalse(
            codeOnly(collector.replace("sourceReleaseCount", "releaseCountRemoved"))
                .contains("sourceReleaseCount"),
        )
    }

    private fun imports(source: String): List<String> =
        codeOnly(source).lineSequence()
            .map { line -> line.trim() }
            .filter { line -> line.startsWith("import ") }
            .map { line -> line.removePrefix("import ") }
            .toList()

    private fun directFactSubtypes(source: String): Set<String> =
        directFactSubtypeIdentities(source).map { identity -> identity.substringAfterLast('.') }.toSet()

    private fun directFactSubtypeIdentities(source: String): Set<String> {
        val code = codeOnly(source)
        val packageName = Regex("(?m)^\\s*package\\s+([A-Za-z_][\\w.]*)")
            .find(code)?.groupValues?.get(1) ?: error("Missing package declaration")
        val declarations = declarationStarts.findAll(code).map { match ->
            declarationHeader(code, match.range.first, match.range.last + 1, match.groupValues[1])
        }.toList()
        val scopes = declarations.mapNotNull { declaration ->
            declaration.bodyStart?.let { bodyStart ->
                DeclarationScope(declaration, bodyStart, matchingBrace(code, bodyStart))
            }
        }
        val identities = mutableMapOf<Int, String>()
        declarations.sortedBy(DeclarationHeader::start).forEach { declaration ->
            val parent = scopes
                .filter { scope -> scope.bodyStart < declaration.start && declaration.start < scope.bodyEnd }
                .maxByOrNull(DeclarationScope::bodyStart)
            identities[declaration.start] = parent?.let { scope ->
                "${identities.getValue(scope.declaration.start)}.${declaration.name}"
            } ?: "$packageName.${declaration.name}"
        }
        return declarations
            .filter { declaration -> declaration.directSupertypes.any(::isMetricsFactType) }
            .map { declaration -> identities.getValue(declaration.start) }
            .toSet()
    }

    private fun declarationHeader(code: String, start: Int, afterName: Int, name: String): DeclarationHeader {
        var parentheses = 0
        var angles = 0
        var brackets = 0
        var index = afterName
        var bodyStart: Int? = null
        while (index < code.length) {
            when (code[index]) {
                '(' -> parentheses++
                ')' -> parentheses--
                '<' -> angles++
                '>' -> angles--
                '[' -> brackets++
                ']' -> brackets--
                '{' -> if (parentheses == 0 && angles == 0 && brackets == 0) {
                    bodyStart = index
                    break
                }

                '\n' -> if (parentheses == 0 && angles == 0 && brackets == 0) {
                    val header = code.substring(afterName, index).trimEnd()
                    val next = code.drop(index + 1).firstOrNull { char -> !char.isWhitespace() }
                    if (!header.endsWith(':') && !header.endsWith(',') && next != ':' && next != '{') break
                }
            }
            index++
        }
        val header = code.substring(afterName, index)
        return DeclarationHeader(
            name = name,
            start = start,
            directSupertypes = directSupertypes(header),
            bodyStart = bodyStart,
        )
    }

    private fun directSupertypes(header: String): List<String> {
        var parentheses = 0
        var angles = 0
        var brackets = 0
        var colon = -1
        header.forEachIndexed { index, char ->
            when (char) {
                '(' -> parentheses++
                ')' -> parentheses--
                '<' -> angles++
                '>' -> angles--
                '[' -> brackets++
                ']' -> brackets--
                ':' -> if (parentheses == 0 && angles == 0 && brackets == 0 && colon < 0) colon = index
            }
        }
        if (colon < 0) return emptyList()
        val result = mutableListOf<String>()
        var entryStart = colon + 1
        parentheses = 0
        angles = 0
        brackets = 0
        for (index in entryStart..header.length) {
            val char = header.getOrNull(index)
            when (char) {
                '(' -> parentheses++
                ')' -> parentheses--
                '<' -> angles++
                '>' -> angles--
                '[' -> brackets++
                ']' -> brackets--
                ',', null -> if (parentheses == 0 && angles == 0 && brackets == 0) {
                    result += header.substring(entryStart, index).trim()
                    entryStart = index + 1
                }
            }
        }
        return result.filter { supertype -> supertype.isNotEmpty() }
    }

    private fun isMetricsFactType(supertype: String): Boolean {
        val type = supertype.replaceFirst(
            Regex("^(?:@[A-Za-z_][\\w.:]*(?:\\s*\\([^)]*\\))?\\s*)*"),
            "",
        )
        return Regex("^(?:[A-Za-z_]\\w*\\.)*SessionMetricsFact(?:\\s|<|\\(|$)").containsMatchIn(type)
    }

    private fun matchingBrace(code: String, opening: Int): Int {
        var depth = 0
        for (index in opening until code.length) {
            when (code[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return index
            }
        }
        return code.length
    }

    private fun constructorShape(source: String, type: String): ConstructorShape {
        val match = Regex(
            "(?s)\\b(public|private|protected|internal)\\s+((?:data\\s+)?)class\\s+" +
                    "$type\\s*\\(([^)]*)\\)",
        ).find(codeOnly(source)) ?: error("Missing constructor for $type")
        val properties = match.groupValues[3].split(',')
            .map { declaration -> declaration.trim() }
            .filter { declaration -> declaration.isNotEmpty() }
            .associate { declaration ->
                val property = propertyDeclaration.matchEntire(declaration)
                    ?: error("Invalid property in $type: $declaration")
                property.groupValues[3] to PropertyShape(
                    visibility = property.groupValues[1].ifEmpty { null },
                    mutability = property.groupValues[2],
                    type = property.groupValues[4],
                )
            }
        return ConstructorShape(
            visibility = match.groupValues[1],
            modifiers = match.groupValues[2].trim().split(' ').filter { modifier -> modifier.isNotEmpty() }.toSet(),
            properties = properties,
        )
    }

    private fun funInterfaces(source: String): List<String> =
        Regex("\\bfun\\s+interface\\s+(\\w+)").findAll(codeOnly(source)).map { it.groupValues[1] }.toList()

    private fun isExactLeafInventory(names: Set<String>): Boolean = names == expectedLeafFiles

    /** Removes comments and string/character contents while preserving declarations and line positions. */
    private fun codeOnly(source: String): String {
        val result = StringBuilder(source.length)
        var state = LexicalState.Code
        var blockDepth = 0
        var index = 0
        fun blank(char: Char) = if (char == '\n' || char == '\r') char else ' '
        while (index < source.length) {
            val char = source[index]
            val next = source.getOrNull(index + 1)
            when (state) {
                LexicalState.Code -> when {
                    char == '/' && next == '/' -> {
                        result.append("  ")
                        index += 2
                        state = LexicalState.LineComment
                        continue
                    }

                    char == '/' && next == '*' -> {
                        result.append("  ")
                        index += 2
                        blockDepth = 1
                        state = LexicalState.BlockComment
                        continue
                    }

                    source.startsWith("\"\"\"", index) -> {
                        result.append("   ")
                        index += 3
                        state = LexicalState.RawString
                        continue
                    }

                    char == '"' -> state = LexicalState.String
                    char == '\'' -> state = LexicalState.Character
                    else -> result.append(char)
                }

                LexicalState.LineComment -> {
                    result.append(blank(char))
                    if (char == '\n') state = LexicalState.Code
                }

                LexicalState.BlockComment -> when (char) {
                    '/' -> if (next == '*') {
                        result.append("  ")
                        index += 2
                        blockDepth++
                        continue
                    } else {
                        result.append(blank(char))
                    }

                    '*' -> if (next == '/') {
                        result.append("  ")
                        index += 2
                        blockDepth--
                        if (blockDepth == 0) state = LexicalState.Code
                        continue
                    } else {
                        result.append(blank(char))
                    }

                    else -> result.append(blank(char))
                }

                LexicalState.String, LexicalState.Character -> {
                    result.append(blank(char))
                    if (char == '\\' && next != null) {
                        result.append(blank(next))
                        index += 2
                        continue
                    }
                    if (state == LexicalState.String && char == '"' ||
                        state == LexicalState.Character && char == '\''
                    ) {
                        state = LexicalState.Code
                    }
                }

                LexicalState.RawString -> {
                    if (source.startsWith("\"\"\"", index)) {
                        result.append("   ")
                        index += 3
                        state = LexicalState.Code
                        continue
                    }
                    result.append(blank(char))
                }
            }
            index++
        }
        return result.toString()
    }

    private data class ConstructorShape(
        val visibility: String,
        val modifiers: Set<String>,
        val properties: Map<String, PropertyShape>,
    )

    private data class PropertyShape(
        val visibility: String?,
        val mutability: String,
        val type: String,
    )

    private data class DeclarationHeader(
        val name: String,
        val start: Int,
        val directSupertypes: List<String>,
        val bodyStart: Int?,
    )

    private data class DeclarationScope(
        val declaration: DeclarationHeader,
        val bodyStart: Int,
        val bodyEnd: Int,
    )

    @Suppress("unused") // Entries are consumed through the qualified state machine above.
    private enum class LexicalState { Code, LineComment, BlockComment, String, RawString, Character }

    private companion object {
        const val METRICS_PACKAGE = "dev.dmkr.screencaptureengine.internal.platform.metrics"
        val oldPathExclusions = setOf("AndroidCaptureMetricsProvider.kt", "CaptureMetricsObservation.kt")
        val expectedLeafFiles = setOf("SessionMetricsFact.kt", "SessionMetricsCollector.kt")
        val expectedPackageFiles = oldPathExclusions + expectedLeafFiles
        val productionConsumer = Regex(
            "\\b(?:SessionMetricsFact|SessionMetricsFactTag|SessionMetricsCollector|SessionMetricsFactSink|" +
                    "SessionMetricsBarrierProof)\\b",
        )
        val declarationStarts = Regex("\\b(?:class|object|interface)\\s+(\\w+)")
        val propertyDeclaration = Regex(
            "(?:(public|private|protected|internal)\\s+)?(val|var)\\s+(\\w+)\\s*:\\s*" +
                    "([A-Za-z_][\\w.]*\\??)",
        )
        val factInventory = setOf(
            "$METRICS_PACKAGE.SessionMetricsFact.Available",
            "$METRICS_PACKAGE.SessionMetricsFact.Unavailable",
            "$METRICS_PACKAGE.SessionMetricsFact.GetterThrew",
            "$METRICS_PACKAGE.SessionMetricsFact.CollectionCompleted",
            "$METRICS_PACKAGE.SessionMetricsFact.CollectionThrew",
            "$METRICS_PACKAGE.SessionMetricsFact.CancellationMarked",
        )
        val expectedTagShape = ConstructorShape(
            visibility = "internal",
            modifiers = setOf("data"),
            properties = mapOf(
                "session" to PropertyShape(null, "val", "SessionIdentity"),
                "attachment" to PropertyShape(null, "val", "ControllerMetricsAttachmentIdentity"),
                "operation" to PropertyShape(null, "val", "ControllerOperationIdentity"),
            ),
        )
        val expectedProofShape = ConstructorShape(
            visibility = "internal",
            modifiers = setOf("data"),
            properties = mapOf(
                "session" to PropertyShape(null, "val", "SessionIdentity"),
                "attachment" to PropertyShape(null, "val", "ControllerMetricsAttachmentIdentity"),
            ),
        )
        val allowedImports = setOf(
            "dev.dmkr.screencaptureengine.CaptureMetricsProvider",
            "dev.dmkr.screencaptureengine.CaptureMetricsState",
            "dev.dmkr.screencaptureengine.CaptureMetricsUnavailableReason",
            "dev.dmkr.screencaptureengine.internal.control.ControllerCancellationMarkerRevision",
            "dev.dmkr.screencaptureengine.internal.control.ControllerMetricsAttachmentIdentity",
            "dev.dmkr.screencaptureengine.internal.control.ControllerOperationIdentity",
            "dev.dmkr.screencaptureengine.internal.control.SessionIdentity",
            "kotlinx.coroutines.CoroutineDispatcher",
            "kotlinx.coroutines.CoroutineScope",
            "kotlinx.coroutines.Job",
            "kotlinx.coroutines.SupervisorJob",
            "kotlinx.coroutines.flow.Flow",
            "kotlinx.coroutines.flow.StateFlow",
            "kotlinx.coroutines.launch",
            "kotlinx.coroutines.withContext",
        )
        val forbidden = listOf(
            "android.",
            "ScreenCaptureSession",
            "SessionController",
            "ControllerReducer",
            "ControllerState",
            "ScreenCaptureProblem",
            "ScreenCaptureEvent",
            "IngressSequence",
            "SequenceAllocator",
            "CommittedRevision",
            "PlatformFailure",
            "Severity",
            "Retry",
            "Adapter",
            "CaptureMetricsObservation",
            "EngineAttachableCaptureMetricsProvider",
            "java.lang.reflect",
            "kotlin.reflect",
            "synchronized",
            "AutoCloseable",
            "Closeable",
            "distinctUntilChanged",
            "dedup",
        )
    }
}
