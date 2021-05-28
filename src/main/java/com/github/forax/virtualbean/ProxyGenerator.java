package com.github.forax.virtualbean;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.PrintWriter;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.Method;
import java.lang.runtime.ObjectMethods;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static java.lang.invoke.MethodHandles.Lookup.ClassOption.NESTMATE;
import static java.lang.invoke.MethodHandles.Lookup.ClassOption.STRONG;
import static java.lang.invoke.MethodType.methodType;
import static java.util.stream.Collectors.joining;
import static org.objectweb.asm.ClassWriter.COMPUTE_FRAMES;
import static org.objectweb.asm.ClassWriter.COMPUTE_MAXS;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_VARARGS;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.ASTORE;
import static org.objectweb.asm.Opcodes.ATHROW;
import static org.objectweb.asm.Opcodes.CHECKCAST;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.GETFIELD;
import static org.objectweb.asm.Opcodes.H_GETFIELD;
import static org.objectweb.asm.Opcodes.H_INVOKEINTERFACE;
import static org.objectweb.asm.Opcodes.H_INVOKESTATIC;
import static org.objectweb.asm.Opcodes.ICONST_0;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Opcodes.NEW;
import static org.objectweb.asm.Opcodes.PUTFIELD;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V16;

@SuppressWarnings("HardcodedFileSeparator")
class ProxyGenerator {
  public static <T> Supplier<T> createProxyFactory(Lookup lookup, Class<? extends T> proxyType, MethodHandle bsm) {
    var array = gen(lookup.lookupClass(), proxyType);
    Lookup proxyLookup;
    try {
      proxyLookup = lookup.defineHiddenClassWithClassData(array, bsm, true, NESTMATE, STRONG);
    } catch (IllegalAccessException e) {
      throw new IllegalArgumentException("lookup is not fully privileged", e);
    } catch(VerifyError e) {
      CheckClassAdapter.verify(new ClassReader(array), true, new PrintWriter(System.err, false, StandardCharsets.UTF_8));
      throw e;
    }
    MethodHandle constructor;
    try {
      constructor = proxyLookup.findConstructor(proxyLookup.lookupClass(), methodType(void.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }
    return () -> {
      try {
        return proxyType.cast(constructor.invoke());
      } catch(RuntimeException | Error e) {
        throw e;
      } catch (Throwable throwable) {
        throw new AssertionError(throwable);
      }
    };
  }

  private static final Handle CONDY_BSM = new Handle(H_INVOKESTATIC, "java/lang/invoke/MethodHandles", "classData",
      "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;",
      false);

  private static byte[] gen(Class<?> lookupClass, Class<?> proxyType) {
    var internalName = lookupClass.getPackageName().replace('.', '/') + '/' + proxyType.getSimpleName() + "$$Proxy";
    var writer = new ClassWriter(COMPUTE_FRAMES | COMPUTE_MAXS);
    writer.visit(V16, ACC_PUBLIC,
        internalName, null,
        "java/lang/Object", new String[] { proxyType.getName().replace('.', '/') });

    {
      // constructor
      var init = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
      init.visitCode();
      init.visitVarInsn(ALOAD, 0);
      init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
      init.visitInsn(RETURN);
      init.visitMaxs(-1, -1);
      init.visitEnd();
    }

    var bsm = new Handle(H_INVOKESTATIC, internalName, "bsm",
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;)Ljava/lang/invoke/CallSite;", false);

    var metadata = Metadata.of(proxyType);

    // properties
    var properties = metadata.properties().values();
    for(var property: properties) {
      var fv = writer.visitField(ACC_PRIVATE, property.name(), property.type().descriptorString(), null, null);
      fv.visitEnd();

      // getter
      genMethod(writer, property.getter(), bsm, mv -> {
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, internalName, property.name(), property.type().descriptorString());
      });
      // setter
      genMethod(writer, property.setter(), bsm, mv -> {
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(Type.getType(property.type()).getOpcode(ILOAD), 1);
        mv.visitFieldInsn(PUTFIELD, internalName, property.name(), property.type().descriptorString());
      });
    }

    // services
    for(var service: metadata.services().values()) {
      var method = service.method();
      var name = method.getName();
      var desc = Type.getMethodDescriptor(method);
      genMethod(writer, method, bsm, mv -> {
        var declaringClass = method.getDeclaringClass();
        loadThisAndArgumentsOnStack(mv, Type.getArgumentTypes(desc));
        mv.visitMethodInsn(INVOKESPECIAL, declaringClass.getName().replace('.', '/'), name, desc, true);
      });
    }

    // object methods
    var bsmObject = new Handle(H_INVOKESTATIC, internalName, "bsmObject",
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/invoke/MethodHandle;)Ljava/lang/invoke/CallSite;", false);

    genToString(writer, bsmObject, internalName, properties);
    genEquals(writer, bsmObject, internalName, properties);
    genHashCode(writer, bsmObject, internalName, properties);

    // BSM for pre/post methods
    {
      var mv = writer.visitMethod(ACC_PRIVATE | ACC_STATIC, "bsm",
          "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;)Ljava/lang/invoke/CallSite;",
          null, null);
      mv.visitCode();
      //var start = new Label();
      //var end = new Label();
      //var handler = new Label();
      //mv.visitTryCatchBlock(start, end, handler, null);
      //mv.visitLabel(start);
      mv.visitLdcInsn(new ConstantDynamic("_", "Ljava/lang/invoke/MethodHandle;", CONDY_BSM));
      mv.visitVarInsn(ALOAD, 0);
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ALOAD, 2);
      mv.visitVarInsn(ALOAD, 3);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "invokeExact",
          "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;)Ljava/lang/invoke/CallSite;",
          false);
      mv.visitInsn(ARETURN);
      //mv.visitLabel(end);
      //mv.visitLabel(handler);
      //mv.visitInsn(DUP);
      //mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Throwable", "printStackTrace", "()V", false);
      //mv.visitInsn(ATHROW);
      mv.visitMaxs(-1, -1);
      mv.visitEnd();
    }

