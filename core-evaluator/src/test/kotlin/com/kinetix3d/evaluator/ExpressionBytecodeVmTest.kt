package com.kinetix3d.evaluator

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExpressionBytecodeVmTest {
    @Test
    fun `compiles and evaluates the wiggle and audioBand expression`() {
        val expression = ExpressionBytecodeVm.compile("wiggle(4, 25) + audioBand(\"kick\") * 50")
        val result = expression.evaluate(StubContext())
        assertEquals(27f, result, 1e-6f)
        assertTrue(expression.bytecodeSize > 0)
    }

    @Test
    fun `operator precedence is correct`() {
        assertEquals(7f, evaluate("1 + 2 * 3"))
        assertEquals(9f, evaluate("(1 + 2) * 3"))
        assertEquals(1f, evaluate("7 % 3"))
        assertEquals(2f, evaluate("8 / 2 / 2"))
    }

    @Test
    fun `power is right associative`() {
        assertEquals(512f, evaluate("2 ^ 3 ^ 2"))
        assertEquals(65_536f, evaluate("2 ^ 2 ^ 2 ^ 2"))
    }

    @Test
    fun `unary operators bind correctly`() {
        assertEquals(-4f, evaluate("-2 ^ 2"))
        assertEquals(0.25f, evaluate("2 ^ -2"))
        assertEquals(3f, evaluate("--3"))
        assertEquals(5f, evaluate("+2 + 3"))
    }

    @Test
    fun `numeric literal forms are supported`() {
        assertEquals(0.5f, evaluate(".5"))
        assertEquals(10_000.5f, evaluate("1e4 + .5"))
        assertEquals(0.125f, evaluate("1.25e-1"))
    }

    @Test
    fun `variables resolve through the context and missing ones are zero`() {
        val context = StubContext(mapOf("time" to 2f, "value" to 1f))
        assertEquals(5f, ExpressionBytecodeVm.compile("time * 2 + value").evaluate(context))
        assertEquals(0f, ExpressionBytecodeVm.compile("missing").evaluate(context))
        assertEquals(4f, ExpressionBytecodeVm.compile("time * missing + time ^ 2").evaluate(context))
    }

    @Test
    fun `numeric arguments are evaluated at runtime`() {
        val context = StubContext(mapOf("time" to 4f))
        assertEquals(10f, ExpressionBytecodeVm.compile("add(time, 2 * 3)").evaluate(context))
    }

    @Test
    fun `string arguments are passed as text`() {
        val context = StubContext()
        assertEquals(0.5f, ExpressionBytecodeVm.compile("audioBand(\"kick\")").evaluate(context))
        assertEquals(3f, ExpressionBytecodeVm.compile("mix(\"kick\", 2 + 1)").evaluate(context))
    }

    @Test
    fun `intrinsic math functions never reach the host context`() {
        val context = StubContext()
        assertEquals(3f, ExpressionBytecodeVm.compile("abs(-3)").evaluate(context))
        assertEquals(4f, ExpressionBytecodeVm.compile("sqrt(16)").evaluate(context))
        assertEquals(2f, ExpressionBytecodeVm.compile("min(5, 2, 9)").evaluate(context))
        assertEquals(9f, ExpressionBytecodeVm.compile("max(5, 2, 9)").evaluate(context))
        assertEquals(0.5f, ExpressionBytecodeVm.compile("clamp(1.5, 0, 0.5)").evaluate(context))
        assertEquals(5f, ExpressionBytecodeVm.compile("lerp(0, 10, 0.5)").evaluate(context))
        assertEquals(5f, ExpressionBytecodeVm.compile("linear(0.5, 0, 1, 0, 10)").evaluate(context))
        assertEquals(5f, ExpressionBytecodeVm.compile("ease(0.5, 0, 1, 0, 10)").evaluate(context))
        assertEquals(10f, ExpressionBytecodeVm.compile("linear(2, 0, 1, 0, 10)").evaluate(context))
        assertEquals(0f, ExpressionBytecodeVm.compile("sin(0)").evaluate(context))
        assertEquals(1f, ExpressionBytecodeVm.compile("cos(0)").evaluate(context))
        assertEquals(2f, ExpressionBytecodeVm.compile("round(1.6)").evaluate(context))
        assertEquals(1f, ExpressionBytecodeVm.compile("floor(1.9)").evaluate(context))
        assertEquals(2f, ExpressionBytecodeVm.compile("ceil(1.1)").evaluate(context))
        assertEquals(0, context.calls)
    }

    @Test
    fun `division by zero yields IEEE infinity without crashing`() {
        val positive = ExpressionBytecodeVm.compile("1 / 0").evaluate(StubContext())
        val negative = ExpressionBytecodeVm.compile("-1 / 0").evaluate(StubContext())
        assertEquals(Float.POSITIVE_INFINITY, positive)
        assertEquals(Float.NEGATIVE_INFINITY, negative)
        assertTrue(ExpressionBytecodeVm.compile("0 / 0").evaluate(StubContext()).isNaN())
    }

    @Test
    fun `unknown functions throw with the function name`() {
        val expression = ExpressionBytecodeVm.compile("doesNotExist(1)")
        val error = assertFailsWith<ExpressionException> { expression.evaluate(StubContext()) }
        assertTrue(error.message.orEmpty().contains("doesNotExist"))
    }

    @Test
    fun `parse errors report positions`() {
        val errors =
            listOf(
                "(1 + 2",
                "1 +",
                "1 @ 2",
                "",
                "audioBand(\"kick\"",
                "2 ^",
                "1 + (2 * 3",
            )
        for (source in errors) {
            val error =
                assertFailsWith<ExpressionException>("source should fail: $source") {
                    ExpressionBytecodeVm.compile(source)
                }
            assertTrue(error.message.orEmpty().contains("position"), "missing position for: $source")
        }
    }

    @Test
    fun `compiled expressions are reusable and immutable`() {
        val expression = ExpressionBytecodeVm.compile("2 * 21")
        assertEquals(42f, expression.evaluate(StubContext()))
        assertEquals(42f, expression.evaluate(StubContext()))
    }

    @Test
    fun `too many call arguments is rejected`() {
        val source = "add(" + List(17) { "1" }.joinToString(", ") + ")"
        val error = assertFailsWith<ExpressionException> { ExpressionBytecodeVm.compile(source) }
        assertTrue(error.message.orEmpty().contains("Too many function arguments"))
    }

    @Test
    fun `re-entrant evaluation from a host function does not corrupt the stack`() {
        val outer = ExpressionBytecodeVm.compile("add(1, 2)")
        val inner = ExpressionBytecodeVm.compile("add(10, 20)")
        var nested = false
        val context =
            object : ExpressionContext {
                override fun loadVariable(name: String): Float? = null

                override fun callFunction(call: ExpressionCall): Float? =
                    when (call.functionName) {
                        "add" -> {
                            if (!nested) {
                                nested = true
                                assertEquals(30f, inner.evaluate(this))
                            }
                            var sum = 0f
                            for (index in 0 until call.argumentCount) sum += call.number(index)
                            sum
                        }

                        else -> {
                            null
                        }
                    }
            }
        assertEquals(3f, outer.evaluate(context))
        assertEquals(3f, outer.evaluate(context))
    }

    private fun evaluate(source: String): Float = ExpressionBytecodeVm.compile(source).evaluate(StubContext())

    private class StubContext(
        private val variables: Map<String, Float> = emptyMap(),
    ) : ExpressionContext {
        var calls: Int = 0
            private set

        override fun loadVariable(name: String): Float? = variables[name]

        override fun callFunction(call: ExpressionCall): Float? {
            calls++
            return when (call.functionName) {
                "wiggle" -> {
                    2f
                }

                "audioBand" -> {
                    0.5f
                }

                "add" -> {
                    var sum = 0f
                    for (index in 0 until call.argumentCount) sum += call.number(index)
                    sum
                }

                "mix" -> {
                    assertTrue(call.isText(0))
                    assertEquals("kick", call.text(0))
                    3f
                }

                else -> {
                    null
                }
            }
        }
    }
}

