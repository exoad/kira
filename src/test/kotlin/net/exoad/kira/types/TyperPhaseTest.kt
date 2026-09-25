package net.exoad.kira.types

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.BodyTyper
import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.ConstValue
import net.exoad.kira.compiler.analysis.types.FieldSymbol
import net.exoad.kira.compiler.analysis.types.FnParam
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.Foreign
import net.exoad.kira.compiler.analysis.types.GlobalSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.KiraTyper
import net.exoad.kira.compiler.analysis.types.ModuleGlob
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.Profile
import net.exoad.kira.compiler.analysis.types.RulePass
import net.exoad.kira.compiler.analysis.types.Severity
import net.exoad.kira.compiler.analysis.types.TraitSymbol
import net.exoad.kira.compiler.analysis.types.TypeArg
import net.exoad.kira.compiler.analysis.types.TypedModelDumper
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.TyperMode
import net.exoad.kira.compiler.analysis.types.TyperOptions
import net.exoad.kira.compiler.analysis.types.display
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ClassDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.StructDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.TraitDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.ConstTypeArg
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionDeclParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.IntegerLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.StringLiteral
import net.exoad.kira.compiler.frontend.parser.ast.statements.ReturnStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.core.CompilerIntrinsic
import net.exoad.kira.source.SourceContext
import net.exoad.kira.types.TyperTestSupport.astModule
import net.exoad.kira.types.TyperTestSupport.expectDiagnostic
import net.exoad.kira.types.TyperTestSupport.expectNoErrors
import net.exoad.kira.types.TyperTestSupport.field
import net.exoad.kira.types.TyperTestSupport.fn
import net.exoad.kira.types.TyperTestSupport.module
import net.exoad.kira.types.TyperTestSupport.param
import net.exoad.kira.types.TyperTestSupport.snippet
import net.exoad.kira.types.TyperTestSupport.ty
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Phases A and B on the forms they must handle, including the AST contract's new nodes. */
class TyperPhaseTest {
    private fun typed(unit: CompilationUnit, options: TyperOptions = TyperOptions()): TypedProgram =
        KiraTyper.run(unit, TyperMode.STRICT, options)

    private fun TypedProgram.member(uri: String, name: String) = module(uri)!!.members[name]

    // ---- the AST contract's new nodes ----------------------------------------------------

    @Test
    fun structIsCollectedAsAStructAndImplementsTraitsStatically() {
        val unit = TyperTestSupport.unitOf(module("test:shapes", "pub trait Sized {\n pub fx width: () Int32\n}"))
        val struct = StructDecl(
            ty("Band"),
            listOf(Modifier.PUBLIC),
            listOf(
                field("lo", ty("Int32"), IntegerLiteral(0), Modifier.PUBLIC),
                field("hi", ty("Int32"), null, Modifier.PUBLIC, Modifier.REQUIRE),
                fn("width", emptyList(), ty("Int32"), listOf(ReturnStatement(IntegerLiteral(1))), listOf(Modifier.PUBLIC, Modifier.OVERRIDE)),
                fn("widen", listOf(param("by", ty("Int32"))), ty("Void"), listOf(), listOf(Modifier.PUBLIC, Modifier.MUTABLE)),
            ),
            listOf(ty("Sized")),
            listOf(Statement(IntegerLiteral(0))),
        )
        astModule(unit, "test:band", Statement(net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr(Identifier("use"), emptyList(), emptyList())), struct)
        unit.getSource("test/band.kira")!!.ast.let { root ->
            // `use "test:shapes"`, so Sized is visible (built by hand like the struct).
            val withUse = net.exoad.kira.compiler.frontend.parser.ast.RootASTNode(
                listOf(root.statements[0], net.exoad.kira.compiler.frontend.parser.ast.statements.UseStatement(StringLiteral("test:shapes"))) + root.statements.drop(2)
            )
            unit.getSource("test/band.kira")!!.ast = withUse
        }
        val p = typed(unit)
        expectNoErrors(p)
        val band = p.member("test:band", "Band") as ClassSymbol
        assertEquals(ClassKind.STRUCT, band.kind)
        assertTrue(band.isStruct && band.isPub)
        assertNull(band.superclass)
        assertEquals(listOf("Sized"), band.traits.map { it.sym.name })
        assertNotNull(band.initially)
        assertNull(band.finally)
        assertEquals(listOf("lo", "hi"), band.fields.map { it.name })
        assertTrue(band.fields[1].isRequired && !band.fields[0].isRequired)
        assertEquals("0:Int32", p.model.consts[band.fields[0].default]!!.toString())
        val width = band.method("width")!!
        assertTrue(width.isOverride)
        assertEquals("Sized.width", width.overrides?.qualifiedName)
        assertFalse(width.isVirtual, "a struct method is statically dispatched (D1)")
        assertTrue(band.method("widen")!!.isMutMethod)
        assertSame(band, p.model.declSyms[struct])
    }

