package com.github.forax.virtualbean;

import java.io.Serializable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Executable;
import java.lang.reflect.UndeclaredThrowableException;

class LambdaCracker {
  static SerializedLambda extractLambdaInfo(Lookup lookup, Serializable lambda) {
    if (!lambda.getClass().isHidden()) {
      throw new IllegalArgumentException("lambda is not a hiddenClass");
    }

    MethodHandle mh;
    try {
      mh = lookup.findVirtual(lambda.getClass(), "writeReplace", MethodType.methodType(Object.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new IllegalStateException("can not extract method handle info from " + lambda, e);
    }

    Object serializationProxy;
    try {
      serializationProxy = mh.invoke(lambda);
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable t) {
      throw new UndeclaredThrowableException(t, "can not call writeReplace");
    }
    return (SerializedLambda) serializationProxy;
  }

  private static final int H_INVOKEVIRTUAL = 5;
  private static final int H_INVOKESTATIC = 6;
  //private static final int H_INVOKESPECIAL = 7;
  //private static final int H_NEWINVOKESPECIAL = 8;
  private static final int H_INVOKEINTERFACE = 9;


  static Executable extractMethodOrConstructor(Lookup lookup, SerializedLambda lambda) {
    Class<?> implClass;
    try {
      implClass = lookup.findClass(lambda.getImplClass().replace('/', '.'));
    } catch (ClassNotFoundException e) {
      throw (NoClassDefFoundError) new NoClassDefFoundError().initCause(e);
    } catch(IllegalAccessException e) {
      throw (IllegalAccessError) new IllegalAccessError().initCause(e);
    }
    var implMethodName = lambda.getImplMethodName();
    var implMethodType = MethodType.fromMethodDescriptorString(lambda.getImplMethodSignature(), implClass.getClassLoader());
    MethodHandle mh;
    try {
      mh = switch (lambda.getImplMethodKind()) {
        case H_INVOKEVIRTUAL, H_INVOKEINTERFACE -> lookup.findVirtual(implClass, implMethodName, implMethodType);
        case H_INVOKESTATIC -> lookup.findStatic(implClass, implMethodName, implMethodType);
        default -> throw new AssertionError("invalid implementation method kind");
      };
    } catch(NoSuchMethodException e) {
      throw (NoSuchMethodError) new NoSuchMethodError().initCause(e);
    } catch (IllegalAccessException e) {
      throw (IllegalAccessError) new IllegalAccessError().initCause(e);
    }
    var mhInfo = lookup.revealDirect(mh);
    return mhInfo.reflectAs(Executable.class, lookup);
  }
}