class StandardExpressionContextTest {
    private fun evaluate(
        source: String,
        context: ExpressionContext,
    ): Float = ExpressionBytecodeVm.compile(source).evaluate(context)

    @Test
    fun `time frame and frame rate variables resolve`() {
        val context = StandardExpressionContext(timeSeconds = 1.5f, frameRate = 30f)
        assertEquals(1.5f, evaluate("time", context), 1e-6f)
        assertEquals(45f, evaluate("frame", context), 1e-6f)
        assertEquals(30f, evaluate("frameRate", context), 1e-6f)
        assertEquals(3.1415927f, evaluate("PI", context), 1e-5f)
        assertEquals(2.7182817f, evaluate("e", context), 1e-5f)
    }

    @Test
    fun `wiggle is deterministic and respects its amplitude`() {
        val first = StandardExpressionContext(timeSeconds = 2.25f, frameRate = 30f)
        val second = StandardExpressionContext(timeSeconds = 2.25f, frameRate = 30f)
        val a = evaluate("wiggle(3, 10)", first)
        val b = evaluate("wiggle(3, 10)", second)
        assertEquals(a, b)
        assertTrue(abs(a) <= 10f, "wiggle exceeded its amplitude: $a")

        // Different seeds break the correlation without changing the bound.
        val seeded = evaluate("wiggle(3, 10, 7)", first)
        assertTrue(abs(seeded) <= 10f)
        assertTrue(seeded != a)

        // Degenerate parameters collapse to zero rather than drifting the value.
        assertEquals(0f, evaluate("wiggle(0, 10)", first), 0f)
        assertEquals(0f, evaluate("wiggle(3, 0)", first), 0f)
    }

