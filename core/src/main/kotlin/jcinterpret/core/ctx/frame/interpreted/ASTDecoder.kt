package jcinterpret.core.ctx.frame.interpreted

import jcinterpret.core.control.UnsupportedLanguageFeature
import jcinterpret.core.descriptors.qualifiedSignature
import jcinterpret.core.descriptors.signature
import jcinterpret.core.memory.stack.StackBoolean
import jcinterpret.core.memory.stack.StackInt
import jcinterpret.signature.ClassTypeSignature
import jcinterpret.signature.PrimitiveTypeSignature
import org.eclipse.jdt.core.dom.*
import kotlin.math.exp

class ASTDecoder(val frame: InterpretedExecutionFrame): ASTVisitor() {

    //
    //  Helpers
    //

    fun add(stmt: Statement) = frame.instructions.push(decode_stmt(stmt))

    fun add(expr: Expression, store: Boolean = true) {
        if (!store) frame.instructions.push(pop)
        frame.instructions.push(decode_expr(expr))
    }

    fun push(instr: InterpretedInstruction) = frame.instructions.push(instr)

    //
    //  Statements
    //

    //  Blocks

    override fun visit(node: Block): Boolean {
        push(block_pop)
        node.statements()
            .reversed()
            .forEach { add(it as Statement) }
        push(block_push)

        return false
    }

    override fun visit(node: SynchronizedStatement): Boolean {
        TODO()
    }

    //  Variable

    override fun visit(node: VariableDeclarationStatement): Boolean {
        node.fragments().reversed().forEach { (it as ASTNode).accept(this) }
        return false
    }

    override fun visit(node: VariableDeclarationExpression): Boolean {
        node.fragments().reversed().forEach { (it as ASTNode).accept(this) }
        return false
    }

    override fun visit(node: SingleVariableDeclaration): Boolean {
        val type = node.resolveBinding().type.signature()

        if (node.initializer != null) {
            push(store(node.name.identifier, type))
            add(node.initializer)
        }

        push(allocate(node.name.identifier, type))

        return false
    }

    override fun visit(node: VariableDeclarationFragment): Boolean {
        val type = node.resolveBinding().type.signature()

        if (node.initializer != null) {
            push(store(node.name.identifier, type))
            add(node.initializer)
        }

        push(allocate(node.name.identifier, type))

        return false
    }

    //  Loops

    override fun visit(node: DoStatement): Boolean {

        val instr = while_loop(node.expression, node.body)
        val instructionSize = frame.instructions.size
        var operandsSize = frame.operands.size
        val localDepth = frame.locals.scopes.size

        push(block_pop)
        push(break_pop)
        push(continue_pop)
        push(instr)
        add(node.expression)
        add(node.body)
        push(continue_push(null, instr, StackBoolean(true), 3))
        push(break_push(null, instructionSize, operandsSize, localDepth))
        push(block_push)

        return false
    }

    override fun visit(node: WhileStatement): Boolean {

        val instr = while_loop(node.expression, node.body)
        val instructionSize = frame.instructions.size
        val operandsSize = frame.operands.size
        val localDepth = frame.locals.scopes.size

        push(block_pop)
        push(break_pop)
        push(continue_pop)
        push(instr)
        add(node.expression)
        push(continue_push(null, instr, StackBoolean(true), 2))
        push(break_push(null, instructionSize, operandsSize, localDepth))
        push(block_push)

        return false
    }

    // TODO There is a problem when using a continue statement in a for loop
    // The scope declaring the initialiser variables is destroyed
    override fun visit(node: ForStatement): Boolean {

        val expr = node.expression ?: node.ast.newBooleanLiteral(true)
        val instr = for_loop(expr, node.body, node.updaters() as MutableList<Expression>)

        val instructionSize = frame.instructions.size
        val operandsSize = frame.operands.size
        val localDepth = frame.locals.scopes.size

        push(block_pop)
        push(break_pop)
        push(continue_pop)
        push(instr)
        add(expr)
        push(continue_push(null, instr, StackBoolean(true), 2))
        node.initializers().reversed().forEach { (it as ASTNode).accept(this) }
        push(break_push(null, instructionSize, operandsSize, localDepth))
        push(block_push)

        return false
    }