    @Test
    fun structCannotInheritAClass() {
        val unit = TyperTestSupport.unitOf(module("test:base", "pub class Base { }"))
        val struct = StructDecl(ty("S"), emptyList(), emptyList(), listOf(ty("Base")), null)
        val src = astModule(unit, "test:s", struct)
        src.ast = net.exoad.kira.compiler.frontend.parser.ast.RootASTNode(
            listOf(src.ast.statements[0], net.exoad.kira.compiler.frontend.parser.ast.statements.UseStatement(StringLiteral("test:base"))) + src.ast.statements.drop(1)
        )
        expectDiagnostic(typed(unit), "types.struct.inherits-class")
    }

    @Test
    fun classInitiallyAndFinallyAreRecorded() {
        val unit = CompilationUnit()
        val cls = ClassDecl(
            ty("Governor"), listOf(Modifier.PUBLIC),
            listOf(field("seconds", ty("Int32"), null, Modifier.PUBLIC, Modifier.REQUIRE)),
            emptyList(),
            initially = listOf(Statement(IntegerLiteral(1))),
            finally = listOf(Statement(IntegerLiteral(2)), Statement(IntegerLiteral(3))),
        )
        astModule(unit, "test:gov", cls)
        val p = typed(unit)
        expectNoErrors(p)
        val gov = p.member("test:gov", "Governor") as ClassSymbol
        assertEquals(1, gov.initially?.size)
        assertEquals(2, gov.finally?.size)
    }

    @Test
    fun parameterDefaultsAreKeptAndFolded() {
        val unit = CompilationUnit()
        val verb = param("verb", ty("Str"))
        val args = param("args", ty("Str"), StringLiteral(""))
        val retries = param("retries", ty("Int32"), IntegerLiteral(3))
        astModule(unit, "test:cmd", fn("command", listOf(verb, args, retries), ty("Str")))
        val p = typed(unit)
        expectNoErrors(p)
        val command = p.member("test:cmd", "command") as FnSymbol
        assertNull(command.params[0].default)
        assertSame(args.defaultValue, command.params[1].default)
        assertEquals("\"\"", p.model.consts[args.defaultValue!!].toString())
        assertEquals("3:Int32", p.model.consts[retries.defaultValue!!].toString())
    }

    @Test
    fun defaultsGoLastAndAMutParameterHasNone() {
        val unit = CompilationUnit()
        astModule(
            unit, "test:bad",
            fn("f", listOf(param("a", ty("Int32"), IntegerLiteral(1)), param("b", ty("Int32"))), ty("Void")),
            fn("g", listOf(param("out", ty("Int32"), IntegerLiteral(0), mut = true)), ty("Void")),
        )
        val p = typed(unit)
        expectDiagnostic(p, "types.param.default-order")
        expectDiagnostic(p, "types.param.mut-default")
    }

    @Test
    fun constTypeArgumentsSizeAnArr() {
        val unit = CompilationUnit()
        val magic = field("MAGIC", ty("Arr", ty("UInt8"), ConstTypeArg(IntegerLiteral(4))), null, Modifier.PUBLIC)
        astModule(unit, "test:arr", magic)
        val p = typed(unit)
        expectNoErrors(p)
        val t = (p.member("test:arr", "MAGIC") as GlobalSymbol).type as KType.Nominal
        assertEquals("Arr", t.sym.name)
        assertEquals(listOf(TypeArg.Ty(KType.UINT8), TypeArg.Const(4)), t.args)
        assertEquals("Arr<UInt8, 4>", t.display())
    }

    @Test
    fun aConstantNameInTypeArgumentPositionIsAConst() {
        val p = snippet("pub LEN: Size = 32\npub alias Frame as Arr<UInt8, LEN>\nf: Frame = [1]")
        expectNoErrors(p)
        val frame = p.workspaceModules.single().members["f"] as GlobalSymbol
        assertEquals("Arr<UInt8, 32>", frame.type.display())
    }

