package com.github.forax.virtualbean;

import com.github.forax.virtualbean.BeanFactory.Advice;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;

public class Example {
  @Retention(RetentionPolicy.RUNTIME)
  @interface ParametersNonNull { }

  interface HelloManager {
    @ParametersNonNull
    default void sayHello(String text)  {
      System.out.println("hello " + text);
    }
  }

  public static void main(String[] args) {
    var lookup = MethodHandles.lookup();
    var beanFactory = new BeanFactory(lookup);

    beanFactory.registerAdvice(ParametersNonNull.class, new Advice() {
      @Override
      public void pre(Method method, Object bean, Object[] args) {
        System.out.println("pre " + Arrays.toString(args));
        for (int i = 0; i < args.length; i++) {
          Objects.requireNonNull(args[i], "argument " + i + " of " + method + " is null");
        }
      }

      @Override
      public void post(Method method, Object bean, Object[] args) {
        System.out.println("post " + Arrays.toString(args));
      }
    });

    var helloManager = beanFactory.create(HelloManager.class);
    helloManager.sayHello("Bob");
    helloManager.sayHello(null);
  }
}