    override fun visit(node: EnhancedForStatement): Boolean {

        val instr = foreach_loop(node.parameter.name.identifier, node.parameter.resolveBinding().type.signature(), node.body)
        val instructionSize = frame.instructions.size
        val operandsSize = frame.operands.size
        val localDepth = frame.locals.scopes.size

        push(block_pop)
        push(break_pop)
        push(continue_pop)
        push(instr)
        add(node.expression)
        push(continue_push(null, instr, null, 2))
        push(break_push(null, instructionSize, operandsSize, localDepth))
        push(block_push)

        return false
    }

    //  Conditional

    override fun visit(node: IfStatement): Boolean {
        push(conditional_if(node.thenStatement, node.elseStatement))
        add(node.expression)

        return false
    }

    override fun visit(node: SwitchStatement): Boolean {

        val instructionSize = frame.instructions.size
        val operandsSize = frame.operands.size
        val localDepth = frame.locals.scopes.size

        push(block_pop)
        push(break_pop)
        push(conditional_switch(node.statements() as List<Statement>))
        add(node.expression)
        push(break_push(null, instructionSize, operandsSize, localDepth))
        push(block_push)

        return false
    }

    override fun visit(node: SwitchCase): Boolean {
        return false
    }

    //  Try

    override fun visit(node: TryStatement): Boolean {

        node.finally?.accept(this)

        val instructionSize = frame.instructions.size
        val operandsSize = frame.operands.size
        val localDepth = frame.locals.scopes.size

        push(block_pop)
        push(excp_pop)

        add(node.body)

        node.resources().reversed().forEach { (it as ASTNode).accept(this) }

        val handles = node.catchClauses().map {
            (it as CatchClause)
            return@map ExceptionHandle (
                it.exception.name.identifier,
                it.exception.type.resolveBinding().signature() as ClassTypeSignature,
                it.body
            )
        }

        push(excp_push(handles, instructionSize, operandsSize, localDepth))
        push(block_push)

        return false
    }

    //  Control

    override fun visit(node: LabeledStatement): Boolean {
        val instructionSize = frame.instructions.size
        val operandsSize = frame.operands.size
        val localDepth = frame.locals.scopes.size

        push(break_pop)
//        push(continue_pop)
        add(node.body)
//        push(continue_push(node.label.identifier, null, null, 0))
        push(break_push(node.label.identifier, instructionSize, operandsSize, localDepth))

        return false
    }

    override fun visit(node: ContinueStatement): Boolean {
        push(continue_statement(node.label?.identifier))
        return false
    }

    override fun visit(node: BreakStatement): Boolean {
        push(break_statement(node.label?.identifier))
        return false
    }

    //  Linkage

    override fun visit(node: ReturnStatement): Boolean {
        if (node.expression != null) {
            push(return_value)
            add(node.expression)
        } else {
            push(return_void)
        }

        return false
    }

    override fun visit(node: ThrowStatement): Boolean {
        push(throw_exception)
        add(node.expression)

        return false
    }

    //  Expression

    override fun visit(node: ExpressionStatement): Boolean {
        try {
            var storeResult = node.parent is Expression

            if (node.expression is MethodInvocation)
                if ((node.expression as MethodInvocation).resolveMethodBinding().returnType.qualifiedName == "void")
                    storeResult = true // i.e. don't pop a value off -> void doesn't return a value

            add(node.expression, storeResult)
            return false
        } catch (e: Exception) {

            throw e
        }
    }

    //
    //  Expressions
    //

    override fun visit(node: ParenthesizedExpression): Boolean {
        add(node.expression)
        return false
    }

    //  Invocation

    override fun visit(node: ClassInstanceCreation): Boolean {
        val type = node.resolveTypeBinding()
        val binding = node.resolveConstructorBinding()


        val dclass = type.declaringClass
        val static = Modifier.isStatic(type.modifiers)

        if (dclass != null && !static)
            throw UnsupportedLanguageFeature("Non-static inner class ${type.signature()} cannot be interpreted (inner classes not implemented)")

        if (node.anonymousClassDeclaration != null)
            throw UnsupportedLanguageFeature("Anonymous classes are not implemented")

        if (node.expression != null)
            throw UnsupportedLanguageFeature("Scoped constructor calls not implemented")

        push(invoke_special(node.resolveConstructorBinding().qualifiedSignature()))
        if (binding.isVarargs) {
            val regSize = binding.parameterTypes.size - 1

            for (i in node.arguments().size-1 downTo regSize) {
                push(arr_store)
                add(node.arguments()[i] as Expression)
                push(push(StackInt(i)))
                push(dup)
            }

            push(arr_allocate(binding.parameterTypes.last().componentType.signature()))

            node.arguments()
                .take(regSize)
                .reversed()
                .forEach { add(it as Expression) }

        } else {
            node.arguments()
                .reversed()
                .forEach { add(it as Expression) }
        }

        push(dup)
        push(obj_allocate(node.resolveTypeBinding().signature() as ClassTypeSignature))

        return false
    }

