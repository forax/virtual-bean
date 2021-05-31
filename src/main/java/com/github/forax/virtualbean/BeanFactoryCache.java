package com.github.forax.virtualbean;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MutableCallSite;
import java.lang.reflect.UndeclaredThrowableException;

import static java.lang.invoke.MethodHandles.dropArguments;
import static java.lang.invoke.MethodHandles.insertArguments;
import static java.lang.invoke.MethodType.methodType;

class BeanFactoryCache {
  private static final MethodHandle CACHE = new InliningCache(InliningCache.BOOT).dynamicInvoker();

  public static Object create(BeanFactory beanFactory, Class<?> type) {
    try {
      return CACHE.invokeExact(beanFactory, type);
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable throwable) {
      throw new UndeclaredThrowableException(throwable);
    }
  }

  private static final class InliningCache extends MutableCallSite {
    private static final MethodHandle BEAN_FACTORY_CHECK, CLASS_CHECK, BOOT, DEOPTIMIZE, SLOW, FALLBACK;
    static {
      var lookup = MethodHandles.lookup();
      try {
        BEAN_FACTORY_CHECK = lookup.findStatic(InliningCache.class, "beanFactoryCheck",
            methodType(boolean.class, BeanFactory.class, BeanFactory.class));
        CLASS_CHECK = lookup.findStatic(InliningCache.class, "classCheck",
            methodType(boolean.class, Class.class, Class.class));
        BOOT = lookup.findVirtual(InliningCache.class, "boot",
            methodType(Object.class, BeanFactory.class, Class.class));
        DEOPTIMIZE = lookup.findVirtual(InliningCache.class, "deoptimize",
            methodType(Object.class, BeanFactory.class, Class.class));
        SLOW = lookup.findVirtual(InliningCache.class, "slow",
            methodType(Object.class, BeanFactory.class, Class.class));
        FALLBACK = lookup.findVirtual(InliningCache.class, "fallback",
            methodType(Object.class, BeanFactory.class, Class.class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }

    @SuppressWarnings("ThisEscapedInObjectConstruction")
    private InliningCache(MethodHandle fallback) {
      super(methodType(Object.class, BeanFactory.class, Class.class));
      setTarget(fallback.bindTo(this));
    }

    private static boolean beanFactoryCheck(BeanFactory beanFactory, BeanFactory expectedFactory) {
      return beanFactory == expectedFactory;
    }

    private static boolean classCheck(Class<?> type, Class<?> expectedType) {
      return type == expectedType;
    }

    private Object boot(BeanFactory beanFactory, Class<?> type) throws Throwable {
      var test = insertArguments(BEAN_FACTORY_CHECK, 1, beanFactory);
      var factory = beanFactory.beanFactory(type).asType(methodType(Object.class));
      var beanFactoryGuard = MethodHandles.guardWithTest(test,
          classGuard(factory, type),
          DEOPTIMIZE.bindTo(this));
      setTarget(beanFactoryGuard);
      return factory.invokeExact();
    }

    private Object deoptimize(BeanFactory beanFactory, Class<?> type) throws Throwable {
      setTarget(SLOW.bindTo(this));
      return slow(beanFactory, type);
    }

    private Object slow(BeanFactory beanFactory, Class<?> type) throws Throwable {
      var factory = beanFactory.beanFactory(type);
      return factory.invoke();
    }

    private Object fallback(BeanFactory beanFactory, Class<?> type) throws Throwable {
      var factory = beanFactory.beanFactory(type).asType(methodType(Object.class));
      var classGuard = classGuard(factory, type);
      setTarget(classGuard);
      return factory.invokeExact();
    }

    private MethodHandle classGuard(MethodHandle factory, Class<?> type) {
      var test = dropArguments(insertArguments(CLASS_CHECK, 1, type), 0, BeanFactory.class);
      return MethodHandles.guardWithTest(test,
          MethodHandles.dropArguments(factory, 0, BeanFactory.class, Class.class),
          new InliningCache(FALLBACK).dynamicInvoker()
      );
    }
  }
}
