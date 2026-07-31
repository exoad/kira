package net.exoad.kira

import net.exoad.kira.compiler.frontend.parser.ParserBackend
import kotlin.test.Test
import kotlin.test.assertTrue

class TraitCodegenTest {
    @Test
    fun traitDispatchEmitsVtableStructsAndTrampolines() {
        val source = """
            module "tests:traits"

            trait Speaker {
                pub fx speak(): Str
                pub fx name(): Str
            }

            trait Noisy: Speaker {
                pub fx loudness(): Int32
            }

            class Dog: Noisy {
                require pub label: Str

                pub fx speak(): Str {
                    return "woof"
                }

                pub fx name(): Str {
                    return label
                }

                pub fx loudness(): Int32 {
                    return 8
                }
            }

            class Cat: Speaker {
                require pub label: Str

                pub fx speak(): Str {
                    return "meow"
                }

                pub fx name(): Str {
                    return label
                }
            }

            fx announce(s: Speaker): Void {
                trace(s.name())
                trace(s.speak())
            }

            fx noiseLevel(s: Noisy): Int32 {
                return s.loudness()
            }

            fx main(): Void {
                dog: Dog = Dog { "Rex" }
                cat: Cat = Cat { "Luna" }
                announce(dog)
                announce(cat)
                loud: Int32 = noiseLevel(dog)
                trace(loud)
                s: Speaker = dog
                trace(s.name())
                trace(makeCatSpeaker().name())
            }

            fx makeCatSpeaker(): Speaker {
                c: Cat = Cat { "Mochi" }
                return c
            }
        """.trimIndent()

        val output = TestCompileSupport.transpileSnippetToC(
            source,
            "tests/traits.kira",
            ParserBackend.LEGACY,
            runSemantic = true
        )

        // Trait interface structs (by-value fat pointers).
        assertTrue(output.contains("struct Speaker"), output)
        assertTrue(output.contains("struct SpeakerVTable"), output)
        assertTrue(output.contains("struct Noisy"), output)
        assertTrue(output.contains("struct NoisyVTable"), output)
        // Noisy inherits Speaker methods.
        assertTrue(output.contains("Noisy_loudness_tramp_Dog"), output)
        assertTrue(output.contains("Noisy_speak_tramp_Dog"), output)
        // Per-class vtables.
        assertTrue(output.contains("Speaker_vtable_Dog"), output)
        assertTrue(output.contains("Speaker_vtable_Cat"), output)
        assertTrue(output.contains("Noisy_vtable_Dog"), output)
        // Fat-pointer coercion at call sites.
        assertTrue(output.contains(".vtable = &Speaker_vtable_Dog"), output)
        assertTrue(output.contains(".vtable = &Noisy_vtable_Dog"), output)
        // Dispatch through the vtable.
        assertTrue(output.contains(".vtable->speak("), output)
        assertTrue(output.contains(".vtable->loudness("), output)
        // Trait-typed local with a class initializer coerces.
        assertTrue(output.contains("Speaker s = "), output)
    }
}
