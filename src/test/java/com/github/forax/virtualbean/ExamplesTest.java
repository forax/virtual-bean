package com.github.forax.virtualbean;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ExamplesTest {
  @Test
  public void example() {
    Assertions.assertThrows(NullPointerException.class, () -> Example.main(new String[0]));
  }

  @Test
  public void example2() {
    Example2.main(new String[0]);
  }

  @Test
  public void example3() {
    Assertions.assertThrows(NullPointerException.class, () -> Example3.main(new String[0]));
  }

  @Test
  public void example4() {
    Example4.main(new String[0]);
  }

  @Test
  public void example5() {
    Assertions.assertThrows(IllegalArgumentException.class, () -> Example5.main(new String[0]));
  }

  @Test
  public void example6() {
    Assertions.assertThrows(IllegalArgumentException.class, () -> Example6.main(new String[0]));
  }

  @Test
  public void example7() {
    Example7.main(new String[0]);
  }

  @Test
  public void example8() {
    Example8.main(new String[0]);
  }
}
