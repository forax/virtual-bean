package com.github.forax.virtualbean.example;

import com.github.forax.virtualbean.BeanFactory;
import com.github.forax.virtualbean.BeanFactory.Interceptor;
import com.github.forax.virtualbean.Metadata;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Validate property value when the setter is called by calling a validation method.
 */
public class Example11 {
  @Retention(RetentionPolicy.RUNTIME)
  @interface Validate {}

  public static void main(String[] args) {
    var lookup = MethodHandles.lookup();
    var beanFactory = new BeanFactory(lookup);
    beanFactory.registerInterceptor(Validate.class, Metadata::isSetter, (kind, method, type) -> {
      if (kind == Interceptor.Kind.POST) {
        return null;
      }
      var validationName = "validate" + method.getName().substring(3);
      MethodHandle validation;
      try {
        validation = lookup.findVirtual(method.getDeclaringClass(), validationName,
            MethodType.methodType(void.class, type.parameterType(1)));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw (LinkageError) new LinkageError().initCause(e);
      }
      return validation.asType(type);
    });

    @Validate
    interface Person {
      String getName();
      void setName(String name);

      default void validateName(String name) {
        if (name == null || name.isBlank()) {
          throw new IllegalArgumentException("invalid name \"%s\"".formatted(name));
        }
      }
    }

    var person = beanFactory.create(Person.class);
    person.setName("Bob");
    person.setName("");  // oops
  }
}
