package com.github.forax.virtualbean.example;

import com.github.forax.virtualbean.BeanFactory;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Objects;

import static com.github.forax.virtualbean.BeanFactory.Interceptor.Kind.POST;

/**
 * Implement an annotation ParametersNonNull that checks if the parameters are null
 * Same behavior as Example.java but uses an interceptor instead of an advice
 * which is a little more complex to use but more efficient.
 */
public class Example2 {
  @Retention(RetentionPolicy.RUNTIME)
  @interface ParametersNonNull { }

  private static final MethodHandle REQUIRE_NON_NULL;
  static {
    try {
      REQUIRE_NON_NULL = MethodHandles.lookup().findStatic(Objects.class, "requireNonNull", MethodType.methodType(Object.class, Object.class, String.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }
  }

  public static void main(String[] args) {
    var lookup = MethodHandles.lookup();
    var beanFactory = new BeanFactory(lookup);

    beanFactory.registerInterceptor(ParametersNonNull.class, (kind, method, type) -> {
      if (kind == POST) {
        return null;
      }
      var parameterTypes = method.getParameterTypes();
      var filters = new MethodHandle[parameterTypes.length];
      for(var i = 0; i < parameterTypes.length; i++) {
        var parameterType = parameterTypes[i];
        if (parameterType.isPrimitive()) {
          continue;
        }
        var requireNonNull = MethodHandles.insertArguments(REQUIRE_NON_NULL, 1, "argument " + i + " of " + method + " is null");
        var filter = requireNonNull.asType(MethodType.methodType(parameterType, parameterType));
        filters[i] = filter;
      }
      var empty = MethodHandles.empty(type);
      return MethodHandles.filterArguments(empty, 1, filters);
    });


    interface HelloManager {
      @ParametersNonNull
      default void sayHello(String text)  {
        System.out.println("hello " + text);
      }
    }

    var helloManager = beanFactory.create(HelloManager.class);
    helloManager.sayHello("Bob");
    helloManager.sayHello(null);
  }
}
