package net.exoad.kira.types.body

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.CallKind
import net.exoad.kira.compiler.analysis.types.Capture
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.Coercion
import net.exoad.kira.compiler.analysis.types.ConstValue
import net.exoad.kira.compiler.analysis.types.FieldInit
import net.exoad.kira.compiler.analysis.types.FieldSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.Foreign
import net.exoad.kira.compiler.analysis.types.GlobalSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.LocalSymbol
import net.exoad.kira.compiler.analysis.types.LoopKind
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.Place
import net.exoad.kira.compiler.analysis.types.Prim
import net.exoad.kira.compiler.analysis.types.TyperMode
import net.exoad.kira.compiler.analysis.types.display
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IfExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.statements.ForIterationStatement
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BinaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.types.TyperTestSupport
import net.exoad.kira.types.TyperTestSupport.expectNoErrors
import net.exoad.kira.types.TyperTestSupport.snippet
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The tables TypedModelDumper does not print, checked one fact at a time: captures, loop
 * plans, if shapes, argument bindings (defaults, source order, `mut`), constructions,
 * inferred type arguments, the two call shapes, operator overloads and D39's Result.
 */
class TyperBodyModelTest {
    private fun closures() = TyperTestSupport.project(File("src/test/resources/cpp-golden/closures"), TyperMode.STRICT)

    private fun capturesOf(p: net.exoad.kira.compiler.analysis.types.TypedProgram, lambda: LambdaExpr): List<String> =
        p.model.captures[lambda]!!.map {
            when (it) {
                is Capture.Value -> "value ${it.symbol.name}"
                is Capture.Field -> "field ${it.field.qualifiedName}"
                is Capture.This -> "this ${it.owner.name}"
            }
        }

    private fun lambdasIn(p: net.exoad.kira.compiler.analysis.types.TypedProgram, uri: String): List<LambdaExpr> =
        BodyTestSupport.every(p, uri)

    // ---- captures (design 5.6) ---------------------------------------------------------------

    @Test
    fun theClosuresGoldenCapturesEachWayTheDesignTableLists() {
        val p = closures()
        expectNoErrors(p)
        val all = lambdasIn(p, "lang:closures").map { capturesOf(p, it) }
        assertEquals(
            listOf(
                listOf("value k"),            // scaleBy: a free function capturing its parameter: [k]
                listOf("value k"),            // addAll: a lambda passed to a template parameter
                listOf("field Gain.k"),       // Gain.scaler: a struct field is copied: [c_k = k]
                listOf("this Gain"),          // Gain.twicer: a struct method call copies the struct: [*this]
                listOf("this Counter"),       // Counter.offset: a class's field through `this`
                listOf("this Counter"),       // Counter.multiplier: the same, escaping
                listOf("value counter"),      // tally: a Ref<T> is captured by value, shared by reference
            ),
            all,
        )
    }

    @Test
    fun aNestedLambdaCapturesThroughEveryLambdaInBetween() {
        val p = snippet(
            """
            pub fx nested: (base: Int32) Fx<Tuple1<Int32>, Fx<Tuple1<Int32>, Int32>> {
                return fx(a: Int32) Fx<Tuple1<Int32>, Int32> {
                    return fx(b: Int32) Int32 {
                        return base + a + b
                    }
                }
            }
            """
        )
        expectNoErrors(p)
        val (outer, inner) = lambdasIn(p, "test:main")
        assertEquals(listOf("value base"), capturesOf(p, outer), "the outer lambda holds base for the inner one")
        assertEquals(listOf("value base", "value a"), capturesOf(p, inner))
    }

    @Test
    fun aLambdaParameterAndLocalAreNotCaptures() {
        val p = snippet(
            """
            pub fx f: () Fx<Tuple1<Int32>, Int32> {
                return fx(x: Int32) Int32 {
                    y: Int32 = x + 1
                    return y
                }
            }
            """
        )
        expectNoErrors(p)
        assertEquals(emptyList(), capturesOf(p, lambdasIn(p, "test:main").single()))
        val param = p.model.declSyms.values.filterIsInstance<ParamSymbol>().single { it.name == "x" }
        assertEquals(KType.INT32, param.type)
    }

