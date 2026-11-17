/*
 * Kinetix3D Studio — core-evaluator
 * Recursive-descent expression compiler, compact stack bytecode VM, and the
 * built-in motion-expression standard library.
 *
 * Evaluation is allocation-free after the first call per thread: the stack,
 * numeric argument scratchpad and reusable call view all live in a ThreadLocal.
 */

package com.kinetix3d.evaluator

import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Parse/compile/evaluation failure with an optional source position (0-based). */
class ExpressionException(
    message: String,
    val position: Int = -1,
) : IllegalArgumentException(if (position >= 0) "$message at position $position" else message)

/**
 * Reusable view over one function call's arguments. The VM reuses a single instance
 * per thread, so implementations must read arguments immediately and never retain it.
 */
class ExpressionCall internal constructor(
    private val numeric: FloatArray,
    private val text: Array<String?>,
) {
    var functionName: String = ""
        private set

    var argumentCount: Int = 0
        private set

    private var textMask: Int = 0

    internal fun prepare(
        name: String,
        count: Int,
        mask: Int,
    ): ExpressionCall {
        functionName = name
        argumentCount = count
        textMask = mask
        return this
    }

    fun hasTextArguments(): Boolean = textMask != 0

    fun isText(index: Int): Boolean {
        require(index in 0 until argumentCount) { "Argument index $index out of bounds" }
        return textMask and (1 shl index) != 0
    }

    fun number(index: Int): Float {
        require(index in 0 until argumentCount) { "Argument index $index out of bounds" }
        return numeric[index]
    }

    fun text(index: Int): String? {
        require(index in 0 until argumentCount) { "Argument index $index out of bounds" }
        return if (isText(index)) text[index] else null
    }

    internal fun numericView(): FloatArray = numeric
}

/** Host services used while evaluating a compiled expression. */
interface ExpressionContext {
    fun loadVariable(name: String): Float?

    /** Called for non-intrinsic functions; return `null` if the function is unknown. */
    fun callFunction(call: ExpressionCall): Float?
}

/** Allocation-free scratchpad reused by every evaluation on a thread. */
private class EvaluationScratch {
    val stack = FloatArray(STACK_SIZE)
    val numericArguments = FloatArray(MAX_CALL_ARGUMENTS)
    val textArguments = arrayOfNulls<String>(MAX_CALL_ARGUMENTS)
    val call = ExpressionCall(numericArguments, textArguments)
    var stackPointer = 0
    var inUse = false

    fun push(value: Float) {
        if (stackPointer >= STACK_SIZE) throw ExpressionException("Expression stack overflow (max depth $STACK_SIZE)")
        stack[stackPointer++] = value
    }

    fun pop(): Float {
        if (stackPointer <= 0) throw ExpressionException("Expression stack underflow")
        return stack[--stackPointer]
    }

    fun reset() {
        stackPointer = 0
    }
}

/**
 * Self-contained compiled expression: bytecode plus constant and string pools.
 * Instances are immutable and safe to evaluate concurrently.
 */
