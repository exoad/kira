package net.exoad.kira.suite.runtime

import net.exoad.kira.suite.runtime.RuntimeRun.assertStdout
import org.junit.jupiter.api.Test

/** End to end on C: classes, generics and trait dispatch. */
class TypeRuntimeTest {
    @Test
    fun classConstructionAndMethods() {
        assertStdout("Mochi\nmeow\n") {
            """
            pub Pet: Any {
                pub name: Str
                pub sound: Str

                pub speak: () Str {
                    sound
                }
            }

            main: () Void {
                friend: Pet = Pet { "Mochi", "meow" }
                trace(friend.name)
                trace(friend.speak())
            }
            """
        }
    }

    @Test
    fun genericIdentityAndBox() {
        assertStdout("7\n") {
            """
            pub Box<T>: Any {
                pub value: T
            }

            id<T>: (value: T) T {
                value
            }

            main: () Void {
                wrapped: Box<Int32> = Box<Int32> { 7 }
                trace(id<Int32>(wrapped.value))
            }
            """
        }
    }

    @Test
    fun traitDispatchThroughVtable() {
        assertStdout("Rex\nwoof\nLuna\nmeow\n8\n") {
            """
            pub Speaker: Any {
                pub speak: () Str
                pub name: () Str
            }

            pub Noisy: Speaker {
                pub loudness: () Int32
            }

            pub Dog: Noisy {
                pub label: Str

                pub speak: () Str {
                    "woof"
                }

                pub name: () Str {
                    label
                }

                pub loudness: () Int32 {
                    8
                }
            }

            pub Cat: Speaker {
                pub label: Str

                pub speak: () Str {
                    "meow"
                }

                pub name: () Str {
                    label
                }
            }

            announce: (s: Speaker) Void {
                trace(s.name())
                trace(s.speak())
            }

            noiseLevel: (s: Noisy) Int32 {
                s.loudness()
            }

            main: () Void {
                dog: Dog = Dog { "Rex" }
                cat: Cat = Cat { "Luna" }
                announce(dog)
                announce(cat)
                trace(noiseLevel(dog))
            }
            """
        }
    }

    @Test
    fun genericIdentityInstantiatesAcrossNumericTypes() {
        // Int8/Int16/Int32 all print as %d, so the happy path works through
        // generic calls. Str/Int64 returns hit the print-format gap pinned in
        // CodegenSuiteTest.genericCallReturnTypeNotThreadedIntoPrintFormat.
        assertStdout("1\n2\n3\n") {
            """
            id<T>: (value: T) T {
                value
            }

            main: () Void {
                trace(id<Int8>(1))
                trace(id<Int16>(2))
                trace(id<Int32>(3))
            }
            """
        }
    }
}