    @Test
    fun writingThroughARefCaptureIsNotWritingTheCapture() {
        val p = snippet(
            """
            pub fx tally: () Fx<Tuple0, Int32> {
                counter: Ref<Int32> = Ref<Int32> { value = 0 }
                return fx() Int32 {
                    counter.value = counter.value + 1
                    return counter.value
                }
            }
            """
        )
        expectNoErrors(p)
    }

    @Test
    fun aStructLambdaWritingAFieldWritesItsCopy() {
        val p = snippet(
            """
            pub struct S {
                pub k: Int32 = 0
                pub mut fx f: () Fx<Tuple0, Void> {
                    return fx() Void {
                        k = 1
                    }
                }
            }
            """
        )
        TyperTestSupport.expectDiagnostic(p, "types.lambda.assign-capture")
    }

    // ---- loops (design 5.4) ------------------------------------------------------------------

    @Test
    fun everyIterableGetsItsLoopPlan() {
        val p = snippet(
            """
            pub fx loops: (xs: List<Int32>, arr: Arr<UInt8, 4>, v: View<Char>, m: Map<Str, Int32>, s: Set<Int64>, n: Int32) Void {
                for i: Size in 0..4 { }
                for x: Int32 in xs { }
                for b: UInt8 in arr { }
                for c: Char in v { }
                for e: Tuple2<Str, Int32> in m { }
                for k: Int64 in s { }
                for mut j: 0..n { }
            }
            """
        )
        assertEquals(listOf("types.for.legacy"), p.diagnostics.map { it.code })
        val plans = BodyTestSupport.every<ForIterationStatement>(p).map { p.model.loops[it]!! }
        assertEquals(
            listOf(LoopKind.RANGE, LoopKind.LIST, LoopKind.ARR, LoopKind.VIEW, LoopKind.MAP, LoopKind.SET, LoopKind.RANGE_INCLUSIVE),
            plans.map { it.kind },
        )
        assertEquals(listOf("Size", "Int32", "UInt8", "Char", "Tuple2<Str, Int32>", "Int64", "Int32"), plans.map { it.element.display() })
        assertEquals(listOf(false, false, false, false, false, false, true), plans.map { it.isLegacy })
        val vars = plans.map { it.variable as LocalSymbol }
        assertEquals(listOf("i", "x", "b", "c", "e", "k", "j"), vars.map { it.name })
        assertEquals(listOf(false, false, false, false, false, false, true), vars.map { it.isMut }, "only the legacy `for mut` variable is mut")
        BodyTestSupport.every<ForIterationStatement>(p).forEachIndexed { i, s ->
            assertSame(vars[i], p.model.declSyms[s.forIterationExpr])
            assertSame(vars[i], p.model.refs[s.forIterationExpr.initializer])
        }
    }

    // ---- if-expressions (R18) ----------------------------------------------------------------

    @Test
    fun anIfExpressionIsATernaryWhenEveryBranchIsOneExpression() {
        val p = snippet(
            """
            pub fx f: (c: Bool, a: Int32) Int32 {
                x: Int32 = if c { 1 } else { 2 }
                y: Int32 = if c { 1 } else if a > 0 { 2 } else { 3 }
                z: Int32 = if c {
                    t: Int32 = a * 2
                    t
                } else {
                    0
                }
                return x + y + z
            }
            """
        )
        expectNoErrors(p)
        val shapes = BodyTestSupport.every<IfExpr>(p).map { p.model.ifShape[it] }
        assertEquals(listOf(true, true, true, false), shapes, "x, y and the nested else-if are ternaries; z has a statement in a branch")
    }

    // ---- bindings (R6, D4, D33) --------------------------------------------------------------

    private val calls = """
        pub LIMIT: Int32 = 3
        pub fx sub: (a: Int32, b: Int32) Int32 {
            return a - b
        }
        pub fx command: (verb: Str, args: Str = "", retries: Int32 = LIMIT) Str {
            return verb
        }
        pub fx bump: (mut value: Int32, by: Int32 = 1) Void {
            value += by
        }
        pub fx caller: () Void {
            r1: Int32 = sub(b = 3, a = 10)
            r2: Str = command("go")
            r3: Str = command("go", retries = 5)
            mut n: Int32 = 0
            bump(mut n)
        }
    """

