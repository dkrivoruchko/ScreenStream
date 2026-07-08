package dev.dmkr.screencaptureengine.internal.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class RuntimeBoundaryStaticGuardTest {
    @Test
    fun allRuntimeSourceFilesAreExplicitlyClassifiedForBoundaryGuards() {
        val discoveredPaths = runtimeSourceFiles()
            .map { sourceFile -> sourceFile.runtimeRelativePath }
            .toSet()

        assertEquals(
            "Runtime boundary guard classifications must be updated when runtime files are added, " +
                    "removed, or moved.",
            runtimeFileClassifications.keys.sorted(),
            discoveredPaths.sorted(),
        )
    }

    @Test
    fun startupAndPreparationFilesConservativelyStayBeforeRuntimeConsumptionEncodeAndPublication() {
        val sourceFiles = runtimeSourceFiles().filter { sourceFile ->
            sourceFile.role in startupPreparationRoles
        }

        assertTrue("No startup/preparation runtime files were classified.", sourceFiles.isNotEmpty())

        sourceFiles.forEach { sourceFile ->
            val executableContent = Files.readString(sourceFile.path).withoutKotlinCommentsAndStrings()

            startupPreparationForbiddenPatterns.forEach { pattern ->
                assertFalse(
                    "${sourceFile.displayPath} crosses startup/preparation runtime boundary: " +
                            pattern.description,
                    pattern.regex.containsMatchIn(executableContent),
                )
            }
        }
    }

    @Test
    fun directGlesCallsStayInLowLevelAdapterAllowlist() {
        runtimeSourceFiles().forEach { sourceFile ->
            val executableContent = Files.readString(sourceFile.path).withoutKotlinCommentsAndStrings()
            val directGlesUsages = directGlesUsagesIn(executableContent)
            val forbiddenDirectGlesUsages = directGlesUsages.filterNot { usage ->
                sourceFile.allowsDirectGlesUsage(usage)
            }

            assertTrue(
                "${sourceFile.displayPath} calls GLES directly outside low-level GLES adapters or " +
                        "explicit projection-target texture seams: " +
                        forbiddenDirectGlesUsages.joinToString { it.expression },
                forbiddenDirectGlesUsages.isEmpty(),
            )

            if (sourceFile.runtimeRelativePath == "target/ProjectionTargetOwner.kt") {
                assertEquals(
                    "${sourceFile.displayPath} changed its explicit direct GLES texture seam. " +
                            "Move new direct GLES work into a low-level adapter or update this guard deliberately.",
                    projectionTargetOwnerDirectGlesCallCounts,
                    directGlesUsages.groupingBy { usage -> usage.callName }.eachCount(),
                )
            }
        }
    }

    @Test
    fun screenCaptureSessionCoreUseStaysInActiveSessionIntegrationOwner() {
        runtimeSourceFiles().forEach { sourceFile ->
            val executableContent = Files.readString(sourceFile.path).withoutKotlinCommentsAndStrings()
            val matches = sessionCoreUsePatterns.flatMap { pattern ->
                pattern.regex.findAll(executableContent).map { match -> "${pattern.description}: ${match.value}" }
            }

            assertTrue(
                "${sourceFile.displayPath} imports or uses ScreenCaptureSessionCore outside active/session integration owner: " +
                        matches.joinToString(),
                matches.isEmpty() || sourceFile.runtimeRelativePath in sessionCoreIntegrationOwnerPaths,
            )
        }
    }

    @Test
    fun activeRuntimeProjectionStopSensitiveCoreMutationsStayInFencedPaths() {
        val activeRuntimeOwner = runtimeSourceFiles().single { sourceFile ->
            sourceFile.runtimeRelativePath == "lifecycle/ActiveRuntimeOwner.kt"
        }
        val executableContent = Files.readString(activeRuntimeOwner.path).withoutKotlinCommentsAndStrings()
        val failureFence = executableContent.functionBody("finishRuntimeFailureWithProjectionFence")
        val frameRatePolicy = executableContent.functionBody("admitFrameRatePolicy")

        assertEquals(
            "Runtime failure terminal commits must stay centralized behind the projection-stop fence.",
            1,
            Regex("""\.\s*finishFailed\s*\(""").findAll(executableContent).count(),
        )
        assertTrue(
            "Runtime failure terminal fence no longer commits the Failed state.",
            Regex("""\.\s*finishFailed\s*\(""").containsMatchIn(failureFence),
        )
        assertTrue(
            "Runtime failure terminal fence must arbitrate raw projection stop before committing.",
            failureFence.contains("arbitrateProjectionStopPublicOutcome"),
        )
        assertEquals(
            "Unmaterialized frame-rate drop accounting must stay centralized behind the projection-stop fence.",
            1,
            Regex("""\.\s*recordCurrentUnmaterializedProductionFrameDrop\s*\(""").findAll(executableContent).count(),
        )
        assertTrue(
            "Frame-rate policy drops must arbitrate raw projection stop before recording a public counter.",
            frameRatePolicy.contains("arbitrateProjectionStopPublicOutcome"),
        )
    }

    @Test
    fun publicStateStatsEventsAndCountersAreNotOwnedInRuntimeFiles() {
        runtimeSourceFiles().forEach { sourceFile ->
            val executableContent = Files.readString(sourceFile.path).withoutKotlinCommentsAndStrings()
            val matches = publicStateOwnershipPatterns.flatMap { pattern ->
                pattern.regex.findAll(executableContent).map { match -> "${pattern.description}: ${match.value}" }
            }

            assertFalse(
                "${sourceFile.displayPath} owns public state/stats/events/counters outside ScreenCaptureSessionCore: " +
                        matches.joinToString(),
                matches.isNotEmpty(),
            )
        }
    }

    @Test
    fun startupOnlyGlAccessStaysInPreparationAndHandoffCode() {
        runtimeSourceFiles().forEach { sourceFile ->
            val executableContent = Files.readString(sourceFile.path).withoutKotlinCommentsAndStrings()
            val matches = startupRenderingGlAccessRegex.findAll(executableContent).toList()

            assertTrue(
                "${sourceFile.displayPath} uses startup-only StartupRenderingGlAccess outside preparation/handoff code: " +
                        matches.joinToString { it.value },
                matches.isEmpty() || sourceFile.runtimeRelativePath in startupRenderingGlAccessAllowlistPaths,
            )
        }
    }

    @Test
    fun sanitizerIgnoresRuntimeBoundaryTokensInCommentsAndLiterals() {
        val source = listOf(
            "package synthetic",
            "/** Ignore updateTexImage(), glReadPixels, ImageEncoder.encode, and ScreenCaptureSessionCore. */",
            "internal fun ignoredTokens() {",
            "    // GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)",
            "    /* StartupRenderingGlAccess and MutableStateFlow<ScreenCaptureSessionState> are comments. */",
            "    val escaped = \"publishEncodedFrame(\\\"literal\\\") and surface.updateTexImage()\"",
            "    val raw = \"\"\"ScreenCaptureSessionCore and GLES20.glReadPixels are literal text\"\"\"",
            "    val quote = '\"'",
            "    val slash = '/'",
            "    val dollar = '$'",
            "    val executable = 1",
            "}",
        ).joinToString(separator = "\n")

        val executableContent = source.withoutKotlinCommentsAndStrings()

        assertTrue(executableContent.contains("val executable = 1"))
        listOf(
            "updateTexImage",
            "glReadPixels",
            "ImageEncoder.encode",
            "ScreenCaptureSessionCore",
            "StartupRenderingGlAccess",
            "MutableStateFlow",
            "publishEncodedFrame",
            "GLES20.glDrawArrays",
            "\"literal\"",
            "'\"'",
            "'/'",
            "'$'",
        ).forEach { forbidden ->
            assertFalse(
                "Sanitized executable content retained non-executable token $forbidden",
                executableContent.contains(forbidden),
            )
        }
    }

    @Test
    fun sanitizerPreservesExecutableStringTemplateExpressions() {
        val source = listOf(
            "package synthetic",
            "internal fun catchesForbiddenTemplates(buffer: java.nio.Buffer) {",
            "    val regular = \"literal \${GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)} text\"",
            "    val raw = \"\"\"literal \${GLES20.glReadPixels(0, 0, 1, 1, 0, 0, buffer)} text\"\"\"",
            "    val multiLiteral = \$\$\"literal \${android.opengl.GLES20.glDrawArrays(android.opengl.GLES20.GL_TRIANGLES, 0, 3)} text\"",
            "    val multiExecutable = \$\$\"literal \$\${android.opengl.GLES20.glFinish()} text\"",
            "}",
        ).joinToString(separator = "\n")

        val executableContent = source.withoutKotlinCommentsAndStrings()

        assertTrue(executableContent.contains("GLES20.glDrawArrays"))
        assertTrue(executableContent.contains("GLES20.glReadPixels"))
        assertTrue(executableContent.contains("android.opengl.GLES20.glFinish"))
        assertFalse(executableContent.contains("android.opengl.GLES20.glDrawArrays"))
        assertFalse(executableContent.contains("literal"))
    }

    @Test
    fun directGlesGuardCatchesAliasImportedCalls() {
        val executableContent = listOf(
            "package synthetic",
            "import android.opengl.GLES20",
            "import android.opengl.GLES20 as GL",
            "import android.opengl.GLES20.glFinish",
            "import android.opengl.GLES20.glReadPixels as readback",
            "private typealias GL20 = GLES20",
            "private typealias GLFqn = android.opengl.GLES20",
            "internal fun directAliasCall() {",
            "    GL.glDrawArrays(GL.GL_TRIANGLES, 0, 3)",
            "    GL20.glReadPixels(0, 0, 1, 1, 0, 0, buffer)",
            "    readback(0, 0, 1, 1, 0, 0, buffer)",
            "    val draw = GL::glDrawArrays",
            "    val typeAliasDraw = GL20 :: glDrawArrays",
            "    val fqnTypeAliasRead = GLFqn::glReadPixels",
            "    val read = android.opengl.GLES20::glReadPixels",
            "    val aliasRead = ::readback",
            "    val staticFinish = ::glFinish",
            "}",
        ).joinToString(separator = "\n").withoutKotlinCommentsAndStrings()

        assertTrue(
            "Direct GLES20 class/static/typealias calls and callable references were not caught",
            directGlesUsagesIn(executableContent).map { it.expression }.containsAll(
                listOf(
                    "GL.glDrawArrays(",
                    "GL20.glReadPixels(",
                    "GL::glDrawArrays",
                    "GL20 :: glDrawArrays",
                    "GLFqn::glReadPixels",
                    "::glFinish",
                ),
            ),
        )
    }

    @Test
    fun startupPreparationGuardCatchesCallableReferencesToRuntimeTargetMethods() {
        val executableContent = listOf(
            "package synthetic",
            "internal fun forbidden(target: RuntimeProjectionTargetGlAccess) {",
            "    val update = target::updateTexImage",
            "    val updateWithWhitespace = target :: updateTexImage",
            "    val castUpdate = (target as RuntimeProjectionTargetGlAccess)::updateTexImage",
            "    val matrix = target::getTransformMatrix",
            "    val matrixWithWhitespace = target :: getTransformMatrix",
            "    val castMatrix = (target as RuntimeProjectionTargetGlAccess)::getTransformMatrix",
            "}",
        ).joinToString(separator = "\n").withoutKotlinCommentsAndStrings()

        val matchedDescriptions = startupPreparationForbiddenPatterns
            .filter { pattern -> pattern.regex.containsMatchIn(executableContent) }
            .map { pattern -> pattern.description }

        assertTrue(
            "Startup/preparation guard did not catch updateTexImage callable references.",
            matchedDescriptions.any { description ->
                description.startsWith("SurfaceTexture frame update callable reference")
            },
        )
        assertTrue(
            "Startup/preparation guard did not catch getTransformMatrix callable references.",
            matchedDescriptions.any { description ->
                description.startsWith("SurfaceTexture matrix acquisition callable reference")
            },
        )
    }

    @Test
    fun startupPreparationGuardCatchesSessionDeliveryImportsAndUses() {
        val executableContent = listOf(
            "package synthetic",
            "import dev.dmkr.screencaptureengine.internal.session.delivery.DeliveryDropKind",
            "internal fun forbidden() {",
            "    val dropKind: DeliveryDropKind? = null",
            "    dev.dmkr.screencaptureengine.internal.session.delivery.ScreenCaptureFrameDeliveryCoordinator",
            "}",
        ).joinToString(separator = "\n").withoutKotlinCommentsAndStrings()

        val matchedDescriptions = startupPreparationForbiddenPatterns
            .filter { pattern -> pattern.regex.containsMatchIn(executableContent) }
            .map { pattern -> pattern.description }

        assertTrue(
            "Startup/preparation guard did not catch session.delivery imports.",
            matchedDescriptions.any { description -> description.startsWith("internal session package import") },
        )
        assertTrue(
            "Startup/preparation guard did not catch fully-qualified session.delivery uses.",
            matchedDescriptions.any { description -> description.startsWith("fully-qualified internal session use") },
        )
    }

    @Test
    fun publicStateStatsEventsGuardCatchesTypealiases() {
        val executableContent = listOf(
            "package synthetic",
            "import dev.dmkr.screencaptureengine.ScreenCaptureDeliveryDropStats",
            "import dev.dmkr.screencaptureengine.ScreenCaptureEvent",
            "import dev.dmkr.screencaptureengine.ScreenCaptureFrameDropStats",
            "import dev.dmkr.screencaptureengine.ScreenCaptureStats",
            "import kotlinx.coroutines.flow.MutableSharedFlow",
            "import kotlinx.coroutines.flow.MutableStateFlow",
            "private typealias PublicState<T> = MutableStateFlow<T>",
            "private typealias PublicEvents<T> = MutableSharedFlow<T>",
            "private typealias Stats = ScreenCaptureStats",
            "private typealias FrameDrops = ScreenCaptureFrameDropStats",
            "private typealias DeliveryDrops = ScreenCaptureDeliveryDropStats",
            "private typealias Event = ScreenCaptureEvent",
            "private typealias FqnStats = dev.dmkr.screencaptureengine.ScreenCaptureStats",
            "private typealias FqnEvents<T> = kotlinx.coroutines.flow.MutableSharedFlow<T>",
        ).joinToString(separator = "\n").withoutKotlinCommentsAndStrings()

        val matchedDescriptions = publicStateOwnershipPatterns
            .filter { pattern -> pattern.regex.containsMatchIn(executableContent) }
            .map { pattern -> pattern.description }

        listOf(
            "mutable public state flow typealias",
            "mutable public event flow typealias",
            "public stats typealias",
            "public frame-drop stats typealias",
            "public delivery-drop stats typealias",
            "public event typealias",
        ).forEach { expectedDescription ->
            assertTrue(
                "Public state/stats/events guard did not catch $expectedDescription.",
                matchedDescriptions.any { description -> description.startsWith(expectedDescription) },
            )
        }
    }

    @Test
    fun publicStateStatsEventsGuardCatchesCounterOwnershipBypasses() {
        val executableContent = listOf(
            "package synthetic",
            "import java.util.concurrent.atomic.AtomicLong",
            "internal class CounterOwner {",
            "    private var framesEncoded: Long = 0",
            "    private var averageEncodeMs = 0.0",
            "    private val lastEncodedByteCount: AtomicLong = AtomicLong()",
            "    private val averageReadbackMs = java.util.concurrent.atomic.AtomicLong()",
            "    private fun record() {",
            "        framesEncoded++",
            "        ++droppedDeliveries",
            "        framesPublished += 1",
            "        activeFrameSubscriptions -= 1",
            "    }",
            "}",
            "private AtomicLong slowConsumers",
        ).joinToString(separator = "\n").withoutKotlinCommentsAndStrings()

        val matchedDescriptions = publicStateOwnershipPatterns
            .filter { pattern -> pattern.regex.containsMatchIn(executableContent) }
            .map { pattern -> pattern.description }

        listOf(
            "public stats counter declaration",
            "public stats atomic counter declaration",
            "public stats counter assignment",
            "public stats counter increment",
        ).forEach { expectedDescription ->
            assertTrue(
                "Public state/stats/events guard did not catch $expectedDescription.",
                matchedDescriptions.any { description -> description.startsWith(expectedDescription) },
            )
        }
    }

    @Test
    fun publicStateStatsEventsGuardDoesNotCatchUnrelatedInternalCounters() {
        val executableContent = listOf(
            "package synthetic",
            "import java.util.concurrent.atomic.AtomicLong",
            "internal class InternalCounterOwner {",
            "    private var retryCount: Long = 0",
            "    private val eventSequence: AtomicLong = AtomicLong()",
            "    private val nextSequence = AtomicLong()",
            "    private fun record() {",
            "        retryCount++",
            "        nextSequence.incrementAndGet()",
            "        eventSequence.addAndGet(1)",
            "    }",
            "}",
        ).joinToString(separator = "\n").withoutKotlinCommentsAndStrings()

        val matches = publicStateOwnershipPatterns.flatMap { pattern ->
            pattern.regex.findAll(executableContent).map { match -> "${pattern.description}: ${match.value}" }
        }

        assertTrue(
            "Public state/stats/events guard caught unrelated internal counters: ${matches.joinToString()}",
            matches.isEmpty(),
        )
    }

    @Test
    fun boundaryPatternsCatchExecutableForbiddenRuntimeTokens() {
        val executableContent = listOf(
            "package synthetic",
            "import dev.dmkr.screencaptureengine.internal.session.core.ScreenCaptureSessionCore",
            "import dev.dmkr.screencaptureengine.internal.session.core.ProductionAttemptToken as AttemptToken",
            "import dev.dmkr.screencaptureengine.internal.session.delivery.DeliveryDropKind",
            "import dev.dmkr.screencaptureengine.ScreenCaptureStats as Stats",
            "import dev.dmkr.screencaptureengine.ScreenCaptureFrameDropStats as FrameDrops",
            "import dev.dmkr.screencaptureengine.ScreenCaptureDeliveryDropStats as DeliveryDrops",
            "import dev.dmkr.screencaptureengine.ScreenCaptureEvent as Event",
            "import kotlinx.coroutines.flow.MutableStateFlow as PublicStateFlow",
            "import kotlinx.coroutines.flow.MutableSharedFlow as PublicEventFlow",
            "import kotlinx.coroutines.flow.MutableStateFlow",
            "private typealias StateAlias<T> = MutableStateFlow<T>",
            "private typealias EventFlowAlias<T> = MutableSharedFlow<T>",
            "private typealias StatsAlias = ScreenCaptureStats",
            "private typealias FrameDropsAlias = ScreenCaptureFrameDropStats",
            "private typealias DeliveryDropsAlias = ScreenCaptureDeliveryDropStats",
            "private typealias EventAlias = ScreenCaptureEvent",
            "internal fun forbidden(encoder: ImageEncoder, buffer: java.nio.Buffer) {",
            "    target.updateTexImage()",
            "    target.updateTexImage",
            "        ()",
            "    val update = target::updateTexImage",
            "    target.getTransformMatrix(floatArray)",
            "    target.getTransformMatrix",
            "        (floatArray)",
            "    val matrix = target::getTransformMatrix",
            "    gles.readPixels(0, 0, 1, 1, 0, 0, buffer)",
            "    android.opengl.GLES20.glReadPixels(0, 0, 1, 1, 0, 0, buffer)",
            "    ImageEncoder.encode",
            "    ImageEncoder::encode",
            "    encoder::encode",
            "    encoder.encode(input, output)",
            "    publishEncodedFrame(frame)",
            "    publishFrame(frame)",
            "    publisher.publish(frame)",
            "    ScreenCaptureSessionCore(config, initialState, updater, trim)",
            "    dev.dmkr.screencaptureengine.internal.encoding.runtime.EncodedAttemptScratch(maxBytes)",
            "    dev.dmkr.screencaptureengine.internal.session.delivery.ScreenCaptureFrameDeliveryCoordinator",
            "    val token: AttemptToken? = null",
            "    val dropKind: DeliveryDropKind? = null",
            "    val state = MutableStateFlow(initialState)",
            "    val aliasedState = PublicStateFlow(initialState)",
            "    val events = MutableSharedFlow<ScreenCaptureEvent>()",
            "    val aliasedEvents = PublicEventFlow<Event>()",
            "    val stats = ScreenCaptureStats(framesEncoded = 1L)",
            "    val aliasedStats = Stats(framesPublished = 1L)",
            "    val frameDrops = ScreenCaptureFrameDropStats(total = 1L)",
            "    val aliasedFrameDrops = FrameDrops(total = 1L)",
            "    val deliveryDrops = ScreenCaptureDeliveryDropStats(total = 1L)",
            "    val aliasedDeliveryDrops = DeliveryDrops(total = 1L)",
            "    val event = ScreenCaptureEvent(sequence = 1L)",
            "    val aliasedEvent = Event(sequence = 1L)",
            "    framesEncoded++",
            "    framesPublished += 1",
            "    emitEvent(type)",
            "    StartupRenderingGlAccess",
            "}",
            "private var framesEncoded: Long = 0",
            "private val lastEncodedByteCount: java.util.concurrent.atomic.AtomicLong = java.util.concurrent.atomic.AtomicLong()",
            "private java.util.concurrent.atomic.AtomicLong slowConsumers",
        ).joinToString(separator = "\n").withoutKotlinCommentsAndStrings()

        startupPreparationForbiddenPatterns.forEach { pattern ->
            assertTrue(
                "Executable forbidden source should match ${pattern.description}",
                pattern.regex.containsMatchIn(executableContent),
            )
        }
        publicStateOwnershipPatterns.forEach { pattern ->
            assertTrue(
                "Executable public state ownership should match ${pattern.description}",
                pattern.regex.containsMatchIn(executableContent),
            )
        }
        assertTrue(directGlesUsagesIn(executableContent).isNotEmpty())
        assertTrue(startupRenderingGlAccessRegex.containsMatchIn(executableContent))
    }

    private fun runtimeSourceFiles(): List<RuntimeSourceFile> {
        val sourceRoot = resolveSourceRoot()
        val runtimeRoot = sourceRoot.resolve(runtimePackagePath)
        assertTrue("Runtime source package is missing: $runtimeRoot", Files.isDirectory(runtimeRoot))

        return runtimeFileClassifications.keys.sorted().map { relativePath ->
            val path = runtimeRoot.resolve(relativePath)
            assertTrue("Classified runtime source file is missing: $path", Files.isRegularFile(path))
            RuntimeSourceFile(
                path = path,
                runtimeRelativePath = relativePath,
                displayPath = sourceRoot.relativize(path).toDisplayPath(),
            )
        }
    }

    private fun resolveSourceRoot(): Path {
        val start = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        return generateSequence(start) { path -> path.parent }
            .mapNotNull { root ->
                when {
                    Files.isDirectory(root.resolve("screencaptureengine/src/main/java")) ->
                        root.resolve("screencaptureengine/src/main/java")

                    Files.isDirectory(root.resolve("src/main/java")) -> root.resolve("src/main/java")
                    else -> null
                }
            }
            .firstOrNull()
            ?: error("Could not resolve screencaptureengine source root from $start.")
    }

    private fun Path.toDisplayPath(): String =
        joinToString(separator = "/") { path -> path.toString() }

    private fun RuntimeSourceFile.allowsDirectGlesUsage(usage: DirectGlesUsage): Boolean =
        when (runtimeRelativePath) {
            in broadDirectGlesAdapterPaths -> true
            "target/ProjectionTargetOwner.kt" -> usage.callName in projectionTargetOwnerDirectGlesCallNames
            else -> false
        }

    private fun directGlesUsagesIn(executableContent: String): List<DirectGlesUsage> =
        buildList {
            directGlesCallRegex(executableContent).findAll(executableContent).forEach { match ->
                add(DirectGlesUsage(callName = match.groupValues[1], expression = match.value))
            }
            directGlesCallableReferenceRegex(executableContent).findAll(executableContent).forEach { match ->
                add(DirectGlesUsage(callName = match.groupValues[1], expression = match.value))
            }
            bareDirectGlCallRegex.findAll(executableContent).forEach { match ->
                add(DirectGlesUsage(callName = match.groupValues[1], expression = match.value))
            }
            addAll(directGlesStaticImportAliasUsagesIn(executableContent))
            addAll(directGlesStaticImportAliasCallableReferencesIn(executableContent))
        }

    private fun directGlesCallRegex(executableContent: String): Regex {
        val referencePattern = directGlesReferencePattern(executableContent)
        return Regex("""(?<![.\w])(?:$referencePattern)\.(gl[A-Z]\w*)\s*\(""")
    }

    private fun directGlesCallableReferenceRegex(executableContent: String): Regex {
        val referencePattern = directGlesReferencePattern(executableContent)
        return Regex("""(?<![.\w])(?:$referencePattern)\s*::\s*(gl[A-Z]\w*)\b""")
    }

    private fun directGlesReferencePattern(executableContent: String): String =
        directGlesReferences(executableContent).joinToString(separator = "|") { reference ->
            Regex.escape(reference)
        }

    private fun directGlesReferences(executableContent: String): List<String> =
        buildSet {
            add("android.opengl.GLES20")
            add("android.opengl.GLES30")
            add("android.opengl.GLES31")
            add("android.opengl.GLES32")
            add("GLES20")
            add("GLES30")
            add("GLES31")
            add("GLES32")
            Regex("""\bimport\s+android\.opengl\.(?:GLES20|GLES30|GLES31|GLES32)\s+as\s+([A-Za-z_][A-Za-z0-9_]*)""")
                .findAll(executableContent)
                .map { match -> match.groupValues[1] }
                .forEach(::add)
            var addedAlias: Boolean
            do {
                addedAlias = false
                Regex(
                    """\btypealias\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*""" +
                            """((?:android\.opengl\.)?(?:GLES20|GLES30|GLES31|GLES32)|[A-Za-z_][A-Za-z0-9_]*)\b""",
                )
                    .findAll(executableContent)
                    .forEach { match ->
                        val alias = match.groupValues[1]
                        val aliasedReference = match.groupValues[2]
                        if ((aliasedReference in this) && add(alias)) {
                            addedAlias = true
                        }
                    }
            } while (addedAlias)
        }.toList()

    private fun directGlesStaticImportAliasUsagesIn(executableContent: String): List<DirectGlesUsage> {
        val aliasesByCallName = directGlesStaticImportAliasesByCallName(executableContent)

        if (aliasesByCallName.isEmpty()) return emptyList()

        val aliasPattern = aliasesByCallName.keys.joinToString(separator = "|") { alias -> Regex.escape(alias) }
        return Regex("""(?<![.\w])($aliasPattern)\s*\(""")
            .findAll(executableContent)
            .map { match ->
                DirectGlesUsage(
                    callName = aliasesByCallName.getValue(match.groupValues[1]),
                    expression = match.value,
                )
            }
            .toList()
    }

    private fun directGlesStaticImportAliasCallableReferencesIn(executableContent: String): List<DirectGlesUsage> {
        val aliasesByCallName = directGlesStaticImportAliasesByCallName(executableContent)

        if (aliasesByCallName.isEmpty()) return emptyList()

        val aliasPattern = aliasesByCallName.keys.joinToString(separator = "|") { alias -> Regex.escape(alias) }
        return Regex("""(?<![.\w])::\s*($aliasPattern)\b""")
            .findAll(executableContent)
            .map { match ->
                DirectGlesUsage(
                    callName = aliasesByCallName.getValue(match.groupValues[1]),
                    expression = match.value,
                )
            }
            .toList()
    }

    private fun directGlesStaticImportAliasesByCallName(executableContent: String): Map<String, String> =
        buildMap {
            Regex("""\bimport\s+android\.opengl\.(?:GLES20|GLES30|GLES31|GLES32)\.(gl[A-Z]\w*)\b(?!\s+as\b)""")
                .findAll(executableContent)
                .forEach { match -> put(match.groupValues[1], match.groupValues[1]) }
            Regex(
                """\bimport\s+android\.opengl\.(?:GLES20|GLES30|GLES31|GLES32)\.(gl[A-Z]\w*)\s+as\s+""" +
                        """([A-Za-z_][A-Za-z0-9_]*)""",
            )
                .findAll(executableContent)
                .forEach { match -> put(match.groupValues[2], match.groupValues[1]) }
        }

    private fun String.withoutKotlinCommentsAndStrings(): String {
        val stripped = StringBuilder(length)

        fun appendWhitespace(start: Int, endExclusive: Int) {
            repeat(endExclusive - start) {
                stripped.append(' ')
            }
        }

        fun appendLineComment(start: Int): Int {
            val end = indexOf('\n', startIndex = start).let { if (it < 0) length else it }
            appendWhitespace(start = start, endExclusive = end)
            return end
        }

        fun appendBlockComment(start: Int): Int {
            var end = start + 2
            var depth = 1
            while ((end < length) && (depth > 0)) {
                when {
                    startsWith("/*", startIndex = end) -> {
                        depth += 1
                        end += 2
                    }

                    startsWith("*/", startIndex = end) -> {
                        depth -= 1
                        end += 2
                    }

                    else -> end += 1
                }
            }
            appendWhitespace(start = start, endExclusive = end)
            return end
        }

        fun appendCharLiteral(start: Int): Int {
            var end = start + 1
            var escaped = false
            while (end < length) {
                val char = this[end]
                end += 1
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == '\'') {
                    break
                }
            }
            appendWhitespace(start = start, endExclusive = end)
            return end
        }

        lateinit var appendCode: (start: Int, endExclusive: Int, stopAtTemplateEnd: Boolean) -> Int

        fun dollarRunEnd(start: Int, endExclusive: Int): Int {
            var end = start
            while ((end < endExclusive) && (this[end] == '$')) {
                end += 1
            }
            return end
        }

        fun templateExpressionMarkerEnd(start: Int, endExclusive: Int, interpolationThreshold: Int): Int? {
            if (this[start] != '$') return null
            val dollarEnd = dollarRunEnd(start = start, endExclusive = endExclusive)
            return if (
                (dollarEnd < endExclusive) &&
                (this[dollarEnd] == '{') &&
                (dollarEnd - start >= interpolationThreshold)
            ) {
                dollarEnd + 1
            } else {
                null
            }
        }

        fun appendTemplateExpression(start: Int, markerEndExclusive: Int, endExclusive: Int): Int {
            appendWhitespace(start = start, endExclusive = markerEndExclusive)
            return appendCode(markerEndExclusive, endExclusive, true)
        }

        fun appendRegularString(start: Int, quoteStart: Int, endExclusive: Int, interpolationThreshold: Int): Int {
            var current = quoteStart + 1
            appendWhitespace(start = start, endExclusive = current)
            var escaped = false
            while (current < endExclusive) {
                val markerEnd = if (!escaped) {
                    templateExpressionMarkerEnd(
                        start = current,
                        endExclusive = endExclusive,
                        interpolationThreshold = interpolationThreshold,
                    )
                } else {
                    null
                }
                when {
                    markerEnd != null -> {
                        current = appendTemplateExpression(
                            start = current,
                            markerEndExclusive = markerEnd,
                            endExclusive = endExclusive,
                        )
                    }

                    else -> {
                        val char = this[current]
                        appendWhitespace(start = current, endExclusive = current + 1)
                        current += 1
                        if (escaped) {
                            escaped = false
                        } else if (char == '\\') {
                            escaped = true
                        } else if (char == '"') {
                            return current
                        }
                    }
                }
            }
            return current
        }

        fun appendRawString(start: Int, quoteStart: Int, endExclusive: Int, interpolationThreshold: Int): Int {
            var current = quoteStart + 3
            appendWhitespace(start = start, endExclusive = current)
            while (current < endExclusive) {
                val markerEnd = templateExpressionMarkerEnd(
                    start = current,
                    endExclusive = endExclusive,
                    interpolationThreshold = interpolationThreshold,
                )
                when {
                    startsWith("\"\"\"", startIndex = current) -> {
                        appendWhitespace(start = current, endExclusive = current + 3)
                        return current + 3
                    }

                    markerEnd != null -> {
                        current = appendTemplateExpression(
                            start = current,
                            markerEndExclusive = markerEnd,
                            endExclusive = endExclusive,
                        )
                    }

                    else -> {
                        appendWhitespace(start = current, endExclusive = current + 1)
                        current += 1
                    }
                }
            }
            return current
        }

        appendCode = appendCode@{ start, endExclusive, stopAtTemplateEnd ->
            var current = start
            var nestedBraceDepth = 0
            while (current < endExclusive) {
                when {
                    stopAtTemplateEnd && (this[current] == '}') && (nestedBraceDepth == 0) -> {
                        appendWhitespace(start = current, endExclusive = current + 1)
                        return@appendCode current + 1
                    }

                    startsWith("//", startIndex = current) -> {
                        current = appendLineComment(start = current)
                    }

                    startsWith("/*", startIndex = current) -> {
                        current = appendBlockComment(start = current)
                    }

                    startsWith("\"\"\"", startIndex = current) -> {
                        current = appendRawString(
                            start = current,
                            quoteStart = current,
                            endExclusive = endExclusive,
                            interpolationThreshold = 1,
                        )
                    }

                    this[current] == '"' -> {
                        current = appendRegularString(
                            start = current,
                            quoteStart = current,
                            endExclusive = endExclusive,
                            interpolationThreshold = 1,
                        )
                    }

                    this[current] == '\'' -> {
                        current = appendCharLiteral(start = current)
                    }

                    this[current] == '$' -> {
                        val dollarEnd = dollarRunEnd(start = current, endExclusive = endExclusive)
                        current = when {
                            startsWith("\"\"\"", startIndex = dollarEnd) -> appendRawString(
                                start = current,
                                quoteStart = dollarEnd,
                                endExclusive = endExclusive,
                                interpolationThreshold = dollarEnd - current,
                            )

                            (dollarEnd < endExclusive) && (this[dollarEnd] == '"') -> appendRegularString(
                                start = current,
                                quoteStart = dollarEnd,
                                endExclusive = endExclusive,
                                interpolationThreshold = dollarEnd - current,
                            )

                            else -> {
                                stripped.append(this[current])
                                current + 1
                            }
                        }
                    }

                    stopAtTemplateEnd && (this[current] == '{') -> {
                        stripped.append(this[current])
                        nestedBraceDepth += 1
                        current += 1
                    }

                    stopAtTemplateEnd && (this[current] == '}') -> {
                        stripped.append(this[current])
                        nestedBraceDepth -= 1
                        current += 1
                    }

                    else -> {
                        stripped.append(this[current])
                        current += 1
                    }
                }
            }
            current
        }

        appendCode(0, length, false)
        return stripped.toString()
    }

    private fun String.functionBody(functionName: String): String {
        val functionMatch = Regex("""\bfun\s+$functionName\s*\(""").find(this)
            ?: error("Function $functionName was not found.")
        val bodyStart = indexOf('{', startIndex = functionMatch.range.last)
        check(bodyStart >= 0) { "Function $functionName has no block body." }
        var depth = 0
        var index = bodyStart
        while (index < length) {
            when (this[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return substring(bodyStart, index + 1)
                }
            }
            index += 1
        }
        error("Function $functionName body is not closed.")
    }

    private data class BoundaryPattern(
        val description: String,
        val regex: Regex,
    )

    private data class RuntimeSourceFile(
        val path: Path,
        val runtimeRelativePath: String,
        val displayPath: String,
    ) {
        val role: RuntimeFileRole
            get() = runtimeFileClassifications.getValue(runtimeRelativePath)
    }

    private data class DirectGlesUsage(
        val callName: String,
        val expression: String,
    )

    private enum class RuntimeFileRole {
        ActiveSessionIntegration,
        PlatformLifecycleSupport,
        ProjectionTargetOwner,
        RuntimeProduction,
        SharedGlInfrastructure,
        StartupPreparation,
        StartupRuntimeHandoff,
    }

    private companion object {
        private val runtimePackagePath = Path.of("dev/dmkr/screencaptureengine/internal")

        private val startupPreparationRoles = setOf(
            RuntimeFileRole.StartupPreparation,
            RuntimeFileRole.StartupRuntimeHandoff,
        )

        private val runtimeFileClassifications = mapOf(
            "lifecycle/ActiveRuntimeOwner.kt" to RuntimeFileRole.ActiveSessionIntegration,
            "platform/metrics/CaptureMetricsObservation.kt" to RuntimeFileRole.StartupPreparation,
            "gl/CleanupFailure.kt" to RuntimeFileRole.PlatformLifecycleSupport,
            "rendering/es2/Es2RenderingPipelinePreparer.kt" to RuntimeFileRole.StartupPreparation,
            "rendering/es2/Es2RenderingReadbackResourcePreparer.kt" to RuntimeFileRole.StartupPreparation,
            "rendering/es2/FirstPlanRenderTransformPackage.kt" to RuntimeFileRole.StartupPreparation,
            "gl/Gles20Api.kt" to RuntimeFileRole.StartupPreparation,
            "gl/GlLaneContextOwner.kt" to RuntimeFileRole.SharedGlInfrastructure,
            "encoding/provider/ImageEncoderPreparer.kt" to RuntimeFileRole.StartupPreparation,
            "lifecycle/InitialRuntimeHandoffGate.kt" to RuntimeFileRole.StartupRuntimeHandoff,
            "lifecycle/InitialRuntimeResourceOwner.kt" to RuntimeFileRole.StartupRuntimeHandoff,
            "platform/projection/MediaProjectionCallbackAdapter.kt" to RuntimeFileRole.PlatformLifecycleSupport,
            "lifecycle/PlanPreparationToken.kt" to RuntimeFileRole.StartupPreparation,
            "lifecycle/PreActiveRuntimeOwner.kt" to RuntimeFileRole.StartupPreparation,
            "rendering/es2/PreparedEs2RenderingReadbackResources.kt" to RuntimeFileRole.StartupPreparation,
            "rendering/pipeline/PreparedRenderingPipelineResources.kt" to RuntimeFileRole.StartupPreparation,
            "platform/projection/ProjectionStopArbiter.kt" to RuntimeFileRole.ActiveSessionIntegration,
            "target/ProjectionTargetOwner.kt" to RuntimeFileRole.ProjectionTargetOwner,
            "target/ProjectionTargetRuntimeExtensions.kt" to RuntimeFileRole.RuntimeProduction,
            "encoding/provider/ProviderPreparationContext.kt" to RuntimeFileRole.StartupPreparation,
            "rendering/pipeline/RenderingPipelinePreparationResult.kt" to RuntimeFileRole.StartupPreparation,
            "rendering/es2/RuntimeEs2FrameRenderer.kt" to RuntimeFileRole.RuntimeProduction,
            "lifecycle/RuntimeFrameLoop.kt" to RuntimeFileRole.RuntimeProduction,
            "lifecycle/RuntimeFrameSignalSource.kt" to RuntimeFileRole.RuntimeProduction,
            "gl/RuntimeGles20Api.kt" to RuntimeFileRole.RuntimeProduction,
            "target/RuntimeProjectionTargetGlAccess.kt" to RuntimeFileRole.RuntimeProduction,
            "startup/ScreenCaptureStartupResources.kt" to RuntimeFileRole.StartupPreparation,
            "startup/ScreenCaptureStartupTransaction.kt" to RuntimeFileRole.StartupPreparation,
            "gl/StartupCleanup.kt" to RuntimeFileRole.StartupPreparation,
            "startup/StartupFactories.kt" to RuntimeFileRole.StartupPreparation,
            "startup/StartupGeometryArbiter.kt" to RuntimeFileRole.StartupPreparation,
            "platform/projection/StartupProjectionCallbackRouter.kt" to RuntimeFileRole.StartupPreparation,
            "target/StartupRenderingGlAccess.kt" to RuntimeFileRole.StartupPreparation,
            "platform/projection/StartupResourceFacades.kt" to RuntimeFileRole.StartupPreparation,
            "startup/StartupRuntimeSignalMailbox.kt" to RuntimeFileRole.StartupPreparation,
            "platform/projection/VirtualDisplayOwner.kt" to RuntimeFileRole.StartupPreparation,
        )

        private val broadDirectGlesAdapterPaths = setOf(
            "gl/Gles20Api.kt",
            "gl/GlLaneContextOwner.kt",
            "gl/RuntimeGles20Api.kt",
        )

        private val projectionTargetOwnerDirectGlesCallNames = setOf(
            "glBindTexture",
            "glDeleteTextures",
            "glGenTextures",
            "glTexParameteri",
        )

        private val projectionTargetOwnerDirectGlesCallCounts = mapOf(
            "glBindTexture" to 5,
            "glDeleteTextures" to 1,
            "glGenTextures" to 1,
            "glTexParameteri" to 4,
        )

        private val sessionCoreIntegrationOwnerPaths = setOf(
            "lifecycle/ActiveRuntimeOwner.kt",
            "InitialActiveSessionOwner.kt",
            "InitialActiveSessionCommitter.kt",
            "InitialActivationCommitter.kt",
            "RuntimeSessionOwner.kt",
            "RuntimeSessionIntegration.kt",
        )

        private val startupRenderingGlAccessAllowlistPaths = setOf(
            "rendering/es2/Es2RenderingPipelinePreparer.kt",
            "lifecycle/InitialRuntimeHandoffGate.kt",
            "lifecycle/PreActiveRuntimeOwner.kt",
            "rendering/pipeline/PreparedRenderingPipelineResources.kt",
            "target/ProjectionTargetOwner.kt",
            "target/StartupRenderingGlAccess.kt",
            "platform/projection/StartupResourceFacades.kt",
        )

        private val internalSessionProductionTypeNames = setOf(
            "EncodedAttemptScratch",
            "ProductionAttemptToken",
            "ScreenCaptureSessionCore",
        )

        private val publicStateFlowTypeNames = setOf("MutableStateFlow")

        private val publicEventFlowTypeNames = setOf("MutableSharedFlow")

        private val publicStatsTypeNames = setOf("ScreenCaptureStats")

        private val publicFrameDropStatsTypeNames = setOf("ScreenCaptureFrameDropStats")

        private val publicDeliveryDropStatsTypeNames = setOf("ScreenCaptureDeliveryDropStats")

        private val publicEventTypeNames = setOf("ScreenCaptureEvent")

        private val publicStatsCounterNames = setOf(
            "framesEncoded",
            "framesPublished",
            "droppedFrames",
            "droppedDeliveries",
            "publishedFps",
            "averageEncodeMs",
            "averageReadbackMs",
            "lastEncodedByteCount",
            "averageEncodedByteCount",
            "activeFrameSubscriptions",
            "slowConsumers",
        )

        private val startupPreparationForbiddenPatterns = listOf(
            regexPattern("SurfaceTexture frame update", methodCallPattern("updateTexImage")),
            regexPattern("SurfaceTexture frame update callable reference", callableReferencePattern("updateTexImage")),
            regexPattern("SurfaceTexture matrix acquisition", methodCallPattern("getTransformMatrix")),
            regexPattern(
                "SurfaceTexture matrix acquisition callable reference",
                callableReferencePattern("getTransformMatrix"),
            ),
            literalPattern("direct runtime readback", "glReadPixels"),
            regexPattern(
                "runtime readback seam call",
                Regex("""(?:^|[^\w])(?:[A-Za-z_][A-Za-z0-9_]*\s*\.\s*)?readPixels\s*\("""),
            ),
            literalPattern("ImageEncoder direct encode", "ImageEncoder.encode"),
            literalPattern("ImageEncoder callable reference encode", "ImageEncoder::encode"),
            regexPattern("conservative encode callable reference", Regex("""::\s*encode\b""")),
            regexPattern("conservative direct encode call", Regex("""\.\s*encode\s*\(""")),
            regexPattern("internal session package import", Regex("""\bimport\s+dev\.dmkr\.screencaptureengine\.internal\.session\.""")),
            regexPattern(
                "fully-qualified internal session use",
                Regex("""\bdev\.dmkr\.screencaptureengine\.internal\.session\.[A-Za-z_][A-Za-z0-9_.]*\b"""),
            ),
            regexPattern(
                "internal session production type alias import",
                aliasImportPattern(
                    packageName = "dev.dmkr.screencaptureengine.internal.session.core",
                    typeNames = internalSessionProductionTypeNames,
                ),
            ),
            regexPattern("internal session production type/use", simpleNamePattern(internalSessionProductionTypeNames)),
            literalPattern("session core", "ScreenCaptureSessionCore"),
            regexPattern("encoded frame publication", Regex("""\bpublishEncodedFrame\s*\(""")),
            regexPattern("frame publication", Regex("""\bpublishFrame\s*\(""")),
            regexPattern("conservative generic publication call", Regex("""\.\s*publish\s*\(""")),
        )

        private val sessionCoreUsePatterns = listOf(
            regexPattern(
                "session core import",
                Regex("""\bimport\s+dev\.dmkr\.screencaptureengine\.internal\.session\.core\.ScreenCaptureSessionCore\b"""),
            ),
            regexPattern("session core type/use", Regex("""\bScreenCaptureSessionCore\b""")),
        )

        private val publicStateOwnershipPatterns = listOf(
            regexPattern("mutable public state flow", Regex("""\bMutableStateFlow\s*(?:<|\()""")),
            regexPattern(
                "mutable public state flow alias import",
                aliasImportPattern(packageName = "kotlinx.coroutines.flow", typeNames = publicStateFlowTypeNames),
            ),
            regexPattern(
                "mutable public state flow typealias",
                typeAliasPattern(packageName = "kotlinx.coroutines.flow", typeNames = publicStateFlowTypeNames),
            ),
            regexPattern("mutable public event flow", Regex("""\bMutableSharedFlow\s*(?:<|\()""")),
            regexPattern(
                "mutable public event flow alias import",
                aliasImportPattern(packageName = "kotlinx.coroutines.flow", typeNames = publicEventFlowTypeNames),
            ),
            regexPattern(
                "mutable public event flow typealias",
                typeAliasPattern(packageName = "kotlinx.coroutines.flow", typeNames = publicEventFlowTypeNames),
            ),
            regexPattern("public stats construction", Regex("""\bScreenCaptureStats\s*\(""")),
            regexPattern(
                "public stats alias import",
                aliasImportPattern(packageName = "dev.dmkr.screencaptureengine", typeNames = publicStatsTypeNames),
            ),
            regexPattern(
                "public stats typealias",
                typeAliasPattern(packageName = "dev.dmkr.screencaptureengine", typeNames = publicStatsTypeNames),
            ),
            regexPattern("public frame-drop stats construction", Regex("""\bScreenCaptureFrameDropStats\s*\(""")),
            regexPattern(
                "public frame-drop stats alias import",
                aliasImportPattern(packageName = "dev.dmkr.screencaptureengine", typeNames = publicFrameDropStatsTypeNames),
            ),
            regexPattern(
                "public frame-drop stats typealias",
                typeAliasPattern(packageName = "dev.dmkr.screencaptureengine", typeNames = publicFrameDropStatsTypeNames),
            ),
            regexPattern("public delivery-drop stats construction", Regex("""\bScreenCaptureDeliveryDropStats\s*\(""")),
            regexPattern(
                "public delivery-drop stats alias import",
                aliasImportPattern(packageName = "dev.dmkr.screencaptureengine", typeNames = publicDeliveryDropStatsTypeNames),
            ),
            regexPattern(
                "public delivery-drop stats typealias",
                typeAliasPattern(packageName = "dev.dmkr.screencaptureengine", typeNames = publicDeliveryDropStatsTypeNames),
            ),
            regexPattern("public event construction", Regex("""\bScreenCaptureEvent\s*\(""")),
            regexPattern(
                "public event alias import",
                aliasImportPattern(packageName = "dev.dmkr.screencaptureengine", typeNames = publicEventTypeNames),
            ),
            regexPattern(
                "public event typealias",
                typeAliasPattern(packageName = "dev.dmkr.screencaptureengine", typeNames = publicEventTypeNames),
            ),
            regexPattern(
                "public stats counter assignment",
                publicStatsCounterAssignmentPattern(publicStatsCounterNames),
            ),
            regexPattern(
                "public stats counter declaration",
                publicStatsCounterDeclarationPattern(publicStatsCounterNames),
            ),
            regexPattern(
                "public stats atomic counter declaration",
                publicStatsAtomicCounterDeclarationPattern(publicStatsCounterNames),
            ),
            regexPattern(
                "public stats counter increment",
                publicStatsCounterIncrementPattern(publicStatsCounterNames),
            ),
            regexPattern("public lifecycle event emission", Regex("""\bemitEvent\s*\(""")),
        )

        private val startupRenderingGlAccessRegex = Regex("""\bStartupRenderingGlAccess\b""")

        private val bareDirectGlCallRegex = Regex(
            "(?<![.\\w])(gl(?:ActiveTexture|AttachShader|Bind|Blend|Buffer|Check|Clear|ClientWait|" +
                    "Compile|Create|Delete|Detach|Disable|Draw|Enable|Fence|Finish|Flush|Framebuffer|" +
                    "Gen|Get|Link|Map|PixelStore|ReadPixels|Renderbuffer|Shader|Tex|Uniform|Unmap|" +
                    "Use|Validate|Viewport|Wait)\\w*)\\s*\\(",
        )

        private fun methodCallPattern(methodName: String): Regex =
            Regex("""(?:^|[^\w])(?:[A-Za-z_][A-Za-z0-9_]*\s*\.\s*)?${Regex.escape(methodName)}\s*\(""")

        private fun callableReferencePattern(methodName: String): Regex =
            Regex("""::\s*${Regex.escape(methodName)}\b""")

        private fun aliasImportPattern(packageName: String, typeNames: Set<String>): Regex {
            val typePattern = simpleAlternation(typeNames)
            return Regex(
                """\bimport\s+${Regex.escape(packageName)}\.(?:$typePattern)\s+as\s+""" +
                        """[A-Za-z_][A-Za-z0-9_]*\b""",
            )
        }

        private fun typeAliasPattern(packageName: String, typeNames: Set<String>): Regex {
            val typePattern = simpleAlternation(typeNames)
            return Regex(
                """\btypealias\s+[A-Za-z_][A-Za-z0-9_]*(?:\s*<[^>\n]+>)?\s*=\s*""" +
                        """(?:${Regex.escape(packageName)}\.)?(?:$typePattern)\b""",
            )
        }

        private fun publicStatsCounterAssignmentPattern(counterNames: Set<String>): Regex =
            Regex("""(?<![.\w])(?:${simpleAlternation(counterNames)})\s*(?:[-+*/%]?=)(?!=|>)""")

        private fun publicStatsCounterDeclarationPattern(counterNames: Set<String>): Regex =
            Regex("""\b(?:var|val)\s+(?:${simpleAlternation(counterNames)})\s*(?::|=)""")

        private fun publicStatsAtomicCounterDeclarationPattern(counterNames: Set<String>): Regex {
            val counterPattern = simpleAlternation(counterNames)
            val atomicTypePattern = """(?:java\.util\.concurrent\.atomic\.)?Atomic(?:Integer|Long)\b"""
            return Regex(
                """\b(?:var|val)\s+(?:$counterPattern)\s*:\s*$atomicTypePattern|""" +
                        """\b(?:(?:private|protected|internal|public)\s+)*$atomicTypePattern\s+""" +
                        """(?:$counterPattern)\b""",
            )
        }

        private fun publicStatsCounterIncrementPattern(counterNames: Set<String>): Regex {
            val counterPattern = simpleAlternation(counterNames)
            return Regex(
                """(?<![.\w])(?:(?:$counterPattern)\s*(?:\+\+|--)|""" +
                        """(?:\+\+|--)\s*(?:$counterPattern)\b)""",
            )
        }

        private fun simpleNamePattern(typeNames: Set<String>): Regex {
            val typePattern = simpleAlternation(typeNames)
            return Regex("""\b(?:$typePattern)\b""")
        }

        private fun simpleAlternation(identifiers: Set<String>): String =
            identifiers.joinToString(separator = "|") { identifier -> Regex.escape(identifier) }

        private fun literalPattern(description: String, literal: String): BoundaryPattern =
            BoundaryPattern(description = "$description: $literal", regex = Regex(Regex.escape(literal)))

        private fun regexPattern(description: String, regex: Regex): BoundaryPattern =
            BoundaryPattern(description = "$description: ${regex.pattern}", regex = regex)
    }
}