    @Test
    fun `wiggle is smooth between noise control points`() {
        val samples =
            (0..40).map { step ->
                val context = StandardExpressionContext(timeSeconds = step * 0.01f, frameRate = 30f)
                evaluate("wiggle(4, 1, 42)", context)
            }
        for (index in 1 until samples.size) {
            assertTrue(abs(samples[index] - samples[index - 1]) < 0.2f, "wiggle jumped at sample $index")
        }
    }

    @Test
    fun `posterizeTime updates the frame variable`() {
        val context = StandardExpressionContext(timeSeconds = 0.5f, frameRate = 60f)
        assertEquals(30f, evaluate("frame", context), 1e-6f)
        assertEquals(0f, evaluate("posterizeTime(10)", context), 0f)
        assertEquals(5f, evaluate("frame", context), 1e-6f)
    }

    @Test
    fun `valueAtTime uses the host provider and fails loudly without one`() {
        val context = StandardExpressionContext(valueProvider = { time -> time * 2f })
        assertEquals(6f, evaluate("valueAtTime(3)", context), 1e-6f)

        val error =
            assertFailsWith<ExpressionException> {
                evaluate("valueAtTime(3)", StandardExpressionContext())
            }
        assertTrue(error.message.orEmpty().contains("valueAtTime"))
    }

    @Test
    fun `audioBand uses the host provider and never fakes audio`() {
        val context =
            StandardExpressionContext(
                audioBandProvider = { band -> if (band == "kick") 0.75f else 0f },
            )
        assertEquals(0.75f, evaluate("audioBand(\"kick\")", context), 1e-6f)

        val error =
            assertFailsWith<ExpressionException> {
                evaluate("audioBand(\"kick\")", StandardExpressionContext())
            }
        assertTrue(error.message.orEmpty().contains("audioBand"))
    }

    @Test
    fun `loopIn and loopOut forward direction type and keyframes`() {
        val context =
            StandardExpressionContext(
                timeSeconds = 1f,
                loopProvider = { direction, type, keyframes, time ->
                    if (direction == "out") 7f else 3f
                },
            )
        assertEquals(7f, evaluate("loopOut(\"cycle\")", context), 0f)
        assertEquals(3f, evaluate("loopIn(\"pingpong\", 4)", context), 0f)

        val error =
            assertFailsWith<ExpressionException> {
                evaluate("loopOut()", StandardExpressionContext())
            }
        assertTrue(error.message.orEmpty().contains("loopOut"))
    }
}
