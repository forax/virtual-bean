package com.github.forax.virtualbean.example;

import com.github.forax.virtualbean.BeanFactory;
import com.github.forax.virtualbean.example.Example12.MockExperience.MethodCall;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Mock an interface using an API close to EasyMock.
 */
public class Example12 {
  public static final class MockExperience {
    private static final ThreadLocal<MockExperience> CURRENT = new ThreadLocal<>();

    public static MockExperience current() {
      var experience = CURRENT.get();
      if (experience == null) {
        throw new IllegalStateException("no current lock experience");
      }
      return experience;
    }

    public record MethodCall(Object bean, Method method, List<Object> arguments) { }

    private final ArrayList<MethodCall> expectedTrace = new ArrayList<>();
    private final ArrayList<MethodCall> actualTrace = new ArrayList<>();
    private final IdentityHashMap<Object, Object> delegateMap = new IdentityHashMap<>();
    private boolean replay;

    public List<MethodCall> expectedTrace() {
      return expectedTrace;
    }

    public List<MethodCall> actualTrace() {
      return actualTrace;
    }

    public void replay(Object mock, Object delegate) {
      delegateMap.put(mock, delegate);
      replay = true;
    }

    public void runTest(Runnable runnable) {
      var previous = CURRENT.get();
      CURRENT.set(this);
      try {
        runnable.run();
      } finally {
        CURRENT.set(previous);
      }
    }
  }

  private static void assertEquals(Object expected, Object actual) {
    if (!(expected.equals(actual))) {
      throw new AssertionError("expected " + expected + " actual " + actual);
    }
  }

  @Retention(RetentionPolicy.RUNTIME)
  @interface Mock { }

  public static void main(String[] arguments) {
    var beanFactory = new BeanFactory(MethodHandles.lookup());
    beanFactory.registerInvocationHandler(Mock.class, (method, bean, args) -> {
      var methodCall = new MethodCall(bean, method, List.of(args));
      var experience = MockExperience.current();
      if (!experience.replay) {
        experience.expectedTrace.add(methodCall);
        return null; // should be null, false, 0, 0.0 etc
      }
      experience.actualTrace.add(methodCall);
      var _delegate = experience.delegateMap.get(methodCall.bean);
      try {
        return methodCall.method.invoke(_delegate, methodCall.arguments.toArray());
      } catch (IllegalAccessException | InvocationTargetException e) {
        throw new AssertionError(e);
      }
    });


    /**
     * An example of document manager with a callback when a document is added.
     */
    record DocumentManager(List<String> documents, Listener listener) {
      @Mock
      interface Listener {
        void documentAdded(String document);
      }

      public void add(String document) {
        documents.add(document);
        listener.documentAdded(document);
      }
    }

    var experience = new MockExperience();
    experience.runTest(() -> {
      var mock = beanFactory.create(DocumentManager.Listener.class);
      mock.documentAdded("document1");
      mock.documentAdded("document2");

      DocumentManager.Listener listener = doc -> System.out.println(doc);
      experience.replay(mock, listener);

      var manager = new DocumentManager(new ArrayList<>(), mock);
      manager.add("document1");
      manager.add("document2");

      assertEquals(experience.expectedTrace(), experience.actualTrace());
    });
  }
}