    override fun visit(node: MethodInvocation): Boolean {
        val binding = node.resolveMethodBinding()

        if (binding.typeArguments.isNotEmpty() || binding.typeParameters.isNotEmpty())
            Unit

        if (binding.declaringClass.qualifiedName.contains("Dequeue"))
            Unit

        val qsig = binding.qualifiedSignature()

        val isStatic = Modifier.isStatic(binding.modifiers)

        if (binding != binding.methodDeclaration) {
            if (binding.returnType.signature() !is PrimitiveTypeSignature) {
                val rsig = binding.returnType.signature()
                push(cast(rsig))
            }
        }

        if (isStatic) {
            push(invoke_static(qsig))
        } else {
            push(invoke_virtual(qsig))
        }

        if (binding.isVarargs) {
            val regSize = binding.parameterTypes.size - 1

            for (i in node.arguments().size-1 downTo regSize) {
                push(arr_store)
                add(node.arguments()[i] as Expression)
                push(push(StackInt(i)))
                push(dup)
            }

            push(arr_allocate(binding.parameterTypes.last().componentType.signature()))

            node.arguments()
                .take(regSize)
                .reversed()
                .forEach { add(it as Expression) }

        } else {
            node.arguments()
                .reversed()
                .forEach { add(it as Expression) }
        }

        if (!isStatic) {
            if (node.expression != null) {
                add(node.expression)
            } else {
                push(load("this"))
            }
        }

        return false
    }

    override fun visit(node: SuperMethodInvocation): Boolean {
        val binding = node.resolveMethodBinding()
        val qsig = binding.qualifiedSignature()

        if (binding != binding.methodDeclaration) {
            if (binding.returnType.signature() !is PrimitiveTypeSignature) {
                val rsig = binding.returnType.signature()
                push(cast(rsig))
            }
        }

        push(invoke_virtual_super(qsig))

        if (binding.isVarargs) {
            val regSize = binding.parameterTypes.size - 1

            for (i in node.arguments().size-1 downTo regSize) {
                push(arr_store)
                add(node.arguments()[i] as Expression)
                push(push(StackInt(i)))
                push(dup)
            }

            push(arr_allocate(binding.parameterTypes.last().componentType.signature()))

            node.arguments()
                .take(regSize)
                .reversed()
                .forEach { add(it as Expression) }

        } else {
            node.arguments()
                .reversed()
                .forEach { add(it as Expression) }
        }

        push(load("this"))

        return false
    }

    override fun visit(node: ConstructorInvocation): Boolean {
        val binding = node.resolveConstructorBinding()

        push(invoke_special(node.resolveConstructorBinding().qualifiedSignature()))
        if (binding.isVarargs) {
            val regSize = binding.parameterTypes.size - 1

            for (i in node.arguments().size-1 downTo regSize) {
                push(arr_store)
                add(node.arguments()[i] as Expression)
                push(push(StackInt(i)))
                push(dup)
            }

            push(arr_allocate(binding.parameterTypes.last().componentType.signature()))

            node.arguments()
                .take(regSize)
                .reversed()
                .forEach { add(it as Expression) }

        } else {
            node.arguments()
                .reversed()
                .forEach { add(it as Expression) }
        }
        push(load("this"))

        return false
    }

    override fun visit(node: SuperConstructorInvocation): Boolean {
        val binding = node.resolveConstructorBinding()

        push(invoke_special(node.resolveConstructorBinding().qualifiedSignature()))
        if (binding.isVarargs) {
            val regSize = binding.parameterTypes.size - 1

            for (i in node.arguments().size-1 downTo regSize) {
                push(arr_store)
                add(node.arguments()[i] as Expression)
                push(push(StackInt(i)))
                push(dup)
            }

            push(arr_allocate(binding.parameterTypes.last().componentType.signature()))

            node.arguments()
                .take(regSize)
                .reversed()
                .forEach { add(it as Expression) }

        } else {
            node.arguments()
                .reversed()
                .forEach { add(it as Expression) }
        }
        push(load("this"))

        return false
    }