class CompiledExpression internal constructor(
    val source: String,
    private val bytecode: ByteArray,
    private val constants: FloatArray,
    private val strings: Array<String>,
) {
    val bytecodeSize: Int get() = bytecode.size

    fun evaluate(context: ExpressionContext): Float {
        val scratch = SCRATCH.get()
        if (scratch.inUse) {
            // Re-entrant evaluation (a host function evaluating another expression)
            // gets a private scratchpad; this is cold and never the render hot path.
            return run(context, EvaluationScratch())
        }
        scratch.inUse = true
        try {
            return run(context, scratch)
        } finally {
            scratch.inUse = false
        }
    }

    private fun run(
        context: ExpressionContext,
        scratch: EvaluationScratch,
    ): Float {
        scratch.reset()
        var instructionPointer = 0
        while (instructionPointer < bytecode.size) {
            when (val opcode = bytecode[instructionPointer++].toInt()) {
                OP_PUSH_CONST -> {
                    val index = readInt(bytecode, instructionPointer)
                    instructionPointer += 4
                    scratch.push(constants.getOrElse(index) { 0f })
                }

                OP_PUSH_STRING -> {
                    val index = readInt(bytecode, instructionPointer)
                    instructionPointer += 4
                    scratch.push(index.toFloat())
                }

                OP_LOAD_VAR -> {
                    val index = readInt(bytecode, instructionPointer)
                    instructionPointer += 4
                    val name = strings.getOrElse(index) { "" }
                    scratch.push(context.loadVariable(name) ?: 0f)
                }

                OP_ADD -> {
                    val right = scratch.pop()
                    scratch.push(scratch.pop() + right)
                }

                OP_SUB -> {
                    val right = scratch.pop()
                    scratch.push(scratch.pop() - right)
                }

                OP_MUL -> {
                    val right = scratch.pop()
                    scratch.push(scratch.pop() * right)
                }

                OP_DIV -> {
                    val right = scratch.pop()
                    scratch.push(scratch.pop() / right)
                }

                OP_MOD -> {
                    val right = scratch.pop()
                    scratch.push(scratch.pop() % right)
                }

                OP_POW -> {
                    val right = scratch.pop()
                    scratch.push(scratch.pop().pow(right))
                }

                OP_NEG -> {
                    scratch.push(-scratch.pop())
                }

                OP_CALL -> {
                    val nameIndex = readInt(bytecode, instructionPointer)
                    instructionPointer += 4
                    val argumentCount = bytecode[instructionPointer++].toInt() and 0xFF
                    val stringMask = readInt(bytecode, instructionPointer)
                    instructionPointer += 4
                    if (argumentCount > scratch.stackPointer) {
                        throw ExpressionException("Malformed bytecode: argument stack underflow")
                    }
                    val name =
                        strings.getOrNull(nameIndex)
                            ?: throw ExpressionException("Malformed expression bytecode: unknown name index")
                    if (argumentCount > MAX_CALL_ARGUMENTS) {
                        throw ExpressionException("Malformed bytecode: too many call arguments")
                    }
                    val numeric = scratch.numericArguments
                    val text = scratch.textArguments
                    for (index in argumentCount - 1 downTo 0) {
                        val raw = scratch.pop()
                        if (stringMask and (1 shl index) != 0) {
                            text[index] =
                                strings.getOrNull(raw.toInt())
                                    ?: throw ExpressionException(
                                        "Malformed expression bytecode: unknown string index",
                                    )
                            numeric[index] = 0f
                        } else {
                            numeric[index] = raw
                            text[index] = null
                        }
                    }

                    val intrinsic =
                        if (stringMask == 0) {
                            ExpressionMath.evaluate(name, numeric, argumentCount)
                        } else {
                            null
                        }
                    val result =
                        intrinsic
                            ?: context.callFunction(scratch.call.prepare(name, argumentCount, stringMask))
                            ?: throw ExpressionException("Unknown function '$name'")
                    scratch.push(result)
                }

                else -> {
                    throw ExpressionException("Malformed expression bytecode: unknown opcode $opcode")
                }
            }
        }
        if (scratch.stackPointer != 1) {
            throw ExpressionException("Malformed expression bytecode: stack size ${scratch.stackPointer}")
        }
        return scratch.stack[0]
    }

    private companion object {
        val SCRATCH: ThreadLocal<EvaluationScratch> = ThreadLocal.withInitial { EvaluationScratch() }
    }
}

/** Compiles expressions such as `wiggle(4, 25) + audioBand("kick") * 50`. */
object ExpressionBytecodeVm {
    fun compile(source: String): CompiledExpression = Parser(source).parse()
}

/**
 * Built-in pure math and interpolation functions compiled into the VM. They never
 * reach the host [ExpressionContext], so they cannot allocate or box arguments.
 */
internal object ExpressionMath {
    fun evaluate(
        name: String,
        args: FloatArray,
        count: Int,
    ): Float? {
        if (count <= 0) return null
        return when (name) {
            "abs" -> abs(args[0])
            "sin" -> sin(args[0])
            "cos" -> cos(args[0])
            "tan" -> kotlin.math.tan(args[0])
            "sqrt" -> sqrt(args[0].coerceAtLeast(0f))
            "floor" -> floor(args[0])
            "ceil" -> ceil(args[0])
            "round" -> args[0].roundToInt().toFloat()
            "min" -> reduce(args, count, true)
            "max" -> reduce(args, count, false)
            "clamp" -> if (count >= 3) args[0].coerceIn(min(args[1], args[2]), max(args[1], args[2])) else null
            "lerp" -> if (count >= 3) args[0] + (args[1] - args[0]) * args[2] else null
            "linear" -> if (count >= 5) ramp(args[0], args[1], args[2], args[3], args[4], smooth = false) else null
            "ease" -> if (count >= 5) ramp(args[0], args[1], args[2], args[3], args[4], smooth = true) else null
            else -> null
        }
    }