    private fun binding(a: ArgBinding): String = when (a) {
        is ArgBinding.Given -> "given ${net.exoad.kira.compiler.analysis.types.KiraUnparser.text(a.expr)}${if (a.byRef) " byRef" else ""}"
        is ArgBinding.Default -> "default ${a.param.name}${if (a.trailing) " trailing" else " filled"}"
    }

    @Test
    fun argumentsAreInParameterOrderAndSourceOrderIsHowTheyWereWritten() {
        val p = snippet(calls)
        expectNoErrors(p)
        val sub = p.model.calls[BodyTestSupport.node<FunctionCallExpr>(p, "sub(b = 3, a = 10)")]!!
        assertEquals(listOf("given 10", "given 3"), sub.args.map(::binding))
        assertEquals(listOf(1, 0), sub.sourceOrder, "b was written first")
        assertEquals(CallKind.FREE, sub.kind)
        assertEquals(KType.INT32, sub.returnType)
    }

    @Test
    fun omittedDefaultsAreTrailingOrFilledAtTheCall() {
        val p = snippet(calls)
        val trailing = p.model.calls[BodyTestSupport.node<FunctionCallExpr>(p, "command(\"go\")")]!!
        assertEquals(listOf("given \"go\"", "default args trailing", "default retries trailing"), trailing.args.map(::binding))
        assertEquals(listOf(0), trailing.sourceOrder)
        val middle = p.model.calls[BodyTestSupport.node<FunctionCallExpr>(p, "command(\"go\", retries = 5)")]!!
        assertEquals(listOf("given \"go\"", "default args filled", "given 5"), middle.args.map(::binding))
        assertEquals(listOf(0, 2), middle.sourceOrder)
    }

    @Test
    fun aMutArgumentIsGivenByReferenceAndIsAPlace() {
        val p = snippet(calls)
        val call = BodyTestSupport.node<FunctionCallExpr>(p, "bump(mut n)")
        val rc = p.model.calls[call]!!
        assertEquals(listOf("given n byRef", "default by trailing"), rc.args.map(::binding))
        val n = (rc.args[0] as ArgBinding.Given).expr
        assertIs<Place.Local>(p.model.places[n])
    }

    // ---- generics (D20) ----------------------------------------------------------------------

    @Test
    fun anInferMathCallTakesItsTypeFromTheArgumentThenTheContext() {
        val p = snippet(
            """
            pub fx f: (x: Float32) Float32 {
                a: Float32 = sqrt(x)
                b: Float64 = sin(1.0)
                c: Float32 = floor(0.5)
                d: Float32 = min(x, 2.0)
                return a + c + d
            }
            """
        )
        expectNoErrors(p)
        fun typeArgs(text: String) = p.model.calls[BodyTestSupport.node<FunctionCallExpr>(p, text)]!!.typeArgs.map { it.display() }
        assertEquals(listOf("Float32"), typeArgs("sqrt(x)"))
        assertEquals(listOf("Float64"), typeArgs("sin(1.0)"), "a bare literal's own default")
        assertEquals(listOf("Float32"), typeArgs("floor(0.5)"), "the expected type, before the literal's default")
        assertEquals(listOf("Float32"), typeArgs("min(x, 2.0)"))
        val sqrt = p.model.calls[BodyTestSupport.node<FunctionCallExpr>(p, "sqrt(x)")]!!
        assertEquals(CallKind.MAGIC, sqrt.kind)
        assertEquals(KType.FLOAT32, sqrt.returnType)
        assertEquals(KType.FLOAT32, sqrt.substitution.values.single())
    }