    override fun visit(node: ArrayCreation): Boolean {

        if (node.initializer != null) {
            for (i in node.initializer.expressions().size-1 downTo 0) {
                val arg = node.initializer.expressions()[i] as Expression

                push(arr_store)
                push(decode_expr(arg))
                push(push(StackInt(i)))
                push(dup)
            }
        }

        push(arr_allocate(node.resolveTypeBinding().componentType.signature()))
        return false
    }

    override fun visit(node: ArrayInitializer): Boolean {

        for (i in node.expressions().size-1 downTo 0) {
            val arg = node.expressions()[i] as Expression

            push(arr_store)
            push(decode_expr(arg))
            push(push(StackInt(i)))
            push(dup)
        }

        push(arr_allocate(node.resolveTypeBinding().componentType.signature()))
        return false
    }

    //  Conditional

    override fun visit(node: ConditionalExpression): Boolean {
        push(conditional_ternary(node.thenExpression, node.elseExpression))
        add(node.expression)

        return false
    }

    //  Assignment & Operators

    var isAssignmentTarget = false
    fun assigning(handle: () -> Unit) {
        isAssignmentTarget = true
        handle()
        isAssignmentTarget = false
    }

    override fun visit(node: Assignment): Boolean {

        // Assignment are expressions - returns the lhs
        add(node.leftHandSide)

        // Put the store/put instruction in
        assigning {
            node.leftHandSide.accept(this)
        }

        when (node.operator) {
            Assignment.Operator.ASSIGN -> {
                add(node.rightHandSide)
            }

            Assignment.Operator.PLUS_ASSIGN -> {
                push(add)
                add(node.rightHandSide)
                add(node.leftHandSide)
            }

            Assignment.Operator.MINUS_ASSIGN -> {
                push(sub)
                add(node.rightHandSide)
                add(node.leftHandSide)
            }

            Assignment.Operator.TIMES_ASSIGN -> {
                push(mul)
                add(node.rightHandSide)
                add(node.leftHandSide)
            }

            Assignment.Operator.DIVIDE_ASSIGN -> {
                push(div)
                add(node.rightHandSide)
                add(node.leftHandSide)
            }

            Assignment.Operator.REMAINDER_ASSIGN -> {
                push(mod)
                add(node.rightHandSide)
                add(node.leftHandSide)
            }

            Assignment.Operator.BIT_AND_ASSIGN -> {
                push(and)
                add(node.rightHandSide)
                add(node.leftHandSide)
            }

            else -> TODO()
        }

        return false
    }

    override fun visit(node: PrefixExpression): Boolean {
        when (node.operator) {
            PrefixExpression.Operator.INCREMENT -> {
                add(node.operand)
                assigning { node.operand.accept(this) }
                push(add)
                push(push(StackInt(1)))
                add(node.operand)
            }

            PrefixExpression.Operator.DECREMENT -> {
                add(node.operand)
                assigning { node.operand.accept(this) }
                push(sub)
                push(push(StackInt(1)))
                add(node.operand)
            }

            PrefixExpression.Operator.PLUS -> {
                TODO()  // Converts to int
            }

            PrefixExpression.Operator.MINUS -> {
                push(mul)
                push(push(StackInt(-1)))
                add(node.operand, true)
            }

            PrefixExpression.Operator.COMPLEMENT -> {
                TODO() // Bitwise complement
            }

            PrefixExpression.Operator.NOT -> {
                push(not)
                add(node.operand)
            }

            else -> throw IllegalArgumentException("Unknown prefix operator ${node.operator}")
        }

        return false
    }

    override fun visit(node: PostfixExpression): Boolean {
        assigning {
            node.operand.accept(this)
        }

        when (node.operator) {
            PostfixExpression.Operator.INCREMENT -> {
                push(add)
                push(push(StackInt(1)))
                add(node.operand)
                add(node.operand)
            }

            PostfixExpression.Operator.DECREMENT -> {
                push(sub)
                push(push(StackInt(1)))
                add(node.operand)
                add(node.operand)
            }

            else -> throw IllegalArgumentException("Unknown PostfixExpression.Operator ${node.operator}")
        }

        return false
    }

