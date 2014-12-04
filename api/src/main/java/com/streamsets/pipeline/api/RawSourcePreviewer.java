/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.api;

import java.io.Reader;
import java.util.Map;

public interface RawSourcePreviewer {

  Reader preview(int maxLength);

  String getMimeType();

  void setMimeType(String mimeType);

}