    @Test
    fun anExplicitGenericCallRecordsItsTypeArguments() {
        val p = snippet(
            """
            fx id<T>: (value: T) T {
                return value
            }
            pub fx f: () Int32 {
                return id<Int32>(7)
            }
            """
        )
        expectNoErrors(p)
        val rc = p.model.calls[BodyTestSupport.node<FunctionCallExpr>(p, "id<Int32>(7)")]!!
        assertEquals(listOf(KType.INT32), rc.typeArgs)
        assertEquals(KType.INT32, rc.returnType)
    }

    // ---- receivers, shapes, kinds ------------------------------------------------------------

    @Test
    fun aMethodCallRecordsItsReceiverAndAnImplicitThisCallHasNone() {
        val p = snippet(
            """
            pub class Dog {
                pub fx sound: () Str {
                    return "woof"
                }
                pub fx twice: () Str {
                    return sound() + sound()
                }
            }
            pub fx f: (d: Dog) Str {
                return d.sound()
            }
            """
        )
        expectNoErrors(p)
        val explicit = p.model.calls[BodyTestSupport.node<FunctionCallExpr>(p, "d.sound()")]!!
        assertEquals(CallKind.METHOD, explicit.kind)
        assertEquals("d", (explicit.receiver as Identifier).value)
        assertFalse(explicit.implicitThis)
        val implicit = BodyTestSupport.all<FunctionCallExpr>(p, "sound()").map { p.model.calls[it]!! }
        assertEquals(2, implicit.size)
        assertTrue(implicit.all { it.implicitThis && it.receiver == null && it.kind == CallKind.METHOD })
    }

    @Test
    fun bothParserShapesOfAMemberCallResolveAlike() {
        // MemberAccessExpr(origin, FunctionCallExpr(m)) is the CONJUNCTIVE_DOT shape; build it by hand.
        val unit = TyperTestSupport.unitOf()
        val s = net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier("s")
        val inner = FunctionCallExpr(Identifier("length"), emptyList(), emptyList())
        val outer = net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr(s, inner)
        TyperTestSupport.astModule(
            unit, "test:shape",
            TyperTestSupport.fn(
                "len", listOf(TyperTestSupport.param("s", TyperTestSupport.ty("Str"))), TyperTestSupport.ty("Size"),
                listOf(net.exoad.kira.compiler.frontend.parser.ast.statements.ReturnStatement(outer)),
            ),
        )
        val p = net.exoad.kira.compiler.analysis.types.KiraTyper.run(unit, TyperMode.STRICT)
        expectNoErrors(p)
        val rc = p.model.calls[inner]!!
        assertEquals(CallKind.MAGIC, rc.kind)
        assertEquals("Str.length", rc.fn!!.qualifiedName)
        assertSame(s, rc.receiver)
        assertEquals(KType.SIZE, p.model.types[outer])
        assertEquals(KType.SIZE, p.model.types[inner])
    }

    @Test
    fun anOperatorOverloadIsAnOpCall() {
        val p = snippet(
            """
            pub struct V {
                pub x: Int32 = 0
            }
            fx @op_add: (a: V, b: V) V {
                return V { a.x + b.x }
            }
            pub fx f: (a: V, b: V) V {
                return a + b
            }
            """
        )
        expectNoErrors(p)
        val add = BodyTestSupport.node<BinaryExpr>(p, "a + b")
        val rc = p.model.opCalls[add]!!
        assertEquals(CallKind.OP_OVERLOAD, rc.kind)
        assertEquals("op_add", rc.fn!!.name)
        assertEquals("V", p.model.types[add]!!.display())
    }

    @Test
    fun resultSuccessAndErrorTakeTheirTypeFromTheContext() {
        val p = snippet(
            """
            pub fx f: (ok: Bool) Result<Int32, Str> {
                if ok {
                    return Result.success(5)
                }
                return Result.error("no")
            }
            """
        )
        expectNoErrors(p)
        val success = p.model.calls[BodyTestSupport.node<FunctionCallExpr>(p, "Result.success(5)")]!!
        assertEquals(CallKind.MAGIC, success.kind)
        assertEquals(Foreign.Magic("Result.success"), success.fn!!.foreign)
        assertEquals("Result<Int32, Str>", success.returnType.display())
        val error = p.model.calls[BodyTestSupport.node<FunctionCallExpr>(p, "Result.error(\"no\")")]!!
        assertEquals(Foreign.Magic("Result.error"), error.fn!!.foreign)
    }