    override fun visit(node: InfixExpression): Boolean {
        val operator = when (node.operator) {
            InfixExpression.Operator.TIMES -> mul
            InfixExpression.Operator.DIVIDE -> div
            InfixExpression.Operator.REMAINDER -> mod
            InfixExpression.Operator.PLUS -> add
            InfixExpression.Operator.MINUS -> sub
            InfixExpression.Operator.LEFT_SHIFT -> shl
            InfixExpression.Operator.RIGHT_SHIFT_SIGNED -> shr
            InfixExpression.Operator.RIGHT_SHIFT_UNSIGNED -> ushr
            InfixExpression.Operator.LESS -> less
            InfixExpression.Operator.GREATER -> greater
            InfixExpression.Operator.LESS_EQUALS -> lessequals
            InfixExpression.Operator.GREATER_EQUALS -> greaterequals
            InfixExpression.Operator.EQUALS -> equals
            InfixExpression.Operator.NOT_EQUALS -> notequals
            InfixExpression.Operator.XOR -> xor
            InfixExpression.Operator.OR -> or
            InfixExpression.Operator.AND -> and
            InfixExpression.Operator.CONDITIONAL_OR -> or
            InfixExpression.Operator.CONDITIONAL_AND -> and

            else -> throw IllegalArgumentException("Unknown InfixExpression.Operator")
        }

        val expressions = (listOf(node.leftOperand, node.rightOperand) + node.extendedOperands()).map { it as Expression }

        push(operator)
        add(expressions.last(), true)

        if (expressions.size > 2) {
            expressions.drop(1)
                .dropLast(1)
                .reversed()
                .forEach { expression ->
                    push(operator)
                    add(expression, true)
                }
        }

        add(expressions.first())

        return false
    }

    //  Accessors

    override fun visit(node: ArrayAccess): Boolean {
        if (isAssignmentTarget) {
            push(arr_store_rev)
            add(node.array)
            add(node.index)
        } else {
            push(arr_load)
            add(node.index)
            add(node.array)
        }

        return false
    }

    override fun visit(node: FieldAccess): Boolean {
        val binding = node.resolveFieldBinding()

        if (binding.isEnumConstant)
            TODO()

        if (isAssignmentTarget) {
            if (Modifier.isStatic(binding.modifiers)) {
                push(stat_put(binding.declaringClass.signature() as ClassTypeSignature, node.name.identifier, node.resolveTypeBinding().signature()))
            } else {
                push(obj_put(binding.name, binding.type.signature()))
                if (node.expression != null) add(node.expression)
                else push(load("this"))
            }
        } else {
            if (node.expression.resolveTypeBinding().isArray && node.name.identifier == "length") {
                push(arr_length)
                node.expression.accept(this)
            } else if (Modifier.isStatic(binding.modifiers)) {
                push(stat_get(binding.declaringClass.signature() as ClassTypeSignature, node.name.identifier, node.resolveTypeBinding().signature()))
            } else {
                push(obj_get(binding.name, binding.type.signature()))
                if (node.expression != null) add(node.expression)
                else push(load("this"))
            }
        }

        return false
    }

    override fun visit(node: SuperFieldAccess): Boolean {
        if (node.qualifier != null) throw NotImplementedError("qualified super field access not implemented")

        val binding = node.resolveFieldBinding()

        push(obj_get(binding.name, binding.type.signature()))
        push(load("this"))

        return false
    }

    override fun visit(node: SimpleName): Boolean {
        val binding = node.resolveBinding()
        val type = node.resolveTypeBinding()

        if (isAssignmentTarget) {

            if (binding is IVariableBinding) {
                if (binding.isParameter) {
                    push(store(node.identifier, type.signature()))
                } else if (binding.isField) {
                    if (Modifier.isStatic(binding.modifiers)) {
                        push(stat_put(binding.declaringClass.signature() as ClassTypeSignature, node.identifier, type.signature()))
                    } else {
                        push(obj_put(node.identifier, type.signature()))
                        push(load("this"))
                    }
                } else if (binding.isEnumConstant) {
                    TODO()
                } else /* Probably a local */ {
                    push(store(node.identifier, type.signature()))
                }
            } else {
                TODO()
            }

        } else {

            if (binding is IVariableBinding) {
                if (binding.isParameter) {
                    push(load(node.identifier, type.signature()))
                } else if (binding.isField) {
                    if (Modifier.isStatic(binding.modifiers)) {
                        push(stat_get(binding.declaringClass.signature() as ClassTypeSignature, node.identifier, type.signature()))
                    } else {
                        push(obj_get(node.identifier, type.signature()))
                        push(load("this"))
                    }
                } else if (binding.isEnumConstant) {
                    TODO()
                } else /* Probably a local */ {
                    push(load(node.identifier, type.signature()))
                }
            } else {
                TODO()
            }

        }

        return false
    }