    // BSM for Objects methods
    {
      var mv = writer.visitMethod(ACC_PRIVATE | ACC_STATIC | ACC_VARARGS, "bsmObject",
          "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/invoke/MethodHandle;)Ljava/lang/invoke/CallSite;",
          null, null);
      mv.visitCode();
      //var start = new Label();
      //var end = new Label();
      //var handler = new Label();
      //mv.visitTryCatchBlock(start, end, handler, null);
      //mv.visitLabel(start);
      mv.visitTypeInsn(NEW, "java/lang/invoke/ConstantCallSite");
      mv.visitInsn(DUP);
      mv.visitVarInsn(ALOAD, 0);
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ALOAD, 2);
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ALOAD, 0);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup", "lookupClass", "()Ljava/lang/Class;", false);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/invoke/MethodType", "changeParameterType", "(ILjava/lang/Class;)Ljava/lang/invoke/MethodType;", false);
      mv.visitVarInsn(ALOAD, 3);
      mv.visitVarInsn(ALOAD, 4);
      mv.visitVarInsn(ALOAD, 5);
      mv.visitMethodInsn(INVOKESTATIC, ObjectMethods.class.getName().replace('.', '/'), "bootstrap",
          "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/TypeDescriptor;Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/invoke/MethodHandle;)Ljava/lang/Object;",
          false);
      mv.visitTypeInsn(CHECKCAST, "java/lang/invoke/CallSite");
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/invoke/CallSite", "getTarget", "()Ljava/lang/invoke/MethodHandle;", false);
      mv.visitVarInsn(ALOAD, 2);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "asType", "(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false);
      mv.visitMethodInsn(INVOKESPECIAL, "java/lang/invoke/ConstantCallSite", "<init>", "(Ljava/lang/invoke/MethodHandle;)V", false);
      mv.visitInsn(ARETURN);
      //mv.visitLabel(end);
      //mv.visitLabel(handler);
      //mv.visitInsn(DUP);
      //mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Throwable", "printStackTrace", "()V", false);
      //mv.visitInsn(ATHROW);
      mv.visitMaxs(-1, -1);
      mv.visitEnd();
    }

    //new ClassReader(writer.toByteArray()).accept(new TraceClassVisitor(new PrintWriter(System.err, true, StandardCharsets.UTF_8)), 0);

    return writer.toByteArray();
  }

  private static void genMethod(ClassVisitor cv, Method method, Handle bsm, Consumer<MethodVisitor> code) {
    var methodName = method.getName();
    var methodDeclaringName = method.getDeclaringClass().getName().replace('.', '/');
    var desc = Type.getMethodDescriptor(method);
    var mv = cv.visitMethod(ACC_PUBLIC, methodName, desc, null, null);
    mv.visitCode();

    var start = new Label();
    var end = new Label();
    var handler = new Label();
    mv.visitTryCatchBlock(start, end, handler, null);

    mv.visitLabel(start);
    var parameterTypes = Type.getArgumentTypes(desc);
    var returnType = Type.getReturnType(desc);
    var impl = new Handle(H_INVOKEINTERFACE, methodDeclaringName, methodName, desc, true);
    insertIndy(mv, bsm,"pre", parameterTypes, impl);

    code.accept(mv);

    mv.visitLabel(end);
    insertIndy(mv, bsm, "post", parameterTypes, impl);
    mv.visitInsn(returnType.getOpcode(IRETURN));

    mv.visitLabel(handler);
    mv.visitVarInsn(ASTORE, 0);
    insertIndy(mv, bsm, "post", parameterTypes, impl);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitInsn(ATHROW);

    mv.visitMaxs(-1, -1);
    mv.visitEnd();
  }

  private static void loadThisAndArgumentsOnStack(MethodVisitor mv, Type[] parameterTypes) {
    mv.visitVarInsn(ALOAD, 0);
    var slot = 1;
    for(var type: parameterTypes) {
      mv.visitVarInsn(type.getOpcode(ILOAD), slot);
      slot += type.getSize();
    }
  }

  private static void insertIndy(MethodVisitor mv, Handle bsm, String phase, Type[] parameterTypes, Handle impl) {
    loadThisAndArgumentsOnStack(mv, parameterTypes);
    mv.visitInvokeDynamicInsn(phase, "(Ljava/lang/Object;" + Type.getMethodDescriptor(Type.VOID_TYPE, parameterTypes).substring(1), bsm, impl);
  }

  private static Stream<Handle> asConstantMH(String internalName, Collection<Metadata.Property> properties) {
    return properties.stream()
        .map(p -> new Handle(H_GETFIELD, internalName, p.name(), Type.getDescriptor(p.type()), false));
  }

  private static Object[] objectBSMArgs(String internalName, String text, Collection<Metadata.Property> properties) {
    return Stream.of(Stream.of(Type.getObjectType(internalName), text), asConstantMH(internalName, properties)).flatMap(s -> s).toArray();
  }

  private static void genToString(ClassVisitor cv, Handle objectBsm, String internalName, Collection<Metadata.Property> properties) {
    var mv = cv.visitMethod(ACC_PUBLIC, "toString", "()Ljava/lang/String;", null, null);
    mv.visitCode();
    var names = properties.stream().map(Metadata.Property::name).collect(joining(";"));
    mv.visitVarInsn(ALOAD, 0);
    mv.visitInvokeDynamicInsn("toString", "(Ljava/lang/Object;)Ljava/lang/String;", objectBsm, objectBSMArgs(internalName, names, properties));
    mv.visitInsn(ARETURN);
    mv.visitMaxs(-1, -1);
    mv.visitEnd();
  }

  private static void genEquals(ClassVisitor cv, Handle objectBsm, String internalName, Collection<Metadata.Property> properties) {
    var mv = cv.visitMethod(ACC_PUBLIC, "equals", "(Ljava/lang/Object;)Z", null, null);
    mv.visitCode();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitInvokeDynamicInsn("equals", "(Ljava/lang/Object;Ljava/lang/Object;)Z", objectBsm, objectBSMArgs(internalName, "", properties));
    mv.visitInsn(IRETURN);
    mv.visitMaxs(-1, -1);
    mv.visitEnd();
  }

  private static void genHashCode(ClassVisitor cv, Handle objectBsm, String internalName, Collection<Metadata.Property> properties) {
    var mv = cv.visitMethod(ACC_PUBLIC, "hashCode", "()I", null, null);
    mv.visitCode();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitInvokeDynamicInsn("hashCode", "(Ljava/lang/Object;)I", objectBsm, objectBSMArgs(internalName, "", properties));
    mv.visitInsn(IRETURN);
    mv.visitMaxs(-1, -1);
    mv.visitEnd();
  }
}