    @Test
    fun strOfIsCalledOnTheTypeAsResultSuccessIs() {
        val p = snippet(
            """
            pub fx f: (v: View<UInt8>, xs: List<UInt8>) Str {
                return Str.of(v) + Str.of(xs)
            }
            """
        )
        expectNoErrors(p)
        val of = p.model.calls[BodyTestSupport.node<FunctionCallExpr>(p, "Str.of(v)")]!!
        assertEquals(CallKind.MAGIC, of.kind)
        assertEquals(Foreign.Magic("Str.of"), of.fn!!.foreign)
        assertEquals("Str", of.returnType.display())
        assertEquals(null, of.receiver)
        val fromList = p.model.calls[BodyTestSupport.node<FunctionCallExpr>(p, "Str.of(xs)")]!!
        assertEquals(of.fn, fromList.fn, "one FnSymbol, as Result.success has")
        val wrong = snippet(
            """
            pub fx g: (s: Str) Str {
                return Str.of(s) + Str.from(s)
            }
            """
        )
        val codes = net.exoad.kira.types.TyperTestSupport.codes(wrong)
        assertTrue(codes.any { it.startsWith("types.") && it != "types.call.static" }, "Str.of(a Str) is a type error: $codes")
        assertTrue("types.call.static" in codes, "any other call on Str is still refused: $codes")
    }

    @Test
    fun jsonsConstructorsAreCalledOnTheTypeAndOfIsChosenByItsArgument() {
        val p = snippet(
            """
            pub fx f: (b: Bool, i: Int64, d: Float64, s: Str, m: Maybe<Float64>, t: Str) Json {
                o: Json = Json.obj()
                o.put("a", Json.of(5))
                o.put("b", Json.of(-2.5))
                o.put("c", Json.of(b))
                o.put("d", Json.of(i))
                o.put("e", Json.of(d))
                o.put("f", Json.of(s))
                o.put("g", Json.of(m))
                o.put("h", Json.of(value = i))
                a: Json = Json.arr()
                a.add(Json.null())
                o.put("i", a)
                o.put("j", Json.parse(text = t))
                o.put("k", Json.of(Json.error()))
                return o
            }
            """
        )
        expectNoErrors(p)
        fun keyOf(text: String): String {
            val rc = p.model.calls[BodyTestSupport.node<FunctionCallExpr>(p, text)]!!
            assertEquals(CallKind.MAGIC, rc.kind, text)
            assertEquals(null, rc.receiver, text)
            return (rc.fn!!.foreign as Foreign.Magic).key
        }
        assertEquals("Json.obj", keyOf("Json.obj()"))
        assertEquals("Json.of(Int64)", keyOf("Json.of(5)"))
        assertEquals("Json.of(Float64)", keyOf("Json.of(-2.5)"))
        assertEquals("Json.of(Bool)", keyOf("Json.of(b)"))
        assertEquals("Json.of(Int64)", keyOf("Json.of(i)"))
        assertEquals("Json.of(Float64)", keyOf("Json.of(d)"))
        assertEquals("Json.of(Str)", keyOf("Json.of(s)"))
        assertEquals("Json.of(Maybe<Float64>)", keyOf("Json.of(m)"))
        assertEquals("Json.of(Int64)", keyOf("Json.of(value = i)"))
        assertEquals("Json.null", keyOf("Json.null()"))
        assertEquals("Json.parse", keyOf("Json.parse(text = t)"))
        assertEquals("Json.error", keyOf("Json.error()"))
        assertEquals("Json", p.model.calls[BodyTestSupport.node<FunctionCallExpr>(p, "Json.parse(text = t)")]!!.returnType.display())
        assertEquals("Str", p.model.calls[BodyTestSupport.node<FunctionCallExpr>(p, "Json.error()")]!!.returnType.display())
        val wrong = snippet(
            """
            pub fx g: (n: Int32, x: Float32) Void {
                a: Json = Json.of(n)
                b: Json = Json.of(x)
                c: Json = Json.of(null)
                d: Json = Json.of([1, 2])
                e: Json = Json { }
                f: Json = Json.make()
            }
            """
        )
        val codes = net.exoad.kira.types.TyperTestSupport.codes(wrong)
        assertEquals(2, codes.count { it == "types.json.of" }, "a null and a List are no Json.of argument: $codes")
        assertTrue(codes.size >= 6, "an Int32 and a Float32 are not widened to an Int64 and a Float64: $codes")
        assertTrue("types.init.not-constructible" in codes, "Json { } is refused: $codes")
        assertTrue("types.call.static" in codes, "any other call on Json is refused: $codes")
    }

