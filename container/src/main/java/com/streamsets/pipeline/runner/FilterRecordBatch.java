/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.runner;

import com.google.common.collect.AbstractIterator;
import com.streamsets.pipeline.api.Batch;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.container.ErrorMessage;
import com.streamsets.pipeline.container.Utils;

import java.util.Iterator;

public class FilterRecordBatch implements Batch {
  private final Batch batch;
  private final Predicate predicate;
  private final Sink filteredOutRecordsSink;

  public interface Predicate {

    public boolean evaluate(Record record);

    public ErrorMessage getRejectedMessage();

  }

  public interface Sink {

    public void add(Record record, ErrorMessage message);

  }

  public FilterRecordBatch(Batch batch, Predicate predicate, Sink filteredOutRecordsSink) {
    this.batch = batch;
    this.predicate = predicate;
    this.filteredOutRecordsSink = filteredOutRecordsSink;
  }

  @Override
  public String getSourceOffset() {
    return batch.getSourceOffset();
  }

  @Override
  public Iterator<Record> getRecords() {
    return new RecordIterator(batch.getRecords());
  }

  private class RecordIterator extends AbstractIterator<Record> {
    private Iterator<Record> iterator;

    public RecordIterator(Iterator<Record> iterator) {
      this.iterator = iterator;
    }

    @Override
    protected Record computeNext() {
      Record next = null;
      while (next == null && iterator.hasNext()) {
        Record record = iterator.next();
        if (predicate.evaluate(record)) {
          next = record;
        } else {
          filteredOutRecordsSink.add(record, predicate.getRejectedMessage());
        }
      }
      if (next == null && !iterator.hasNext()) {
        endOfData();
      }
      return next;
    }
  }

  @Override
  public String toString() {
    return Utils.format("FilterRecordBatch[batch='{}' predicate='{}']", batch, predicate);
  }
}