    override fun visit(node: QualifiedName): Boolean {
        val binding = node.resolveBinding()

        if (isAssignmentTarget) {
            if (binding is IVariableBinding) {
                if (binding.isField) {
                    if (Modifier.isStatic(binding.modifiers)) {
                        push(stat_put(binding.declaringClass.signature() as ClassTypeSignature, node.name.identifier, node.resolveTypeBinding().signature()))
                    } else {
                        push(obj_put(binding.name, binding.type.signature()))
                        if (node.qualifier != null) add(node.qualifier)
                        else push(load("this"))
                    }
                } else {
                    TODO()
                }
            } else {
                TODO()
            }
        } else {
            if (node.qualifier.resolveTypeBinding().isArray && node.name.identifier == "length") {
                push(arr_length)
                node.qualifier.accept(this)
            } else if (binding is IVariableBinding) {
                if (binding.isEnumConstant) {
                    push(stat_get(binding.declaringClass.signature() as ClassTypeSignature, node.name.identifier, node.resolveTypeBinding().signature()))
                } else if (binding.isField) {
                    if (Modifier.isStatic(binding.modifiers)) {
                        push(stat_get(binding.declaringClass.signature() as ClassTypeSignature, node.name.identifier, node.resolveTypeBinding().signature()))
                    } else {
                        push(obj_get(binding.name, binding.type.signature()))
                        if (node.qualifier != null) add(node.qualifier)
                        else push(load("this"))
                    }
                } else {
                    TODO()
                }
            } else {

                val qbinding = node.qualifier.resolveBinding()
                if (qbinding is IVariableBinding) {
                    if (node.name.identifier == "length") {
                        push(arr_length)
                        node.qualifier.accept(this)
                    }
                } else
                    TODO()
            }
        }

        return false
    }

    //  Literals

    override fun visit(node: BooleanLiteral): Boolean {
        push(ldc_boolean(node.booleanValue()))
        return false
    }

    override fun visit(node: CharacterLiteral): Boolean {
        push(ldc_char(node.escapedValue.first()))
        return false
    }

    override fun visit(node: NullLiteral): Boolean {
        push(ldc_null)
        return false
    }

    override fun visit(node: NumberLiteral): Boolean {
        push(ldc_number(node.token, node.resolveTypeBinding().signature() as PrimitiveTypeSignature))
        return false
    }

    override fun visit(node: StringLiteral): Boolean {
        push(ldc_string(node.escapedValue))
        return false
    }

    override fun visit(node: TypeLiteral): Boolean {
        push(ldc_type(node.type.resolveBinding().signature()))
        return false
    }

    //  Type Based

    override fun visit(node: ThisExpression): Boolean {
        if (node.qualifier != null)
            throw IllegalArgumentException("Scoped this not implemented")

        push(load("this"))
        return false
    }

    override fun visit(node: CastExpression): Boolean {
        push(cast(node.type.resolveBinding().signature()))
        add(node.expression)

        return false
    }

    override fun visit(node: InstanceofExpression): Boolean {
        push(instanceof(node.rightOperand.resolveBinding().signature() as ClassTypeSignature))
        add(node.leftOperand)

        return false
    }

    //  Functional

    override fun visit(node: LambdaExpression): Boolean {
        throw UnsupportedLanguageFeature("Lambda expressions are not supported")
    }

    override fun visit(node: CreationReference): Boolean {
        throw UnsupportedLanguageFeature("Method references are not supported")
    }

    override fun visit(node: ExpressionMethodReference): Boolean {
        throw UnsupportedLanguageFeature("Method references are not supported")
    }

    override fun visit(node: SuperMethodReference): Boolean {
        throw UnsupportedLanguageFeature("Method references are not supported")
    }

    override fun visit(node: TypeMethodReference): Boolean {
        throw UnsupportedLanguageFeature("Method references are not supported")
    }

    //  Not used

    override fun visit(node: TypeDeclarationStatement): Boolean {
        return false
    }
}