    private fun reduce(
        args: FloatArray,
        count: Int,
        selectMin: Boolean,
    ): Float {
        var value = args[0]
        for (index in 1 until count) {
            value = if (selectMin) min(value, args[index]) else max(value, args[index])
        }
        return value
    }

    private fun ramp(
        t: Float,
        inMin: Float,
        inMax: Float,
        outMin: Float,
        outMax: Float,
        smooth: Boolean,
    ): Float {
        val span = inMax - inMin
        if (abs(span) < 1e-12f) return outMin
        var progress = ((t - inMin) / span).coerceIn(0f, 1f)
        if (smooth) progress = progress * progress * (3f - 2f * progress)
        return outMin + (outMax - outMin) * progress
    }
}

/**
 * Ready-to-use motion-expression context: deterministic `pcg3d` value noise for
 * `wiggle`, pure math/interpolation intrinsics, and host hooks for the values that
 * only the timeline/audio engine can know.
 *
 * `valueAtTime`, `loopIn`, `loopOut` and `audioBand` deliberately throw when their
 * hook is absent — faking audio or keyframes would silently corrupt animation.
 */
class StandardExpressionContext(
    private val timeSeconds: Float = 0f,
    private val frameRate: Float = 30f,
    private val valueProvider: ((Float) -> Float)? = null,
    private val audioBandProvider: ((String) -> Float)? = null,
    private val loopProvider: ((direction: String, type: String, keyframes: Int, timeSeconds: Float) -> Float?)? = null,
) : ExpressionContext {
    var posterizedFrameRate: Float = frameRate
        private set

    override fun loadVariable(name: String): Float? =
        when (name) {
            "time" -> timeSeconds
            "frame" -> floor(timeSeconds * posterizedFrameRate)
            "frameRate" -> posterizedFrameRate
            "pi", "PI" -> PI.toFloat()
            "e" -> E.toFloat()
            else -> null
        }

    override fun callFunction(call: ExpressionCall): Float? {
        if (!call.hasTextArguments()) {
            val intrinsic = ExpressionMath.evaluate(call.functionName, call.numericView(), call.argumentCount)
            if (intrinsic != null) return intrinsic
        }
        return when (call.functionName) {
            "wiggle" -> {
                wiggle(call)
            }

            "loopIn" -> {
                loop(call, "in")
            }

            "loopOut" -> {
                loop(call, "out")
            }

            "posterizeTime" -> {
                posterizedFrameRate = call.number(0).coerceAtLeast(1f)
                0f
            }

            "valueAtTime" -> {
                val provider =
                    valueProvider
                        ?: throw ExpressionException("valueAtTime requires a value host provider")
                provider(call.number(0))
            }

            "audioBand" -> {
                val band = call.text(0) ?: ""
                val provider =
                    audioBandProvider
                        ?: throw ExpressionException("audioBand(\"$band\") requires an audio host provider")
                provider(band)
            }

            else -> {
                null
            }
        }
    }

    private fun wiggle(call: ExpressionCall): Float {
        val frequency = call.number(0)
        val amplitude = if (call.argumentCount > 1) call.number(1) else 1f
        val seed = if (call.argumentCount > 2) call.number(2).toInt() else 0
        if (frequency <= 0f || amplitude == 0f) return 0f

        val position = timeSeconds * frequency
        val base = floor(position)
        val fraction = position - base
        val smooth = fraction * fraction * (3f - 2f * fraction)
        val from = valueNoise(base.toInt(), seed)
        val to = valueNoise(base.toInt() + 1, seed)
        return (from + (to - from) * smooth) * amplitude
    }

    private fun loop(
        call: ExpressionCall,
        direction: String,
    ): Float {
        val provider =
            loopProvider
                ?: throw ExpressionException(
                    "loop${direction.replaceFirstChar { it.uppercase() }} requires a keyframe host provider",
                )
        val type = if (call.argumentCount > 0) call.text(0) ?: "cycle" else "cycle"
        val keyframes = if (call.argumentCount > 1) call.number(1).toInt() else 0
        return provider(direction, type, keyframes, timeSeconds)
            ?: throw ExpressionException("loop${direction.replaceFirstChar { it.uppercase() }} host returned no value")
    }

    /** Deterministic 1D value noise in `[-1, 1)`; stateless `pcg3d` hashing. */
    private fun valueNoise(
        index: Int,
        seed: Int,
    ): Float {
        val hash = pcgHash(index * 0x9E3779B9.toInt() + seed)
        return (hash ushr 8) / 8_388_608f - 1f
    }

    private fun pcgHash(input: Int): Int {
        var state = input * 747796405 + 2891336453L.toInt()
        val word = ((state ushr ((state ushr 28) + 4)) xor state) * 277803737
        state = (word ushr 22) xor word
        return state
    }
}

