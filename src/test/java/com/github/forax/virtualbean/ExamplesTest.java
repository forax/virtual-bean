package com.github.forax.virtualbean;

import com.github.forax.virtualbean.example.Example;
import com.github.forax.virtualbean.example.Example10;
import com.github.forax.virtualbean.example.Example3;
import com.github.forax.virtualbean.example.Example9;
import com.github.forax.virtualbean.example.Example2;
import com.github.forax.virtualbean.example.Example4;
import com.github.forax.virtualbean.example.Example5;
import com.github.forax.virtualbean.example.Example6;
import com.github.forax.virtualbean.example.Example7;
import com.github.forax.virtualbean.example.Example8;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ExamplesTest {
  @Test
  public void example() {
    Assertions.assertThrows(NullPointerException.class, () -> Example.main(new String[0]));
  }

  @Test
  public void example2() {
    Assertions.assertThrows(NullPointerException.class, () -> Example2.main(new String[0]));
  }

  @Test
  public void example3() {
    Example3.main(new String[0]);
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

  @Test
  public void example9() {
    Example9.main(new String[0]);
  }

  @Test
  public void example10() {
    Example10.main(new String[0]);
  }
}