    @Test
    fun theCompilerMadeCallablesTakeNoTypeArgumentsAndBindNamedOnes() {
        val typed = snippet(
            """
            pub fx a: (v: View<UInt8>) Str {
                return Str.of<Float64, Bool, Str>(v)
            }

            pub fx b: () Result<Int32, Str> {
                return Result.success<Int32>(5)
            }
            """
        )
        val diags = typed.diagnostics.filter { it.code == "types.call.type-args" }.map { it.message }
        assertTrue(diags.any { it.startsWith("'Str.of' takes no type arguments.") }, diags.toString())
        assertTrue(diags.any { it.startsWith("'Result.success' takes no type arguments.") }, diags.toString())
        val named = snippet(
            """
            pub fx c: (v: View<UInt8>) Str {
                return Str.of(bytes = v)
            }

            pub fx d: () Result<Int32, Str> {
                return Result.success(value = 5)
            }
            """
        )
        expectNoErrors(named)
        val of = named.model.calls[BodyTestSupport.node<FunctionCallExpr>(named, "Str.of(bytes = v)")]!!
        assertEquals(Foreign.Magic("Str.of"), of.fn!!.foreign)
        val wrongName = snippet(
            """
            pub fx e: (v: View<UInt8>) Str {
                return Str.of(text = v)
            }
            """
        )
        val messages = wrongName.diagnostics.map { it.message }
        assertTrue(messages.any { it.contains("'Str.of' has no parameter named 'text'") }, messages.toString())
    }

    // ---- construction (R9) -------------------------------------------------------------------

    @Test
    fun aConstructionListsEveryFieldInheritedFirstInDeclarationOrder() {
        val p = snippet(
            """
            pub class Animal {
                require pub name: Str
                pub mut age: Int32 = 0
            }
            pub class Dog: Animal {
                pub mut tricks: Int32 = 0
            }
            pub fx f: () Dog {
                return Dog { "Rex", tricks = 2 }
            }
            """
        )
        expectNoErrors(p)
        val init = p.model.inits[BodyTestSupport.node<ObjectInitExpr>(p, "Dog { \"Rex\", tricks = 2 }")]!!
        assertEquals("Dog", (init.cls as ClassSymbol).name)
        assertEquals(listOf("Animal.name", "Animal.age", "Dog.tricks"), init.fields.map { it.field.qualifiedName })
        assertEquals(listOf("given", "default", "given named"), init.fields.map {
            when (it) {
                is FieldInit.Given -> if (it.named) "given named" else "given"
                is FieldInit.Default -> "default"
            }
        })
        assertEquals(listOf(0, 2), init.sourceOrder)
    }

    @Test
    fun aMagicContainerConstructsEmptyAndAGenericClassSubstitutes() {
        val p = snippet(
            """
            pub class Box<T> {
                require pub value: T
            }
            pub fx f: () Box<Int32> {
                xs: List<Str> = List<Str> { }
                return Box<Int32> { value = 5 }
            }
            """
        )
        expectNoErrors(p)
        val list = p.model.inits[BodyTestSupport.node<ObjectInitExpr>(p, "List<Str> { }")]!!
        assertEquals("List", list.cls!!.name)
        assertTrue(list.fields.all { it is FieldInit.Default })
        val box = p.model.inits[BodyTestSupport.node<ObjectInitExpr>(p, "Box<Int32> { value = 5 }")]!!
        assertEquals(KType.INT32, box.substitution.values.single())
    }

