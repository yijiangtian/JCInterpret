package jcinterpret.comparison.iterative

import jcinterpret.core.memory.heap.*
import jcinterpret.core.memory.stack.*

object ValueComparator {

    fun compare(lvalue: Any, rvalue: Any): Boolean {
        return if (lvalue is StringValue && rvalue is StringValue) compare(lvalue, rvalue)
        else if (lvalue is StackValue && rvalue is StackValue) compare(lvalue, rvalue)
        else false
    }

    fun StringValue.collect(list: MutableList<StringValue>, visited: MutableSet<Int>): List<StringValue> {
        if (this is CompositeStringValue) {
            if (!visited.contains(System.identityHashCode(this))) {
                this.lhs.collect(list, visited)
                this.rhs.collect(list, visited)
            }

        } else {
            list.add(this)
        }

        visited.add(System.identityHashCode(this))
        return list
    }

    fun compare(lvalue: StringValue, rvalue: StringValue): Boolean {
        if (lvalue is ConcreteStringValue && rvalue is ConcreteStringValue)
            return lvalue.value == rvalue.value

        if (lvalue is SymbolicStringValue && rvalue is SymbolicStringValue)
            return true

        if (lvalue is StackValueStringValue && rvalue is StackValueStringValue)
            return compare(lvalue.value, rvalue.value)

        if (lvalue is CompositeStringValue && rvalue is CompositeStringValue) {

            val lcomp = lvalue.collect(mutableListOf(), mutableSetOf())
            val rcomp = rvalue.collect(mutableListOf(), mutableSetOf())

            if (lcomp.size != rcomp.size) return false

            for (i in 0 until lcomp.size) {
                val lhs = lcomp[i]
                val rhs = rcomp[i]

                if (!ValueComparator.compare(lhs, rhs)) return false

                return true
            }
        }

        return false
    }

    fun compare(lvalue: StackValue, rvalue: StackValue): Boolean {

        if (lvalue is StackNil && rvalue is StackNil) return true

        if (lvalue is StackReference && rvalue is StackReference) return lvalue.id == rvalue.id

        if (lvalue is SymbolicValue && rvalue is SymbolicValue) return lvalue.type == rvalue.type

        if (lvalue is ComputedValue && rvalue is ComputedValue)
            return compare(lvalue, rvalue)

        if (lvalue is ConcreteValue<*> && rvalue is ConcreteValue<*>)
            return compare(lvalue, rvalue)

        return false
    }

    fun compare(lvalue: ComputedValue, rvalue: ComputedValue): Boolean {
        if (lvalue is NotValue && rvalue is NotValue)
            return compare(lvalue.value, rvalue.value)

        if (lvalue is CastValue && rvalue is CastValue)
            return compare(lvalue.value, rvalue.value)

        if (lvalue is BinaryOperationValue && rvalue is BinaryOperationValue)
            return lvalue.operator == rvalue.operator &&
                    (compare(lvalue.lhs, rvalue.lhs) && compare(lvalue.rhs, rvalue.rhs) ||
                            compare(lvalue.lhs, rvalue.rhs) && compare(lvalue.rhs, rvalue.lhs))

        return false
    }

    fun compare(lvalue: ConcreteValue<*>, rvalue: ConcreteValue<*>): Boolean {

        if (lvalue is StackBoolean && rvalue is StackBoolean)
            return lvalue.value == rvalue.value

        if (lvalue is StackBoolean || rvalue is StackBoolean)
            return false

        val lnumd = lvalue.number().toDouble()
        val rnumd = rvalue.number().toDouble()

        val lnumi = lvalue.number().toInt()
        val rnumi = rvalue.number().toInt()

        return lnumd == rnumd || lnumi == rnumi
    }
}