/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.el;

import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;

public class TestELEvaluator {

  public static int idFunction(int i) {
    return i;
  }

  public static Object varViaContextFunction(String varName) {
    return ELEvaluator.getVariablesInScope().getVariable(varName);
  }

  public static Object contextVarFunction(String varName) {
    return ELEvaluator.getVariablesInScope().getContextVariable(varName);
  }

  @Test
  public void testELEvaluator() throws Exception {
    ELEvaluator eval = new ELEvaluator();
    ELEvaluator.Variables variables = new ELEvaluator.Variables();

    Assert.assertTrue(eval.eval(variables, "${1 eq 1}", Boolean.class));
    Assert.assertEquals(Boolean.TRUE, eval.eval(variables, "${1 eq 1}"));

    variables.addVariable("a", "A");
    Assert.assertTrue(variables.hasVariable("a"));

    Assert.assertEquals(Boolean.TRUE, eval.eval(variables, "${a eq 'A'}"));

    Method function = getClass().getMethod("idFunction", Integer.TYPE);

    eval.registerFunction("", "id", function);
    Assert.assertEquals(Boolean.TRUE, eval.eval(variables, "${id(1) eq 1}"));

    eval.registerFunction("id", "id", function);
    Assert.assertEquals(Boolean.TRUE, eval.eval(variables, "${id:id(1) eq 1}"));

    function = getClass().getMethod("varViaContextFunction", String.class);

    eval.registerFunction("", "varViaContext", function);
    Assert.assertEquals(Boolean.TRUE, eval.eval(variables, "${varViaContext('a') eq 'A'}"));

    eval.registerConstant("X", "x");
    Assert.assertEquals("x", eval.eval(variables, "${X}"));

    function = getClass().getMethod("contextVarFunction", String.class);

    variables.addContextVariable("c", "C");
    Assert.assertTrue(variables.hasContextVariable("c"));

    eval.registerFunction("", "contextVar", function);
    Assert.assertEquals(Boolean.TRUE, eval.eval(variables, "${contextVar('c') eq 'C'}"));

  }

}
