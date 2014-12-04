/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.lib.stage.source.logtail;

import com.google.common.collect.ImmutableSet;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.lib.stage.source.logtail.LogTailSource;
import com.streamsets.pipeline.sdk.testharness.SourceRunner;
import org.apache.commons.io.IOUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TestTailLogSource {
  private String logFile;

  @Before
  public void setUp() throws IOException {
    File testDataDir = new File("target", UUID.randomUUID().toString());
    testDataDir.mkdirs();
    logFile = new File(testDataDir, "logFile.txt").getAbsolutePath();
    InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream("testLogFile.txt");
    OutputStream os = new FileOutputStream(logFile);
    IOUtils.copy(is, os);
    is.close();
    os.close();
  }

  @Test
  public void testTailFromEnd() throws Exception {
    long start = System.currentTimeMillis();
    Map<String, List<Record>> result = new SourceRunner.Builder<LogTailSource>().addSource(LogTailSource.class)
      .configure("logFileName", logFile)
      .configure("tailFromEnd", true)
      .configure("maxLinesPrefetch", 50)
      .configure("batchSize", 25)
      .configure("maxWaitTime", 100)
      .maxBatchSize(100)
      .outputLanes(ImmutableSet.of("lane"))
      .sourceOffset(null)
      .build()
      .run();
    long end = System.currentTimeMillis();
    Assert.assertTrue(end - start > 100);
    Assert.assertTrue(result.isEmpty());
  }

  @Test
  public void testTailFromBeginning() throws Exception {
    Map<String, List<Record>> result = new SourceRunner.Builder<LogTailSource>().addSource(LogTailSource.class)
      .configure("logFileName", logFile)
      .configure("tailFromEnd", false)
      .configure("maxLinesPrefetch", 50)
      .configure("batchSize", 25)
      .configure("maxWaitTime", 100)
      .maxBatchSize(100)
      .outputLanes(ImmutableSet.of("lane"))
      .sourceOffset(null)
      .build()
      .run();

    Assert.assertFalse(result.get("lane").isEmpty());
    Assert.assertEquals("FIRST", result.get("lane").get(0).get().getValue());
  }

}
