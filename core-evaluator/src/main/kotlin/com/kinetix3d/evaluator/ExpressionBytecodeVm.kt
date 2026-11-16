/*
 * Kinetix3D Studio — core-evaluator
 * Recursive-descent expression compiler and compact stack bytecode VM.
 */

package com.kinetix3d.evaluator

import kotlin.math.pow

/** Parse/compile/evaluation failure with an optional source position (0-based). */
class ExpressionException(
    message: String,
    val position: Int = -1,
) : IllegalArgumentException(if (position >= 0) "$message at position $position" else message)

/** A function argument: a runtime numeric value or a string literal. */
sealed interface ExpressionArg {
    data class Number(
        val value: Float,
    ) : ExpressionArg

    data class Text(
        val value: String,
    ) : ExpressionArg
}

/** Host services used while evaluating a compiled expression. */
interface ExpressionContext {
    fun loadVariable(name: String): Float?

    fun callFunction(
        name: String,
        args: List<ExpressionArg>,
    ): Float?
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
        val stack = FloatArray(STACK_SIZE)
        var stackPointer = 0
        var instructionPointer = 0

        fun push(value: Float) {
            if (stackPointer >= STACK_SIZE) {
                throw ExpressionException("Expression stack overflow (max depth $STACK_SIZE)")
            }
            stack[stackPointer++] = value
        }

        fun pop(): Float {
            if (stackPointer <= 0) throw ExpressionException("Expression stack underflow")
            return stack[--stackPointer]
        }

        while (instructionPointer < bytecode.size) {
            when (val opcode = bytecode[instructionPointer++].toInt()) {
                OP_PUSH_CONST -> {
                    val index = readInt(bytecode, instructionPointer)
                    instructionPointer += 4
                    push(constants.getOrElse(index) { 0f })
                }

                OP_PUSH_STRING -> {
                    val index = readInt(bytecode, instructionPointer)
                    instructionPointer += 4
                    push(index.toFloat())
                }

                OP_LOAD_VAR -> {
                    val index = readInt(bytecode, instructionPointer)
                    instructionPointer += 4
                    val name = strings.getOrElse(index) { "" }
                    push(context.loadVariable(name) ?: 0f)
                }

                OP_ADD -> {
                    val right = pop()
                    push(pop() + right)
                }

                OP_SUB -> {
                    val right = pop()
                    push(pop() - right)
                }

                OP_MUL -> {
                    val right = pop()
                    push(pop() * right)
                }

                OP_DIV -> {
                    val right = pop()
                    push(pop() / right)
                }

                OP_MOD -> {
                    val right = pop()
                    push(pop() % right)
                }

                OP_POW -> {
                    val right = pop()
                    push(pop().pow(right))
                }

                OP_NEG -> {
                    push(-pop())
                }

                OP_CALL -> {
                    val nameIndex = readInt(bytecode, instructionPointer)
                    instructionPointer += 4
                    val argumentCount = bytecode[instructionPointer++].toInt() and 0xFF
                    val stringMask = readInt(bytecode, instructionPointer)
                    instructionPointer += 4
                    if (argumentCount > stackPointer) {
                        throw ExpressionException("Malformed bytecode: argument stack underflow")
                    }
                    val name =
                        strings.getOrNull(nameIndex)
                            ?: throw ExpressionException("Malformed expression bytecode: unknown name index")
                    val arguments = ArrayList<ExpressionArg>(argumentCount)
                    repeat(argumentCount) { arguments.add(ExpressionArg.Number(0f)) }
                    for (index in argumentCount - 1 downTo 0) {
                        val raw = pop()
                        arguments[index] =
                            if (stringMask and (1 shl index) != 0) {
                                val textIndex = raw.toInt()
                                ExpressionArg.Text(
                                    strings.getOrNull(textIndex)
                                        ?: throw ExpressionException(
                                            "Malformed expression bytecode: unknown string index",
                                        ),
                                )
                            } else {
                                ExpressionArg.Number(raw)
                            }
                    }
                    val result =
                        context.callFunction(name, arguments)
                            ?: throw ExpressionException("Unknown function '$name'")
                    push(result)
                }

                else -> {
                    throw ExpressionException("Malformed expression bytecode: unknown opcode $opcode")
                }
            }
        }
        if (stackPointer != 1) {
            throw ExpressionException("Malformed expression bytecode: stack size $stackPointer")
        }
        return stack[0]
    }

    private companion object {
        const val STACK_SIZE = 16
    }
}

/** Compiles expressions such as `wiggle(4, 25) + audioBand("kick") * 50`. */
object ExpressionBytecodeVm {
    fun compile(source: String): CompiledExpression = Parser(source).parse()
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
