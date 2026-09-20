package net.exoad.kira.lsp

import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.services.LanguageClient
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.logging.Level
import java.util.logging.Logger

/**
 * stdio entrypoint for the Kira language server.
 *
 * Editors launch: `kira-lsp` (or `java -jar ... net.exoad.kira.lsp.LspMainKt`).
 * All LSP traffic is JSON-RPC over stdin/stdout; logs go to stderr only.
 */
fun main(args: Array<String>) {
    // Keep java.util.logging off stdout so it cannot corrupt the LSP stream.
    Logger.getLogger("").handlers.forEach { it.level = Level.SEVERE }
    Logger.getLogger("net.exoad.kira").level = Level.OFF

    if (args.contains("--version") || args.contains("-v")) {
        System.err.println("kira-lsp 0.1.0")
        return
    }
    if (args.contains("--help") || args.contains("-h")) {
        System.err.println(
            """
            kira-lsp -- Language Server Protocol server for Kira

            Speaks LSP over stdio. Point your editor at this binary.

            Options:
              -h, --help      show this help
              -v, --version   print version
            """.trimIndent()
        )
        return
    }

    startStdioServer(System.`in`, System.out)
}

fun startStdioServer(input: InputStream, output: OutputStream) {
    val server = KiraLanguageServer()
    val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "kira-lsp").apply { isDaemon = true }
    }
    val launcher = Launcher.createLauncher(
        server,
        LanguageClient::class.java,
        input,
        output,
        executor,
    ) { it }
    server.connect(launcher.remoteProxy)
    launcher.startListening().get()
}