private const val OP_PUSH_CONST = 1
private const val OP_LOAD_VAR = 2
private const val OP_CALL = 3
private const val OP_PUSH_STRING = 4
private const val OP_ADD = 5
private const val OP_SUB = 6
private const val OP_MUL = 7
private const val OP_DIV = 8
private const val OP_MOD = 9
private const val OP_POW = 10
private const val OP_NEG = 11

private const val MAX_CALL_ARGUMENTS = 16
private const val STACK_SIZE = 16

private fun readInt(
    bytes: ByteArray,
    offset: Int,
): Int {
    if (offset + 4 > bytes.size) throw ExpressionException("Malformed expression bytecode: truncated operand")
    return ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)
}

private class BytecodeBuilder {
    private val bytes = ArrayList<Byte>()

    fun emitByte(value: Int) {
        bytes.add(value.toByte())
    }

    fun emitInt(value: Int) {
        bytes.add((value ushr 24).toByte())
        bytes.add((value ushr 16).toByte())
        bytes.add((value ushr 8).toByte())
        bytes.add(value.toByte())
    }

    fun emitOpcode(opcode: Int) = emitByte(opcode)

    fun toByteArray(): ByteArray = ByteArray(bytes.size) { bytes[it] }
}

private class Parser(
    private val source: String,
) {
    private var position = 0
    private val code = BytecodeBuilder()
    private val constants = ArrayList<Float>()
    private val strings = ArrayList<String>()

    fun parse(): CompiledExpression {
        skipWhitespace()
        if (isAtEnd) fail("Expression is empty")
        parseAdditive()
        skipWhitespace()
        if (!isAtEnd) fail("Unexpected token '${peekChar()}'")
        return CompiledExpression(
            source = source,
            bytecode = code.toByteArray(),
            constants = constants.toFloatArray(),
            strings = strings.toTypedArray(),
        )
    }

    private fun parseAdditive() {
        parseMultiplicative()
        while (true) {
            skipWhitespace()
            when (peekChar()) {
                '+' -> {
                    advance()
                    parseMultiplicative()
                    code.emitOpcode(OP_ADD)
                }

                '-' -> {
                    advance()
                    parseMultiplicative()
                    code.emitOpcode(OP_SUB)
                }

                else -> {
                    return
                }
            }
        }
    }

    private fun parseMultiplicative() {
        parseUnary()
        while (true) {
            skipWhitespace()
            val opcode =
                when (peekChar()) {
                    '*' -> OP_MUL
                    '/' -> OP_DIV
                    '%' -> OP_MOD
                    else -> return
                }
            advance()
            parseUnary()
            code.emitOpcode(opcode)
        }
    }

    private fun parseUnary() {
        skipWhitespace()
        when (peekChar()) {
            '-' -> {
                advance()
                parseUnary()
                code.emitOpcode(OP_NEG)
            }

            '+' -> {
                advance()
                parseUnary()
            }

            else -> {
                parsePower()
            }
        }
    }

    private fun parsePower() {
        parsePrimary()
        skipWhitespace()
        if (peekChar() == '^') {
            advance()
            parseUnary()
            code.emitOpcode(OP_POW)
        }
    }

    private fun parsePrimary() {
        skipWhitespace()
        val current = peekChar() ?: fail("Expected expression")
        when {
            current == '(' -> {
                advance()
                parseAdditive()
                skipWhitespace()
                expect(')')
            }

            current == '"' -> {
                fail("String literal is only valid as a function argument")
            }

            current.isDigit() || (current == '.' && peekNextIsDigit()) -> {
                parseNumber()
            }

            isIdentifierStart(current) -> {
                parseIdentifier()
            }

            else -> {
                fail("Unexpected character '$current'")
            }
        }
    }

    private fun parseIdentifier() {
        val name = readIdentifier()
        skipWhitespace()
        if (peekChar() != '(') {
            code.emitOpcode(OP_LOAD_VAR)
            code.emitInt(internString(name))
            return
        }

        advance()
        val nameIndex = internString(name)
        var argumentCount = 0
        var stringMask = 0
        skipWhitespace()
        if (peekChar() == ')') {
            advance()
        } else {
            while (true) {
                if (argumentCount >= MAX_CALL_ARGUMENTS) {
                    fail("Too many function arguments (max $MAX_CALL_ARGUMENTS)")
                }
                skipWhitespace()
                if (peekChar() == '"') {
                    val text = readStringLiteral()
                    code.emitOpcode(OP_PUSH_STRING)
                    code.emitInt(internString(text))
                    stringMask = stringMask or (1 shl argumentCount)
                } else {
                    parseAdditive()
                }
                argumentCount++
                skipWhitespace()
                when (peekChar()) {
                    ',' -> {
                        advance()
                    }

                    ')' -> {
                        advance()
                        break
                    }

                    null -> {
                        fail("Expected ',' or ')'")
                    }

                    else -> {
                        fail("Expected ',' or ')'")
                    }
                }
            }
        }
        code.emitOpcode(OP_CALL)
        code.emitInt(nameIndex)
        code.emitByte(argumentCount)
        code.emitInt(stringMask)
    }

    private fun parseNumber() {
        val start = position
        var hasDigit = false
        while (peekChar()?.isDigit() == true) {
            advance()
            hasDigit = true
        }
        if (peekChar() == '.') {
            advance()
            while (peekChar()?.isDigit() == true) {
                advance()
                hasDigit = true
            }
        }
        if (!hasDigit) fail("Invalid number", start)
        if (peekChar() == 'e' || peekChar() == 'E') {
            advance()
            if (peekChar() == '+' || peekChar() == '-') advance()
            var exponentDigit = false
            while (peekChar()?.isDigit() == true) {
                advance()
                exponentDigit = true
            }
            if (!exponentDigit) fail("Invalid number exponent", start)
        }
        val text = source.substring(start, position)
        val value = text.toFloatOrNull() ?: fail("Invalid number '$text'", start)
        code.emitOpcode(OP_PUSH_CONST)
        code.emitInt(internConstant(value))
    }

    private fun readIdentifier(): String {
        val start = position
        if (isAtEnd || !isIdentifierStart(source[position])) fail("Expected identifier")
        advance()
        while (!isAtEnd && isIdentifierPart(source[position])) advance()
        return source.substring(start, position)
    }

    private fun readStringLiteral(): String {
        val start = position
        advance()
        val builder = StringBuilder()
        while (true) {
            val current = peekChar() ?: fail("Unterminated string literal", start)
            advance()
            when (current) {
                '"' -> {
                    return builder.toString()
                }

                '\\' -> {
                    val escaped = peekChar() ?: fail("Unterminated string literal", start)
                    advance()
                    builder.append(
                        when (escaped) {
                            'n' -> '\n'
                            't' -> '\t'
                            'r' -> '\r'
                            '"' -> '"'
                            '\\' -> '\\'
                            else -> fail("Invalid escape sequence '\\$escaped'")
                        },
                    )
                }

                else -> {
                    builder.append(current)
                }
            }
        }
    }

    private fun internString(value: String): Int {
        val existing = strings.indexOf(value)
        if (existing >= 0) return existing
        strings.add(value)
        return strings.lastIndex
    }

    private fun internConstant(value: Float): Int {
        val existing = constants.indexOf(value)
        if (existing >= 0) return existing
        constants.add(value)
        return constants.lastIndex
    }

    private fun expect(expected: Char) {
        skipWhitespace()
        if (peekChar() != expected) fail("Expected '$expected'")
        advance()
    }

    private fun peekNextIsDigit(): Boolean = position + 1 < source.length && source[position + 1].isDigit()

    private fun peekChar(): Char? = if (position < source.length) source[position] else null

    private fun advance() {
        position++
    }

    private fun skipWhitespace() {
        while (position < source.length && source[position].isWhitespace()) position++
    }

    private val isAtEnd: Boolean get() = position >= source.length

    private fun isIdentifierStart(value: Char): Boolean = value.isLetter() || value == '_'

    private fun isIdentifierPart(value: Char): Boolean = value.isLetterOrDigit() || value == '_'

    private fun fail(
        message: String,
        at: Int = position,
    ): Nothing = throw ExpressionException(message, at)
}
