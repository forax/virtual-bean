package com.github.forax.virtualbean;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;

import static com.github.forax.virtualbean.BeanFactory.Interceptor.Kind.POST;

public class Example6 {
  @Retention(RetentionPolicy.RUNTIME)
  @interface BoundChecks {
    int max();
    int min();
  }

  interface Service {
    default void foo(@BoundChecks(min = 0, max = 10) int value)  {
      System.out.println("foo " + value);
    }
  }

  private static int checkBounds(int value, int min, int max) {
    if (value < min || value >max) {
      throw new IllegalArgumentException("invalid value " + value);
    }
    return value;
  }

  private static final MethodHandle CHECK_BOUNDS;
  static {
    try {
      CHECK_BOUNDS = MethodHandles.lookup().findStatic(Example6.class, "checkBounds", MethodType.methodType(int.class, int.class, int.class, int.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }
  }

  public static void main(String[] args) {
    var lookup = MethodHandles.lookup();
    var beanFactory = new BeanFactory(lookup);

    beanFactory.registerInterceptor(BoundChecks.class, __ -> true, (kind, method, type) -> {
      if (kind == POST) {
        return null;
      }
      var parameterAnnotations = method.getParameterAnnotations();
      var filters = new MethodHandle[parameterAnnotations.length];
      for(var i = 0; i < parameterAnnotations.length; i++) {
        var boundChecksOpt = Arrays.stream(parameterAnnotations[i])
            .<BoundChecks>mapMulti((a, consumer) -> {
              if (a instanceof BoundChecks boundChecks) {
                consumer.accept(boundChecks);
              }
            })
            .findFirst();
        if (boundChecksOpt.isEmpty()) {
          continue;
        }
        var boundChecks = boundChecksOpt.orElseThrow();
        var filter = MethodHandles.insertArguments(CHECK_BOUNDS, 1, boundChecks.min(), boundChecks.max());
        filters[i] = filter;
      }
      var empty = MethodHandles.empty(type);
      return MethodHandles.filterArguments(empty, 1, filters);
    });

    var service = beanFactory.create(Service.class);
    service.foo(3);
    service.foo(-1);
  }
}