    @Test
    fun mutTupleElementsAreByReferenceFxParameters() {
        val unit = CompilationUnit()
        val fxType = ty("Fx", ty("Tuple2", ty("Int32", mutParam = true), ty("Str")), ty("Void"))
        astModule(unit, "test:fx", field("callback", fxType, null))
        val p = typed(unit)
        expectNoErrors(p)
        val t = (p.member("test:fx", "callback") as GlobalSymbol).type
        assertEquals(KType.Fn(listOf(FnParam(KType.INT32, true), FnParam(KType.Str, false)), KType.Void), t)
        assertEquals("Fx<Tuple2<mut Int32, Str>, Void>", t.display())
    }

    @Test
    fun overrideModifier() {
        val unit = CompilationUnit()
        val base = ClassDecl(ty("Base"), emptyList(), listOf(fn("run", emptyList(), ty("Int32"))))
        val child = ClassDecl(
            ty("Child"), emptyList(),
            listOf(
                fn("run", emptyList(), ty("Int32"), modifiers = listOf(Modifier.OVERRIDE)),
                fn("walk", emptyList(), ty("Void"), modifiers = listOf(Modifier.OVERRIDE)),
            ),
            listOf(ty("Base")),
        )
        val wrong = ClassDecl(ty("Wrong"), emptyList(), listOf(fn("run", emptyList(), ty("Str"), modifiers = listOf(Modifier.OVERRIDE))), listOf(ty("Base")))
        astModule(unit, "test:ovr", base, child, wrong)
        val p = typed(unit)
        val run = (p.member("test:ovr", "Child") as ClassSymbol).method("run")!!
        assertTrue(run.isOverride && run.isVirtual)
        assertEquals("Base.run", run.overrides?.qualifiedName)
        assertTrue((p.member("test:ovr", "Base") as ClassSymbol).method("run")!!.isVirtual)
        assertTrue((p.member("test:ovr", "Base") as ClassSymbol).isSubclassed)
        assertEquals(1, p.diagnostics.count { it.code == "types.override.nothing" }, TyperTestSupport.render(p))
        assertEquals(1, p.diagnostics.count { it.code == "types.override.signature" }, TyperTestSupport.render(p))
        assertEquals(0, p.diagnostics.count { it.code == "types.override.missing" }, TyperTestSupport.render(p))
    }

    // ---- markers -------------------------------------------------------------------------

    @Test
    fun magicOpaqueAndExternMarkers() {
        val p = snippet(
            """
            pub @_magic fx clock: () Int64;
            pub @_opaque class Window
            pub @_extern fx cSin: (x: Float64) Float64;
            """
        )
        expectNoErrors(p)
        assertEquals(Foreign.Magic("clock"), (p.member("test:main", "clock") as FnSymbol).foreign)
        val window = p.member("test:main", "Window") as ClassSymbol
        assertEquals(ClassKind.OPAQUE, window.kind)
        assertEquals(listOf("_opaque"), window.markers.map { it.name })
        assertTrue((p.member("test:main", "cSin") as FnSymbol).foreign is Foreign.Extern)
    }

    @Test
    fun membersOfMagicAndExternClassesInheritTheirForeignness() {
        val p = snippet(
            """
            pub @_extern class Car {
                pub mut fx arm: () Bool;
            }
            pub class Plain {
                pub fx run: () Void { }
            }
            """
        )
        expectNoErrors(p)
        val car = p.member("test:main", "Car") as ClassSymbol
        assertTrue(car.foreign is Foreign.Extern)
        assertEquals(Foreign.Extern(emptyMap()), car.method("arm")!!.foreign)
        assertNull((p.member("test:main", "Plain") as ClassSymbol).method("run")!!.foreign)
        val str = p.builtins.classOf(KType.Str)!!
        assertEquals(Foreign.Magic("Str.length"), str.method("length")!!.foreign)
    }

    @Test
    fun constMarkerSetsIsConst() {
        val unit = CompilationUnit()
        val crc = fn("crc", listOf(param("x", ty("UInt32"))), ty("UInt32"), listOf(ReturnStatement(Identifier("x"))))
        val src = astModule(unit, "test:crc", crc)
        src.astIntrinsicMarked[crc] = arrayOf(marker("_const"))
        val p = typed(unit)
        expectNoErrors(p)
        assertTrue((p.member("test:crc", "crc") as FnSymbol).isConst)
    }

