package net.exoad.kira.types

import net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ClassDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.types.TyperTestSupport.Src
import net.exoad.kira.types.TyperTestSupport.module
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What a name may declare: empty types, values written as types, capitals and snake_case. */
class DeclarationNameTest {
    private fun refused(vararg sources: Src): DiagnosticsException = assertThrows { TyperTestSupport.unitOf(*sources) }

    private fun refused(body: String): DiagnosticsException = refused(module("test:main", body))

    @Test
    fun aTypeThatAddsNoMemberIsRefusedUnlessTheHostSuppliesIt() {
        for (empty in listOf("Marker: Any { }", "Marker: Any", "Unit: Struct { }")) {
            val e = refused(empty)
            assertEquals("parse.type.empty", e.tag, empty)
            assertTrue(e.message.contains("adds no member"), e.message)
        }
        TyperTestSupport.unitOf(module("test:main", "pub @_opaque Handle: Any { }\npub Base: Any {\n    pub x: Int32 = 0\n}\npub Kid: Base { }"))
    }

    @Test
    fun aValueWrittenAsATypeOrAnImportInABodySaysWhatItIs() {
        val local = refused("Cfg: Any {\n    pub x: Int32 = 1\n}\nmain: () Void {\n    c: Cfg {\n        pub x: Int32 = 1\n    }\n}")
        assertEquals("parse.type.name", local.tag)
        assertTrue(local.message.contains("'c: Cfg { }' declares nothing") && local.message.contains("'c: Cfg = ...'"), local.message)
        val function = refused("f: Int32 {\n    return 1\n}")
        assertEquals("parse.type.name", function.tag)
        assertTrue(function.message.contains("'f: () Int32 { }'"), function.message)
        val import = refused("main: () Void {\n    kira.math as math\n}")
        assertEquals("parse.import.nested", import.tag)
        assertTrue(import.message.contains("An import is top-level only"), import.message)
        assertEquals("parse.import.nested", refused("main: () Void {\n    Tuple2<A, B> as Pair<A, B>\n}").tag)
    }

    @Test
    fun aCapitalsNameIsAConstantsAndAPascalCaseOrLoneCapitalATypes() {
        val url = refused("URL: Any {\n    pub host: Str = \"h\"\n}")
        assertEquals("parse.type.name", url.tag)
        assertTrue(url.message.contains("'Url'"), url.message)
        assertEquals("parse.type.name", refused("URL: Any, Base").tag)
        val ctx = TyperTestSupport.unitOf(module("test:main", "K: Any {\n    pub x: Int32 = 1\n}\nV2: Struct {\n    pub y: Int32 = 0\n}\npub @_extern(c = \"LIMIT\", header = \"l.h\") LIMIT: Int32\nN: Int32 = 4"))
            .allSources().single { it.moduleName == "test:main" }
        val decls = ctx.ast.statements.mapNotNull { (it as? Statement)?.expr }
        assertEquals(listOf("K", "V2"), decls.mapNotNull { (it as? ClassDecl)?.name?.identifier?.toString()?.trim('\'') } +
            decls.filterIsInstance<net.exoad.kira.compiler.frontend.parser.ast.declarations.StructDecl>().map { it.name.identifier.toString().trim('\'') })
        assertEquals(listOf("LIMIT", "N"), decls.filterIsInstance<VariableDecl>().map { it.name.value })
    }

    @Test
    fun snakeCaseIsAnImportPathsOnly() {
        val e = refused("Pt: Struct {\n    pub x: Int32 = 1\n}\nmain: () Void {\n    p: Pt = Pt { }\n    p.my_x as Int32\n}")
        assertEquals("KiraLexer::lexIdentifier", e.tag)
        assertTrue(e.message.contains("'my_x'"), e.message)
        assertEquals("KiraLexer::lexIdentifier", refused("kira.math as my_math").tag)
        TyperTestSupport.unitOf(module("test:main", "net.exoad.bibo.dash.webdrive_fake as webdriveFake"))
    }
}
