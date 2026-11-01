package net.exoad.kira.suite.codegen

import net.exoad.kira.suite.codegen.CodegenEmit.emit
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/** C backend shape: classes, methods, ARC scope ends and trait dispatch. */
class CodegenClassAndTraitTest {
    @Test
    fun classLowersToStructConstructorAndFreeMethod() {
        val output = emit(
            """
            pub Pet: Any {
                pub name: Str
                pub sound: Str

                pub speak: () Str {
                    sound
                }
            }
            """
        )
        assertTrue(output.contains("typedef struct Pet Pet;"), output)
        assertTrue(output.contains("struct Pet"), output)
        assertTrue(output.contains("Str name;"), output)
        assertTrue(output.contains("Str sound;"), output)
        // constructor
        assertTrue(output.contains("Pet* Pet_new(Str name, Str sound)"), output)
        assertTrue(output.contains("kira_rc_alloc_with(sizeof(Pet), null)"), output)
        // method as free function with receiver
        assertTrue(output.contains("Str Pet_speak(Pet* this)"), output)
        assertTrue(output.contains("return this->sound;"), output)
    }

    @Test
    fun methodCallsLowerToFreeFunctionWithReceiver() {
        val output = emit(
            """
            pub Pet: Any {
                pub name: Str

                pub speak: () Str {
                    name
                }
            }

            main: () Void {
                friend: Pet = Pet { "Mochi" }
                trace(friend.name)
                trace(friend.speak())
            }
            """
        )
        assertTrue(output.contains("Pet* friend = Pet_new(\"Mochi\");"), output)
        assertTrue(output.contains("print(\"%s\\n\", friend->name);"), output)
        assertTrue(output.contains("Pet_speak(friend)"), output)
    }

    @Test
    fun classLocalsAreArcReleasedAtScopeEnd() {
        val output = emit(
            """
            pub Pet: Any {
                pub name: Str
            }

            main: () Void {
                friend: Pet = Pet { "Mochi" }
                trace(friend.name)
            }
            """
        )
        assertTrue(output.contains("kira_rc_release(friend);"), output)
        assertTrue(output.contains("return 0;"), output)
    }

    @Test
    fun traitDispatchEmitsVtablesAndTrampolines() {
        val output = emit(
            """
            pub Speaker: Any {
                pub speak: () Str
            }

            pub Dog: Speaker {
                pub label: Str

                pub speak: () Str {
                    "woof"
                }
            }

            announce: (s: Speaker) Void {
                trace(s.speak())
            }

            main: () Void {
                d: Dog = Dog { "Rex" }
                s: Speaker = d
                announce(s)
            }
            """
        )
        assertTrue(output.contains("struct Speaker"), output)
        assertTrue(output.contains("SpeakerVTable"), output)
        assertTrue(output.contains("Speaker_speak_tramp_Dog"), output)
        assertTrue(output.contains("Speaker_vtable_Dog"), output)
        assertTrue(output.contains(".vtable = &Speaker_vtable_Dog"), output)
        assertTrue(output.contains(".vtable->speak("), output)
    }
}
