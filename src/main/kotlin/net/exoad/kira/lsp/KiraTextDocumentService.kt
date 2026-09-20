package net.exoad.kira.lsp

import net.exoad.kira.compiler.FrontendService
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.services.TextDocumentService
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class KiraTextDocumentService(
    private val server: KiraLanguageServer,
) : TextDocumentService {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "kira-lsp-diagnose").apply { isDaemon = true }
    }
    private val pending = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val debounceMs = 250L

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val uri = params.textDocument.uri
        server.putDocument(uri, params.textDocument.text)
        scheduleDiagnose(uri)
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        val uri = params.textDocument.uri
        // Full sync: the client sends the entire buffer in changes[0].text
        val text = params.contentChanges.firstOrNull()?.text ?: return
        server.putDocument(uri, text)
        scheduleDiagnose(uri)
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        val uri = params.textDocument.uri
        pending.remove(uri)?.cancel(false)
        server.removeDocument(uri)
        // Clear diagnostics for the closed file
        server.clientOrNull()?.publishDiagnostics(
            PublishDiagnosticsParams(uri, emptyList())
        )
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        scheduleDiagnose(params.textDocument.uri)
    }

    private fun scheduleDiagnose(uri: String) {
        pending.remove(uri)?.cancel(false)
        pending[uri] = scheduler.schedule({
            try {
                diagnose(uri)
            } catch (_: Exception) {
                // never crash the server thread on a bad buffer
            }
        }, debounceMs, TimeUnit.MILLISECONDS)
    }

    internal fun diagnose(uri: String) {
        val client = server.clientOrNull() ?: return
        val path = LspPaths.uriToPath(uri)
        if (!path.toString().endsWith(".kira") && !uri.endsWith(".kira")) {
            return
        }

        val projectRoot = resolveProjectRoot(path)
        val overlays = server.openDocuments().mapKeys { (docUri, _) ->
            LspPaths.uriToPath(docUri).toString()
        }

        val result = FrontendService.compileProject(projectRoot, overlays)

        // Group diagnostics by file URI and publish (including empty lists to clear).
        val byUri = linkedMapOf<String, MutableList<Diagnostic>>()
        // Always clear the triggering document first.
        byUri[uri] = mutableListOf()

        for (d in result.diagnostics) {
            val dUri = if (d.file.isBlank()) uri else LspPaths.pathToUri(d.file)
            val list = byUri.getOrPut(dUri) { mutableListOf() }
            list += toLspDiagnostic(d)
        }

        for ((docUri, diags) in byUri) {
            client.publishDiagnostics(PublishDiagnosticsParams(docUri, diags))
        }
    }

    private fun resolveProjectRoot(filePath: Path): Path {
        server.workspaceRoot?.let { root ->
            val candidate = Path.of(root)
            // Prefer workspace root when the file lives under it.
            if (filePath.startsWith(candidate)) {
                return FrontendService.findProjectRoot(filePath)
            }
        }
        return FrontendService.findProjectRoot(filePath)
    }

    private fun toLspDiagnostic(d: FrontendService.Diagnostic): Diagnostic {
        // LSP positions are 0-based; Kira SourcePosition is 1-based.
        val startLine = (d.start.lineNumber - 1).coerceAtLeast(0)
        val startChar = (d.start.column - 1).coerceAtLeast(0)
        val endLine = (d.end.lineNumber - 1).coerceAtLeast(startLine)
        val endChar = (d.end.column - 1).coerceAtLeast(startChar + 1)
        return Diagnostic(
            Range(Position(startLine, startChar), Position(endLine, endChar)),
            d.message,
            when (d.severity) {
                FrontendService.Diagnostic.Severity.ERROR -> DiagnosticSeverity.Error
                FrontendService.Diagnostic.Severity.WARNING -> DiagnosticSeverity.Warning
                FrontendService.Diagnostic.Severity.INFORMATION -> DiagnosticSeverity.Information
                FrontendService.Diagnostic.Severity.HINT -> DiagnosticSeverity.Hint
            },
            "kira",
            d.tag,
        )
    }
}
