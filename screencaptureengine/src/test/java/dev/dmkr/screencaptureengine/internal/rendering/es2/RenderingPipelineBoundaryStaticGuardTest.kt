package dev.dmkr.screencaptureengine.internal.rendering.es2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class RenderingPipelineBoundaryStaticGuardTest {
    @Test
    fun es2RenderingPipelineOrchestrationDoesNotCrossRuntimeRenderingBoundary() {
        val sourceFiles = resolveEs2OrchestrationSourceFiles()

        sourceFiles.forEach { sourceFile ->
            val executableContent = Files.readString(sourceFile.path).withoutKotlinCommentsAndStrings()

            forbiddenBoundaryPatterns.forEach { pattern ->
                assertFalse(
                    "${sourceFile.displayPath} contains forbidden ES2 orchestration boundary pattern " +
                            pattern.description,
                    pattern.regex.containsMatchIn(executableContent),
                )
            }
            assertFalse(
                "${sourceFile.displayPath} calls GLES20 directly instead of staying at the orchestration boundary",
                directGles20CallRegex(executableContent).containsMatchIn(executableContent),
            )
            assertFalse(
                "${sourceFile.displayPath} calls bare GLES directly instead of staying at the orchestration boundary",
                bareDirectGlCallRegex.containsMatchIn(executableContent),
            )
        }
    }

    @Test
    fun kotlinSanitizerIgnoresForbiddenBoundaryTokensInCommentsAndNonExecutableLiterals() {
        val source = listOf(
            "package synthetic",
            "/** KDoc must ignore updateTexImage(), glReadPixels, and ImageEncoder.encode. */",
            "internal fun ignoredForbiddenTokens() {",
            "    // GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3) and android.graphics.SurfaceTexture",
            "    /* Block comment must ignore publishEncodedFrame( and GLES30 and PBO. */",
            "    val escaped = \"GLES20.glReadPixels(\\\"still literal\\\") and ScreenCaptureSessionCore\"",
            "    val raw = \"\"\"ImageEncoder.encode and getTransformMatrix are literal text\"\"\"",
            "    val broadMetadata = \"OES matrix transform readback encoder provider metadata map session\"",
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
            "getTransformMatrix",
            "android.graphics.SurfaceTexture",
            "publishEncodedFrame(",
            "ScreenCaptureSessionCore",
            "GLES30",
            "PBO",
            "OES matrix transform readback encoder provider metadata map session",
            "\"still literal\"",
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
    fun kotlinSanitizerPreservesExecutableStringTemplateExpressions() {
        val source = listOf(
            "package synthetic",
            "internal fun catchesForbiddenTemplates(buffer: java.nio.Buffer) {",
            "    val regular = \"literal \${GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)} text\"",
            "    val raw = \"\"\"literal \${GLES20.glReadPixels(0, 0, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)} text\"\"\"",
            "    val multiLiteral = \$\$\"literal \${android.opengl.GLES20.glDrawArrays(android.opengl.GLES20.GL_TRIANGLES, 0, 3)} text\"",
            "    val multiExecutable = \$\$\"literal \$\${android.opengl.GLES20.glFinish()} text\"",
            "}",
        ).joinToString(separator = "\n")

        val executableContent = source.withoutKotlinCommentsAndStrings()

        assertTrue(
            "Regular string template expression was stripped from executable content",
            executableContent.contains("GLES20.glDrawArrays"),
        )
        assertTrue(
            "Raw string template expression was stripped from executable content",
            executableContent.contains("GLES20.glReadPixels"),
        )
        assertTrue(
            "Multi-dollar executable string template expression was stripped from executable content",
            executableContent.contains("android.opengl.GLES20.glFinish"),
        )
        assertFalse(
            "Multi-dollar literal template expression should not be executable at this threshold",
            executableContent.contains("android.opengl.GLES20.glDrawArrays"),
        )
        assertFalse(executableContent.contains("literal"))
    }

    @Test
    fun directGles20GuardCatchesAliasImportedCalls() {
        val source = listOf(
            "package synthetic",
            "import android.opengl.GLES20 as GL",
            "internal fun directAliasCall() {",
            "    GL.glDrawArrays(GL.GL_TRIANGLES, 0, 3)",
            "}",
        ).joinToString(separator = "\n")

        val executableContent = source.withoutKotlinCommentsAndStrings()

        assertTrue(
            "Direct GLES20 alias call was not caught",
            directGles20CallRegex(executableContent).containsMatchIn(executableContent),
        )
    }

    @Test
    fun boundaryPatternsCatchExecutableForbiddenTokens() {
        val executableContent = listOf(
            "package synthetic",
            "import dev.dmkr.screencaptureengine.internal.session.core.ScreenCaptureSessionCore",
            "import dev.dmkr.screencaptureengine.internal.session.delivery.DeliveryDropKind",
            "internal fun forbidden(buffer: java.nio.Buffer, encoder: ImageEncoder) {",
            "    target.updateTexImage()",
            "    target.getTransformMatrix(floatArray)",
            "    ImageEncoder.encode",
            "    ImageEncoder::encode",
            "    encoder::encode",
            "    encoder.encode(buffer)",
            "    android.opengl.GLES20.glReadPixels(0, 0, 1, 1, 0, 0, buffer)",
            "    publishEncodedFrame(frame)",
            "    publishFrame(frame)",
            "    publisher.publish(frame)",
            "    RuntimeFrameLoopInstalled",
            "    InitialActivePlanCommitted",
            "    startSession(config)",
            "    Running(output)",
            "    SessionStarted",
            "    android.graphics.SurfaceTexture(0)",
            "    android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)",
            "    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)",
            "    android.opengl.GLES30.GL_PIXEL_PACK_BUFFER",
            "    glFenceSync()",
            "    glMapBuffer()",
            "    val deliveryDropKind: DeliveryDropKind? = null",
            "    dev.dmkr.screencaptureengine.internal.session.delivery.ScreenCaptureFrameDeliveryCoordinator",
            "}",
        ).joinToString(separator = "\n").withoutKotlinCommentsAndStrings()

        forbiddenBoundaryPatterns.forEach { pattern ->
            assertTrue(
                "Executable forbidden source should match ${pattern.description}",
                pattern.regex.containsMatchIn(executableContent),
            )
        }
        assertTrue(
            "Direct GLES20 call should be caught",
            directGles20CallRegex(executableContent).containsMatchIn(executableContent),
        )
        assertTrue(
            "Bare GLES call should be caught",
            bareDirectGlCallRegex.containsMatchIn(executableContent),
        )
    }

    @Test
    fun boundaryPatternsCatchSessionDeliveryImportsAndUses() {
        val executableContent = listOf(
            "package synthetic",
            "import dev.dmkr.screencaptureengine.internal.session.delivery.DeliveryDropKind",
            "internal fun forbidden() {",
            "    val dropKind: DeliveryDropKind? = null",
            "    dev.dmkr.screencaptureengine.internal.session.delivery.ScreenCaptureFrameDeliveryCoordinator",
            "}",
        ).joinToString(separator = "\n").withoutKotlinCommentsAndStrings()

        val matchedDescriptions = forbiddenBoundaryPatterns
            .filter { pattern -> pattern.regex.containsMatchIn(executableContent) }
            .map { pattern -> pattern.description }

        assertTrue(
            "Rendering pipeline guard did not catch session.delivery imports.",
            matchedDescriptions.any { description -> description.startsWith("session package import") },
        )
        assertTrue(
            "Rendering pipeline guard did not catch fully-qualified session.delivery uses.",
            matchedDescriptions.any { description -> description.startsWith("fully-qualified session package use") },
        )
    }

    @Test
    fun boundaryPatternsCatchExecutableInstanceEncodeCallableReference() {
        val executableContent = listOf(
            "package synthetic",
            "internal fun forbidden(encoder: ImageEncoder) {",
            "    val callable = encoder::encode",
            "}",
        ).joinToString(separator = "\n").withoutKotlinCommentsAndStrings()

        assertTrue(
            "Executable instance encode callable reference was not caught",
            forbiddenBoundaryPatterns.any { pattern ->
                pattern.description.contains("encode callable reference") &&
                        pattern.regex.containsMatchIn(executableContent)
            },
        )
    }

    @Test
    fun boundaryPatternsCatchSurfaceTextureSimpleNameAfterWildcardImport() {
        val executableContent = listOf(
            "package synthetic",
            "import android.graphics.*",
            "internal fun forbidden(): SurfaceTexture {",
            "    return SurfaceTexture(0)",
            "}",
        ).joinToString(separator = "\n").withoutKotlinCommentsAndStrings()

        assertTrue(
            "Executable SurfaceTexture simple-name reference was not caught",
            forbiddenBoundaryPatterns.any { pattern ->
                pattern.description.contains("SurfaceTexture simple name") &&
                        pattern.regex.containsMatchIn(executableContent)
            },
        )
    }

    @Test
    fun broadMetadataWordsAreNotBoundaryFailuresByThemselves() {
        val executableContent = listOf(
            "package synthetic",
            "internal fun metadataOnly() {",
            "    val OES = 1",
            "    val matrix = 2",
            "    val transform = 3",
            "    val readback = 4",
            "    val encoder = 5",
            "    val provider = 6",
            "    val metadata = 7",
            "    val map = 8",
            "    val session = 9",
            "}",
        ).joinToString(separator = "\n").withoutKotlinCommentsAndStrings()

        forbiddenBoundaryPatterns.forEach { pattern ->
            assertFalse(
                "Broad metadata-only source should not match ${pattern.description}",
                pattern.regex.containsMatchIn(executableContent),
            )
        }
    }

    private fun resolveEs2OrchestrationSourceFiles(): List<Es2OrchestrationSourceFile> {
        val start = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        val sourceRoot = generateSequence(start) { path -> path.parent }
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

        return es2OrchestrationSourceFileNames.map { fileName ->
            val relativePath = runtimePackagePath.resolve(fileName)
            val path = sourceRoot.resolve(relativePath)
            assertTrue(
                "ES2 orchestration source file is not present yet: ${sourceRoot.relativize(path)}. " +
                        "This static guard is ready for that file and should be run after it lands.",
                Files.isRegularFile(path),
            )
            Es2OrchestrationSourceFile(
                path = path,
                displayPath = sourceRoot.relativize(path).toString(),
            )
        }
    }

    private fun directGles20CallRegex(executableContent: String): Regex {
        val gles20References = buildList {
            add("android.opengl.GLES20")
            add("GLES20")
            Regex("""\bimport\s+android\.opengl\.GLES20\s+as\s+([A-Za-z_][A-Za-z0-9_]*)""")
                .findAll(executableContent)
                .map { match -> match.groupValues[1] }
                .forEach(::add)
        }.distinct()
        val referencePattern = gles20References.joinToString(separator = "|") { reference ->
            Regex.escape(reference)
        }
        return Regex("(?<![.\\w])(?:$referencePattern)\\.gl[A-Z]")
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

    private data class BoundaryPattern(
        val description: String,
        val regex: Regex,
    )

    private data class Es2OrchestrationSourceFile(
        val path: Path,
        val displayPath: String,
    )

    private companion object {
        private val runtimePackagePath = Path.of("dev/dmkr/screencaptureengine/internal/rendering/es2")

        private val es2OrchestrationSourceFileNames = listOf(
            "Es2RenderingPipelinePreparer.kt",
        )

        private val forbiddenBoundaryPatterns = listOf(
            literalPattern("SurfaceTexture frame update", "updateTexImage("),
            literalPattern("SurfaceTexture matrix acquisition", "getTransformMatrix("),
            literalPattern("runtime readback", "glReadPixels"),
            literalPattern("ImageEncoder direct encode", "ImageEncoder.encode"),
            literalPattern("ImageEncoder callable reference encode", "ImageEncoder::encode"),
            regexPattern("any encode callable reference", Regex("""::\s*encode\b""")),
            regexPattern("any direct encode call", Regex("""\.\s*encode\s*\(""")),
            regexPattern("session package import", Regex("""\bimport\s+dev\.dmkr\.screencaptureengine\.internal\.session\.""")),
            regexPattern(
                "fully-qualified session package use",
                Regex("""\bdev\.dmkr\.screencaptureengine\.internal\.session\.[A-Za-z_][A-Za-z0-9_.]*\b"""),
            ),
            literalPattern("session core", "ScreenCaptureSessionCore"),
            regexPattern("encoded frame publication", Regex("""\bpublishEncodedFrame\s*\(""")),
            regexPattern("frame publication", Regex("""\bpublishFrame\s*\(""")),
            regexPattern("generic publication call", Regex("""\.\s*publish\s*\(""")),
            literalPattern("runtime frame-loop marker", "RuntimeFrameLoopInstalled"),
            literalPattern("initial active commit marker", "InitialActivePlanCommitted"),
            regexPattern("public start-session entrypoint", Regex("""\bstartSession\s*\(""")),
            regexPattern("running start-state publication", Regex("""\bRunning\s*\(""")),
            literalPattern("session-started event", "SessionStarted"),
            literalPattern("Android SurfaceTexture type", "android.graphics.SurfaceTexture"),
            regexPattern("Android SurfaceTexture simple name", Regex("""\bSurfaceTexture\b""")),
            regexPattern("Android Bitmap type", Regex("""\b(?:android\.graphics\.)?Bitmap\b""")),
            literalPattern("Bitmap compression format", "Bitmap.CompressFormat"),
            regexPattern("Bitmap compression call", Regex("""(?:\.\s*compress|Bitmap\.CompressFormat)\s*\(""")),
            regexPattern("GLES3 API", Regex("""(?<![.\w])(?:android\.opengl\.)?GLES3[0-9]\b""")),
            regexPattern(
                "PBO/fence/sync/map-buffer terms",
                Regex(
                    pattern = """(?i)\b(?:pbo|pixel\s*buffer(?:\s*object)?|pixelbufferobject|""" +
                            """GL_PIXEL_PACK_BUFFER|fence|sync|map\s*buffer|unmap\s*buffer|mapBuffer|""" +
                            """unmapBuffer|glMapBuffer|glUnmapBuffer)\b""",
                ),
            ),
        )

        private val bareDirectGlCallRegex = Regex(
            "(?<![.\\w])gl(?:ActiveTexture|AttachShader|Bind|Blend|Buffer|Check|Clear|ClientWait|" +
                    "Compile|Create|Delete|Detach|Disable|Draw|Enable|Fence|Finish|Flush|Framebuffer|" +
                    "Gen|Get|Link|Map|PixelStore|ReadPixels|Renderbuffer|Shader|Tex|Uniform|Unmap|" +
                    "Use|Validate|Viewport|Wait)\\w*\\s*\\(",
        )

        private fun literalPattern(description: String, literal: String): BoundaryPattern =
            BoundaryPattern(description = "$description: $literal", regex = Regex(Regex.escape(literal)))

        private fun regexPattern(description: String, regex: Regex): BoundaryPattern =
            BoundaryPattern(description = "$description: ${regex.pattern}", regex = regex)
    }
}
