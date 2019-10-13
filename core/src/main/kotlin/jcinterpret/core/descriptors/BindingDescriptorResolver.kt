package jcinterpret.core.descriptors

import jcinterpret.signature.ClassTypeSignature
import jcinterpret.signature.QualifiedMethodSignature
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration
import org.eclipse.jdt.core.dom.CompilationUnit

class BindingDescriptorResolver (
    compilationUnits: List<CompilationUnit>
): DescriptorResolver() {

    private val classes = mutableMapOf<ClassTypeSignature, TypeBindingClassTypeDescriptor>()

    init {
        for (cu in compilationUnits) {
            for (type in cu.types()) {
                type as AbstractTypeDeclaration

                val desc = TypeBindingClassTypeDescriptor(type.resolveBinding())
                classes[desc.signature] = desc
            }
        }
    }

    override fun tryResolveDescriptor(sig: ClassTypeSignature): ClassTypeDescriptor? {
        return classes[sig]
    }

    override fun tryResolveDescriptor(sig: QualifiedMethodSignature): MethodDescriptor? {
        val cls = tryResolveDescriptor(sig.declaringClassSignature) ?: return null
        return cls.methods[sig.methodSignature.toString()]
    }
}