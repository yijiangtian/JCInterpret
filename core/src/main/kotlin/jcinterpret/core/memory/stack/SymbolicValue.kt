package jcinterpret.core.memory.stack

import com.fasterxml.jackson.annotation.JsonTypeInfo

//
//  Symbolic Value
//

@JsonTypeInfo(use= JsonTypeInfo.Id.CLASS, include= JsonTypeInfo.As.PROPERTY, property="@class")
data class SymbolicValue(val symbol: Int, override val type: StackType): StackValue() {
    override fun label(): String {
        return "#$symbol ${type.name}"
    }

//    override fun <T, U> accept(visitor: StackValueVisitor<T, U>, arg: T): U {
//        return visitor.visit(this, arg)
//    }
}