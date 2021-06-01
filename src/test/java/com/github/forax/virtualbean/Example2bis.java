package com.github.forax.virtualbean;

import com.github.forax.virtualbean.BeanFactory.Advice;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Tracks bean that are modified by storing them in a "dirty" set links to a transaction
 * Auto-wire the BeanFactory so it is available in the service
 */
public class Example2bis {
  @Retention(RetentionPolicy.RUNTIME)
  @interface Entity { }

  @Retention(RetentionPolicy.RUNTIME)
  @interface Transactional { }

  @Retention(RetentionPolicy.RUNTIME)
  @interface Autowired { }

  private static final ThreadLocal<EntityManager> ENTITY_MANAGERS = new ThreadLocal<>();

  record EntityManager(Set<Object> dirtySet) {
    public static EntityManager current() {
      return ENTITY_MANAGERS.get();
    }

    public void update() {
      System.out.println("dirtySet " + dirtySet);
      dirtySet.clear();
    }
  }

  public static void main(String[] args) {
    var lookup = MethodHandles.lookup();
    var beanFactory = new BeanFactory(lookup);

    beanFactory.registerImplementor(Autowired.class, (method, type) -> {
      var constant = MethodHandles.constant(BeanFactory.class, beanFactory);
      return MethodHandles.dropArguments(constant, 0, type.parameterList());
    });
    beanFactory.registerAdvice(Entity.class, Metadata::isSetter, new Advice() {
      @Override
      public void pre(Method method, Object bean, Object[] args) { }

      @Override
      public void post(Method method, Object bean, Object[] args) {
        var entityManager = EntityManager.current();
        if (entityManager != null) {
          entityManager.dirtySet.add(bean);
        }
      }
    });
    beanFactory.registerAdvice(Transactional.class, new Advice() {
      @Override
      public void pre(Method method, Object bean, Object[] args) {
        var dirtySet = Collections.newSetFromMap(new IdentityHashMap<>());
        var entityManager = new EntityManager(dirtySet);
        ENTITY_MANAGERS.set(entityManager);
      }

      @Override
      public void post(Method method, Object bean, Object[] args) {
        ENTITY_MANAGERS.remove();
      }
    });


    @Entity
    interface UserBean {
      String getName();
      void setName(String name);
      String getEmail();
      void setEmail(String name);
    }

    interface UserManager {
      @Transactional
      default UserBean createUser(String name, String email) {
        var user = beanFactory().create(UserBean.class);
        user.setName(name);
        user.setEmail(email);

        EntityManager.current().update();
        return user;
      }

      @Autowired
      BeanFactory beanFactory();
    }

    var userManager = beanFactory.create(UserManager.class);
    var user = userManager.createUser("Duke", "duke@openjdk.java.net");
    System.out.println("user " + user.getName() + " " + user.getEmail());
  }
}
