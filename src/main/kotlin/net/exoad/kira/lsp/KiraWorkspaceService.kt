package net.exoad.kira.lsp

import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.services.WorkspaceService

class KiraWorkspaceService(
    private val server: KiraLanguageServer,
) : WorkspaceService {
    override fun didChangeConfiguration(params: DidChangeConfigurationParams?) {
        // no config knobs yet
    }

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams?) {
        // Re-diagnose open docs when something on disk changes.
        val open = server.openDocuments().keys
        val textService = server.textDocumentService
        if (textService is KiraTextDocumentService) {
            open.forEach { uri ->
                try {
                    textService.diagnose(uri)
                } catch (_: Exception) {
                }
            }
        }
    }
}