    // ---- module level ------------------------------------------------------------------------

    @Test
    fun aConstantPhaseBCouldNotFoldGetsItsValueFromPhaseC() {
        val p = snippet("pub ONE: Float32 = 1\npub TWO: Float32 = ONE + 1")
        expectNoErrors(p)
        val one = p.workspaceModules.single().members["ONE"] as GlobalSymbol
        assertEquals(ConstValue.FloatConst(1.0, Prim.FLOAT32), one.constValue)
    }

    @Test
    fun aStrConstantOfLiteralsFolds() {
        val p = snippet("pub A: Str = \"a\"\npub AB: Str = A + \"b\"")
        expectNoErrors(p)
        assertEquals(ConstValue.StrConst("ab"), (p.workspaceModules.single().members["AB"] as GlobalSymbol).constValue)
    }

    @Test
    fun theThreeEntryPointsAreAcceptedAndNothingElse() {
        listOf("fx main: () Void { }", "fx main: () Int32 {\n    return 0\n}", "fx main: (args: List<Str>) Int32 {\n    return 0\n}").forEach {
            expectNoErrors(snippet(it))
        }
        TyperTestSupport.expectDiagnostic(snippet("fx main: (n: Int32) Void { }"), "types.main.signature")
        TyperTestSupport.expectDiagnostic(snippet("fx main: () Str {\n    return \"\"\n}"), "types.main.signature")
    }

    @Test
    fun localsAndHandlerVariablesAreDeclaredSymbols() {
        val p = snippet(
            """
            pub fx f: () Void {
                mut a: Int32 = 1
                try {
                    a += 1
                } on e: Str {
                    trace(e)
                }
            }
            """
        )
        expectNoErrors(p)
        val locals = p.model.declSyms.values.filterIsInstance<LocalSymbol>().associateBy { it.name }
        assertTrue(locals.getValue("a").isMut)
        assertEquals(KType.Str, locals.getValue("e").type)
        assertFalse(locals.getValue("e").isMut)
        assertEquals("test:main.f", (locals.getValue("a").fn as FnSymbol).qualifiedName)
    }

    @Test
    fun fieldsReachedThroughTheImplicitReceiverArePlacesOfThis() {
        val p = snippet(
            """
            pub struct P {
                pub x: Int32 = 0
                pub mut fx bump: () Void {
                    x += 1
                }
            }
            """
        )
        expectNoErrors(p)
        val x = BodyTestSupport.all<Identifier>(p, "x").single { p.model.places[it] != null }
        val place = p.model.places[x] as Place.Field
        assertIs<Place.This>(place.receiver)
        assertEquals("P.x", (p.model.refs[x] as FieldSymbol).qualifiedName)
    }

    @Test
    fun anUpcastIntoAMaybeOfTheBaseIsOneWrap() {
        val p = snippet(
            """
            pub trait B {
                pub fx id: () Str;
            }
            pub class S: B {
                override pub fx id: () Str {
                    return "s"
                }
            }
            pub fx make: () Maybe<B> {
                return S { }
            }
            """
        )
        expectNoErrors(p)
        val made = BodyTestSupport.node<ObjectInitExpr>(p, "S { }")
        assertEquals("S", p.model.types[made]!!.display())
        val c = p.model.coercions[made]
        assertIs<Coercion.WrapSome>(c)
        assertEquals("B", c.inner.display(), "the inner type is the base: the upcast is implied")
    }

    @Test
    fun lenientModeTypesTheSameAndOnlyWarns() {
        val p = snippet("pub fx f: (i: Int32, x: Float32) Float32 {\n    return i + x\n}", mode = TyperMode.LENIENT)
        val d = TyperTestSupport.expectDiagnostic(p, "types.op.mismatch")
        assertEquals(net.exoad.kira.compiler.analysis.types.Severity.WARNING, d.severity)
        assertFalse(p.hasErrors)
        assertNotNull(p.model.types[BodyTestSupport.node<BinaryExpr>(p, "i + x")])
    }
}
