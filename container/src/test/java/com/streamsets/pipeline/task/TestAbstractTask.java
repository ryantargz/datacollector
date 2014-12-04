/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.task;

import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;

public class TestAbstractTask {

  @Test(expected = NullPointerException.class)
  public void testConstructorInvalid() {
    new AbstractTask(null){};
  }

  @Test
  public void testConstructorValid() {
    Task task = new AbstractTask("name"){};
    Assert.assertEquals("name", task.getName());
  }

  @Test
  public void testLifecycleAndStatus() {
    Task task = new AbstractTask("name"){};
    Assert.assertEquals(Task.Status.CREATED, task.getStatus());
    task.init();
    Assert.assertEquals(Task.Status.INITIALIZED, task.getStatus());
    task.run();
    Assert.assertEquals(Task.Status.RUNNING, task.getStatus());
    task.stop();
    Assert.assertEquals(Task.Status.STOPPED, task.getStatus());

    task = new AbstractTask("name"){};
    task.init();
    task.stop();
    Assert.assertEquals(Task.Status.STOPPED, task.getStatus());

    task.stop();

    Assert.assertTrue(task.toString().contains("name"));
    Assert.assertTrue(task.toString().contains("STOPPED"));
  }

  @Test(expected = IllegalStateException.class)
  public void testInvalidLifecycle1() {
    Task task = new AbstractTask("name"){};
    task.run();
  }

  @Test(expected = IllegalStateException.class)
  public void testInvalidLifecycle2() {
    Task task = new AbstractTask("name"){};
    task.stop();
  }

  @Test(expected = IllegalStateException.class)
  public void testInvalidLifecycle3() {
    Task task = new AbstractTask("name"){};
    task.init();
    task.init();
  }

  @Test(expected = IllegalStateException.class)
  public void testInvalidLifecycle4() {
    Task task = new AbstractTask("name"){};
    task.init();
    task.run();
    task.run();
  }

  @Test(expected = RuntimeException.class)
  public void testInitError() {
    Task task = new AbstractTask("name"){
      @Override
      protected void initTask() {
        throw new RuntimeException();
      }
    };
    try {
      task.init();
    } finally {
      Assert.assertEquals(Task.Status.ERROR, task.getStatus());
    }
  }

  @Test(expected = RuntimeException.class)
  public void testRunError() {
    Task task = new AbstractTask("name"){
      @Override
      protected void runTask() {
        throw new RuntimeException();
      }
    };
    task.init();
    try {
      task.run();
    } finally {
      Assert.assertEquals(Task.Status.ERROR, task.getStatus());
    }
  }

  @Test
  public void testStopError() {
    Task task = new AbstractTask("name"){
      @Override
      protected void stopTask() {
        throw new RuntimeException();
      }
    };
    task.init();
    task.run();
    task.stop();
    Assert.assertEquals(Task.Status.ERROR, task.getStatus());
  }

  @Test(expected = IllegalStateException.class)
  public void testInitInError() {
    Task task = new AbstractTask("name"){
      @Override
      public Status getStatus() {
        return Status.ERROR;
      }
    };
    task.init();
  }

  @Test(expected = IllegalStateException.class)
  public void testRunInError() {
    Task task = new AbstractTask("name"){
      @Override
      public Status getStatus() {
        return Status.ERROR;
      }
    };
    task.run();
  }

  @Test(expected = IllegalStateException.class)
  public void testStopInError() {
    Task task = new AbstractTask("name"){
      @Override
      public Status getStatus() {
        return Status.ERROR;
      }
    };
    task.stop();
  }

  @Test
  public void testWaitWhileRunning() throws Exception {
    final CountDownLatch latch1 = new CountDownLatch(1);
    final CountDownLatch latch2 = new CountDownLatch(1);
    final Task task = new AbstractTask("name"){};
    task.init();
    Thread waiter = new Thread() {
      @Override
      public void run() {
        try {
          latch1.await();
          latch2.countDown();
          task.waitWhileRunning();
        } catch (InterruptedException ex) {
          //NOP
        }
      }
    };
    waiter.start();
    task.run();
    latch1.countDown();
    latch2.await();
    task.stop();
    waiter.join();
  }

  @Test
  public void testInvalidWaitWhileRunningWhenStopped() throws Exception {
    final Task task = new AbstractTask("name"){};
    task.init();
    task.run();
    task.stop();
    task.waitWhileRunning();
  }

  @Test(expected = IllegalStateException.class)
  public void testInvalidWaitWhileRunning1() throws Exception {
    final Task task = new AbstractTask("name"){};
    task.waitWhileRunning();
  }

  @Test(expected = IllegalStateException.class)
  public void testInvalidWaitWhileRunning2() throws Exception {
    final Task task = new AbstractTask("name"){};
    task.init();
    task.waitWhileRunning();
  }

  @Test(expected = IllegalStateException.class)
  public void testInvalidWaitWhileRunning4() throws Exception {
    Task task = new AbstractTask("name"){
      @Override
      public Status getStatus() {
        return Status.ERROR;
      }
    };
    task.waitWhileRunning();
  }
}
