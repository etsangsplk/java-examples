package io.opentracing.contrib.examples.late_span_finish;

import static io.opentracing.contrib.examples.TestUtils.sleep;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import io.opentracing.ActiveSpan;
import io.opentracing.Span;
import io.opentracing.mock.MockSpan;
import io.opentracing.mock.MockTracer;
import io.opentracing.mock.MockTracer.Propagator;
import io.opentracing.util.ThreadLocalActiveSpanSource;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import org.junit.Test;

public class TestLateSpanFinish {

  private final MockTracer tracer = new MockTracer(new ThreadLocalActiveSpanSource(),
      Propagator.TEXT_MAP);
  private final ExecutorService executor = Executors.newCachedThreadPool();

  @Test
  public void test() throws Exception {
    /* Create a Span manually and use it as parent of a pair of subtasks */
    Span parentSpan = tracer.buildSpan("parent").startManual();
    submitTasks(parentSpan);

    /* Wait for the threadpool to be done first, instead of polling/waiting */
    executor.shutdown();
    executor.awaitTermination(15, TimeUnit.SECONDS);

    /* Late-finish the parent Span now */
    parentSpan.finish();

    List<MockSpan> spans = tracer.finishedSpans();
    assertEquals(3, spans.size());
    assertEquals("task1", spans.get(0).operationName());
    assertEquals("task2", spans.get(1).operationName());
    assertEquals("parent", spans.get(2).operationName());

    for (int i = 0; i < 2; i++) {
      assertEquals(true, spans.get(2).finishMicros() >= spans.get(i).finishMicros());
      assertEquals(spans.get(2).context().traceId(), spans.get(i).context().traceId());
      assertEquals(spans.get(2).context().spanId(), spans.get(i).parentId());
    }

    assertNull(tracer.activeSpan());
  }

  /* Fire away a few subtasks, passing a parent Span whose lifetime
   * is not tied at-all to the children */
  private void submitTasks(final Span parentSpan) {

    executor.submit(new Runnable() {
      @Override
      public void run() {
        // Alternative to calling makeActive() is to pass it manually to asChildOf() for each created Span.
        try (ActiveSpan span = tracer.makeActive(parentSpan)) {
          try (ActiveSpan childSpan1 = tracer.buildSpan("task1").startActive()) {
            sleep(55);
          }
          span.capture(); // Workaround, prevent parentSpan from being finished here.
        }
      }
    });

    executor.submit(new Runnable() {
      @Override
      public void run() {
        try (ActiveSpan span = tracer.makeActive(parentSpan)) {
          try (ActiveSpan childSpan1 = tracer.buildSpan("task2").startActive()) {
            sleep(85);
          }
          span.capture(); // Workaround, prevent parentSpan from being finished here.
        }
      }
    });
  }
}
