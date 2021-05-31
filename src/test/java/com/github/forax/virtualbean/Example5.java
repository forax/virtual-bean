package com.github.forax.virtualbean;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.Arrays;

import static java.lang.invoke.MethodHandles.lookup;

/**
 * Checks if parameter values are between a minim and a maximum.
 */
public class Example5 {
  @Retention(RetentionPolicy.RUNTIME)
  @interface BoundChecks {
    int max();
    int min();
  }

  public static void main(String[] args) {
    var beanFactory = new BeanFactory(lookup());

    beanFactory.registerAdvice(BoundChecks.class, new BeanFactory.Advice() {
      @Override
      public void pre(Method method, Object bean, Object[] args) {
        System.out.println("pre " + Arrays.toString(args));
        var parameterAnnotations = method.getParameterAnnotations();
        for(var i = 0; i < args.length; i++) {
          for(var annotation:  parameterAnnotations[i]) {
            if (annotation instanceof BoundChecks boundChecks) {
              var value = (int) args[i];
              if (value < boundChecks.min() || value > boundChecks.max()) {
                throw new IllegalArgumentException("invalid value " + value);
              }
            }
          }
        }
      }

      @Override
      public void post(Method method, Object bean, Object[] args) {
        System.out.println("post " + Arrays.toString(args));
      }
    });

    interface Service {
      default void foo(@BoundChecks(min = 0, max = 10) int value)  {
        System.out.println("foo " + value);
      }
    }

    var service = beanFactory.create(Service.class);
    service.foo(3);
    service.foo(-1);
  }
}