    @Test
    fun externArgumentsAreRecordedVerbatimOnceTheParserKeepsThem() {
        // The frontend package records marker arguments in SourceContext.astIntrinsicInvocations
        // with its parser work; this branch has only its AST contract. Runs once both are merged.
        val hasInvocations = runCatching { SourceContext::class.java.getDeclaredField("astIntrinsicInvocations") }.isSuccess
        assumeTrue(hasInvocations, "the parser does not record marker arguments in this branch yet")
        val p = snippet(
            """
            @_extern(cpp = "bibo::Car", header = "car.hxx")
            pub class Car {
                pub fx ok: () Bool;
            }
            @_extern("c_cos")
            pub fx cosine: (x: Float64) Float64;
            """
        )
        assertEquals(Foreign.Extern(mapOf("cpp" to "bibo::Car", "header" to "car.hxx")), (p.member("test:main", "Car") as ClassSymbol).foreign)
        assertEquals(Foreign.Extern(mapOf("symbol" to "c_cos")), (p.member("test:main", "cosine") as FnSymbol).foreign)
    }

    private fun marker(name: String): CompilerIntrinsic = object : CompilerIntrinsic(name, emptySet()) {
        override fun validate(invocation: net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr, compilationUnit: CompilationUnit, context: SourceContext) {}
        override fun apply(
            invocation: net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr,
            target: net.exoad.kira.compiler.frontend.parser.ast.ASTNode,
            compilationUnit: CompilationUnit,
            context: SourceContext,
        ): net.exoad.kira.compiler.frontend.parser.ast.ASTNode = target
    }

    // ---- symbols, scopes, whole-program facts --------------------------------------------

    @Test
    fun declaredNamesMapToTheirOwnSymbols() {
        val p = snippet(
            """
            fx first: (x: Int32) Int32 {
                return x
            }
            fx second: (x: Str) Str {
                return x
            }
            """
        )
        val first = p.member("test:main", "first") as FnSymbol
        val second = p.member("test:main", "second") as FnSymbol
        val a = first.params.single()
        val b = second.params.single()
        assertNotSame(a, b)
        val nameA = (a.decl as FunctionDeclParameterExpr).name
        val nameB = (b.decl as FunctionDeclParameterExpr).name
        assertEquals(nameA, nameB, "equal by value...")
        assertSame(a, p.model.refs[nameA], "...but two entries")
        assertSame(b, p.model.refs[nameB])
        assertSame(a, p.model.declSyms[a.decl!!])
        assertEquals(KType.INT32, a.type)
        assertEquals(KType.Str, b.type)
        assertTrue(p.model.typeRefs.containsKey((a.decl as FunctionDeclParameterExpr).typeSpecifier))
    }

    @Test
    fun genericBoundsAndMethodTypeParameters() {
        val p = snippet(
            """
            pub trait Named {
                pub fx name: () Str
            }
            pub class Holder<T: Named> {
                require pub item: T
                pub fx map<U>: (f: Fx<Tuple1<T>, U>) U {
                    return f(item)
                }
            }
            """
        )
        expectNoErrors(p)
        val holder = p.member("test:main", "Holder") as ClassSymbol
        val t = holder.typeParams.single()
        assertEquals(listOf("Named"), t.bounds.map { it.display() })
        val map = holder.method("map")!!
        assertEquals("Fx<Tuple1<T>, U>", map.params.single().type.display())
        assertEquals("U", map.ret.display())
        assertSame(map, map.typeParams.single().owner)
    }

    @Test
    fun traitMethodsFlattenWithDefaultsAndGenericTraitsSubstitute() {
        val p = snippet(
            """
            pub trait Keyed<K> {
                pub fx key: () K
                pub fx describe: () Str {
                    return "keyed"
                }
            }
            pub trait Named: Keyed<Str> {
                pub fx name: () Str
            }
            pub class Tag: Named {
                require pub label: Str
                pub fx key: () Str {
                    return label
                }
                pub fx name: () Str {
                    return label
                }
            }
            pub class Broken: Keyed<Int32> {
                pub fx key: () Str {
                    return "no"
                }
            }
            """
        )
        val named = p.member("test:main", "Named") as TraitSymbol
        assertEquals(listOf("Keyed.key", "Keyed.describe", "Named.name"), named.flatMethods.map { it.qualifiedName })
        assertTrue(named.flatMethods[1].hasBody)
        val tag = p.member("test:main", "Tag") as ClassSymbol
        assertEquals("Keyed.key", tag.method("key")!!.overrides?.qualifiedName)
        assertEquals(1, p.diagnostics.count { it.code == "types.override.signature" }, "Broken.key returns Str, Keyed<Int32> wants Int32:\n${TyperTestSupport.render(p)}")
    }

