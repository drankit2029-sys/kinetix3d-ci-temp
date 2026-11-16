package com.kinetix3d.evaluator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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

    private fun evaluate(source: String): Float = ExpressionBytecodeVm.compile(source).evaluate(StubContext())

    private class StubContext(
        private val variables: Map<String, Float> = emptyMap(),
    ) : ExpressionContext {
        override fun loadVariable(name: String): Float? = variables[name]

        override fun callFunction(
            name: String,
            args: List<ExpressionArg>,
        ): Float? =
            when (name) {
                "wiggle" -> {
                    2f
                }

                "audioBand" -> {
                    0.5f
                }

                "add" -> {
                    args.filterIsInstance<ExpressionArg.Number>().sumOf { it.value.toDouble() }.toFloat()
                }

                "mix" -> {
                    val text = assertIs<ExpressionArg.Text>(args[0])
                    assertEquals("kick", text.value)
                    3f
                }

                else -> {
                    null
                }
            }
    }
}
