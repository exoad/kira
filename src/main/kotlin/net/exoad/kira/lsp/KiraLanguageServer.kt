package net.exoad.kira.lsp

import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.TextDocumentSyncOptions
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Kira language server (LSP 3.x via lsp4j).
 *
 * Baseline surface:
 *  - full document sync for `kira` / `*.kira`
 *  - publishDiagnostics from the shared frontend pipeline
 *
 * Hover / completion / go-to-def land later on the same FrontendService.
 */
class KiraLanguageServer : LanguageServer, LanguageClientAware {
    private val documents = ConcurrentHashMap<String, String>()
    private lateinit var client: LanguageClient
    private val textDocuments = KiraTextDocumentService(this)
    private val workspace = KiraWorkspaceService(this)
    @Volatile
    var workspaceRoot: String? = null
        private set

    fun clientOrNull(): LanguageClient? =
        if (this::client.isInitialized) client else null

    fun openDocuments(): Map<String, String> = documents.toMap()

    fun putDocument(uri: String, text: String) {
        documents[uri] = text
    }

    fun removeDocument(uri: String) {
        documents.remove(uri)
    }

    fun getDocument(uri: String): String? = documents[uri]

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        workspaceRoot = params.workspaceFolders
            ?.firstOrNull()
            ?.uri
            ?.let { LspPaths.uriToPath(it).toString() }
            ?: params.rootUri?.let { LspPaths.uriToPath(it).toString() }
            ?: params.rootPath

        val sync = TextDocumentSyncOptions().apply {
            openClose = true
            change = TextDocumentSyncKind.Full
            save = org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(false)
        }
        val capabilities = ServerCapabilities().apply {
            textDocumentSync = eitherSync(sync)
            // Future: hoverProvider, completionProvider, definitionProvider
        }
        return CompletableFuture.completedFuture(InitializeResult(capabilities))
    }

    private fun eitherSync(sync: TextDocumentSyncOptions): org.eclipse.lsp4j.jsonrpc.messages.Either<TextDocumentSyncKind, TextDocumentSyncOptions> {
        return org.eclipse.lsp4j.jsonrpc.messages.Either.forRight(sync)
    }

    override fun initialized(params: org.eclipse.lsp4j.InitializedParams?) {
        // no-op; diagnostics fire on first didOpen/didChange
    }

    override fun shutdown(): CompletableFuture<Any> {
        return CompletableFuture.completedFuture(null)
    }

    override fun exit() {
        // launcher process exits when stdin closes / shutdown completes
    }

    override fun getTextDocumentService(): TextDocumentService = textDocuments

    override fun getWorkspaceService(): WorkspaceService = workspace

    override fun connect(client: LanguageClient) {
        this.client = client
    }
}