    @Test
    fun builtinNamesResolveWhetherOrNotTheStdlibDeclaresThem() {
        val absent = snippet("v: View<UInt8> = 1\nr: Ref<Int32> = 1\nw: Weak<Str> = 1\nu: Unsafe<Int32> = 1\nm: MutView<Char> = 1")
        expectNoErrors(absent)
        val view = (absent.member("test:main", "v") as GlobalSymbol).type as KType.Nominal
        assertEquals("View<UInt8>", view.display())
        assertEquals(ClassKind.MAGIC, (view.sym as ClassSymbol).kind)

        val declared = TyperTestSupport.type(
            module("kira:aaa", "pub @_magic class View<T> {\n    pub fx size: () Size;\n}"),
            module("test:main", "v: View<UInt8> = 1"),
        )
        expectNoErrors(declared)
        val sym = ((declared.member("test:main", "v") as GlobalSymbol).type as KType.Nominal).sym as ClassSymbol
        assertEquals("kira:aaa", sym.module.uri, "the first stdlib module (by URI) that declares it is the builtin; kira:aaa sorts first")
        assertFalse(declared.builtins.isSynthesized(sym))
        assertEquals(KType.SIZE, sym.method("size")!!.ret)
    }

    @Test
    fun aUserTypeShadowsABuiltinName() {
        val p = snippet("pub class View { }\nv: View = View { }")
        expectNoErrors(p)
        val t = (p.member("test:main", "v") as GlobalSymbol).type as KType.Nominal
        assertEquals(ClassKind.CLASS, (t.sym as ClassSymbol).kind)
    }

    @Test
    fun profileHeaderOnlyAndNamespacesComeFromTheOptions() {
        val options = TyperOptions(
            freestanding = listOf("firmware:lib.**"),
            headerOnly = listOf("firmware:pilot.src.{scan,speed}"),
            namespaces = mapOf("firmware:lib.text" to "bibo::text", "firmware:pilot.src.bibowire.*" to "bibowire"),
        )
        val p = TyperTestSupport.type(
            module("firmware:lib.text", "x: Int32 = 1"),
            module("firmware:lib.chassis.cal", "x: Int32 = 1"),
            module("firmware:pilot.src.scan", "x: Int32 = 1"),
            module("firmware:pilot.src.bibowire.control", "x: Int32 = 1"),
            module("firmware:pilot.src.proto", "x: Int32 = 1"),
            options = options,
        )
        val text = p.module("firmware:lib.text")!!
        assertEquals(Profile.FREESTANDING, text.profile)
        assertEquals("bibo::text", text.cppNamespace)
        assertEquals(Profile.FREESTANDING, p.module("firmware:lib.chassis.cal")!!.profile)
        assertEquals("cal", p.module("firmware:lib.chassis.cal")!!.cppNamespace)
        assertTrue(p.module("firmware:pilot.src.scan")!!.isHeaderOnly)
        assertEquals(Profile.HOSTED, p.module("firmware:pilot.src.scan")!!.profile)
        assertEquals("bibowire", p.module("firmware:pilot.src.bibowire.control")!!.cppNamespace)
        val proto = p.module("firmware:pilot.src.proto")!!
        assertFalse(proto.isHeaderOnly)
        assertEquals("proto", proto.cppNamespace)
        assertEquals("kira::core", p.module("kira:core")!!.cppNamespace)
    }

    @Test
    fun moduleGlobs() {
        assertTrue(ModuleGlob.matches("firmware:lib.**", "firmware:lib.text"))
        assertTrue(ModuleGlob.matches("firmware:lib.**", "firmware:lib.chassis.cal"))
        assertTrue(ModuleGlob.matches("firmware:lib.**", "firmware:lib"), "a trailing .** matches the prefix module")
        assertFalse(ModuleGlob.matches("firmware:lib.**", "firmware:library"))
        assertTrue(ModuleGlob.matches("firmware:pilot.src.bibowire.*", "firmware:pilot.src.bibowire.control"))
        assertFalse(ModuleGlob.matches("firmware:pilot.src.bibowire.*", "firmware:pilot.src.bibowire.control.deep"))
        assertTrue(ModuleGlob.matches("firmware:pilot.src.{scan,speed}", "firmware:pilot.src.speed"))
        assertFalse(ModuleGlob.matches("firmware:pilot.src.{scan,speed}", "firmware:pilot.src.speedy"))
        assertEquals("exact", ModuleGlob.lookup(linkedMapOf("a:*" to "glob", "a:b" to "exact"), "a:b"))
    }

    @Test
    fun anInternalFailureBecomesADiagnosticAndTheTyperStillReturns() {
        val saved = KiraTyper.bodyTyper
        KiraTyper.bodyTyper = object : BodyTyper {
            override fun type(program: TypedProgram) = throw IllegalStateException("boom")
        }
        try {
            val p = snippet("x: Int32 = 1")
            val d = expectDiagnostic(p, "types.internal")
            assertTrue(d.message.contains("boom") && d.message.contains("body typing"), d.message)
            assertEquals(KType.INT32, (p.member("test:main", "x") as GlobalSymbol).type, "phases A and B still ran")
        } finally {
            KiraTyper.bodyTyper = saved
        }
    }

    @Test
    fun rulePassesRunInOrderAndReport() {
        val seen = mutableListOf<String>()
        val pass = object : RulePass {
            override val name = "probe"
            override fun run(program: TypedProgram) {
                seen.add(program.workspaceModules.joinToString { it.uri })
                program.report("rules.probe.seen", "saw it", null, Severity.NOTE)
            }
        }
        KiraTyper.rulePasses.add(pass)
        try {
            val p = snippet("x: Int32 = 1")
            assertEquals(listOf("test:main"), seen)
            expectDiagnostic(p, "rules.probe.seen")
        } finally {
            KiraTyper.rulePasses.remove(pass)
        }
    }

    @Test
    fun theExpressionDumpShowsFoldedConstants() {
        val p = snippet("pub LIMIT: Int32 = 2 + 3\npub NAME: Str = \"a\" + \"b\"")
        val source = p.module("test:main")!!.source
        val dump = TypedModelDumper.dump(p, source)
        val lines = dump.lines()
        assertTrue(lines.any { it.startsWith("main.kira:3:20  2 + 3  ?  const=5:Int32") }, dump)
        assertTrue(lines.any { it.contains("\"a\" + \"b\"  ?  const=\"ab\"") }, dump)
        val all = TypedModelDumper.dump(p, source, includeUntyped = true)
        assertTrue(all.lines().size >= lines.size)
    }

    @Test
    fun astTreeFindsChildrenOfNewNodesAndDefaultedFields() {
        val default = IntegerLiteral(7)
        val p = param("n", ty("Int32"), default)
        assertTrue(AstTree.children(p).any { it === default }, "FunctionDeclParameterExpr.defaultValue")
        val init = Statement(IntegerLiteral(1))
        val cls = ClassDecl(ty("C"), emptyList(), emptyList(), emptyList(), initially = listOf(init))
        assertTrue(AstTree.children(cls).any { it === init }, "ClassDecl.initially")
        val hole = Identifier("x")
        val interp = net.exoad.kira.compiler.frontend.parser.ast.literals.InterpolatedStringLiteral(
            listOf(
                net.exoad.kira.compiler.frontend.parser.ast.literals.InterpolationPart.Text("v="),
                net.exoad.kira.compiler.frontend.parser.ast.literals.InterpolationPart.Hole(hole),
            )
        )
        assertTrue(AstTree.children(interp).any { it === hole }, "an interpolation hole")
        val trait = TraitDecl(ty("T"), emptyArray(), emptyList())
        assertTrue(AstTree.children(trait).isNotEmpty())
    }

    @Test
    fun fieldsKnowTheirOwnerAndIndex() {
        val p = snippet("pub class P {\n    require pub x: Int32\n    pub mut y: Int32 = 2\n}")
        val cls = p.member("test:main", "P") as ClassSymbol
        val y: FieldSymbol = cls.field("y")!!
        assertSame(cls, y.owner)
        assertEquals(1, y.index)
        assertTrue(y.isMut && y.isPub && !y.isRequired)
        assertEquals("P.y", y.qualifiedName)
        assertEquals(ConstValue.IntConst(2, net.exoad.kira.compiler.analysis.types.Prim.INT32), p.model.consts[y.default!!])
        val param: ParamSymbol? = null
        assertNull(param)
    }
}
