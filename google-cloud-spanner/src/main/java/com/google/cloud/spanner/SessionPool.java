/*
 * Copyright 2017 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.spanner;

import static com.google.cloud.spanner.MetricRegistryConstants.COUNT;
import static com.google.cloud.spanner.MetricRegistryConstants.GET_SESSION_TIMEOUTS;
import static com.google.cloud.spanner.MetricRegistryConstants.IN_USE_SESSIONS;
import static com.google.cloud.spanner.MetricRegistryConstants.IN_USE_SESSIONS_DESCRIPTION;
import static com.google.cloud.spanner.MetricRegistryConstants.MAX_ALLOWED_SESSIONS;
import static com.google.cloud.spanner.MetricRegistryConstants.MAX_ALLOWED_SESSIONS_DESCRIPTION;
import static com.google.cloud.spanner.MetricRegistryConstants.MAX_IN_USE_SESSIONS;
import static com.google.cloud.spanner.MetricRegistryConstants.MAX_IN_USE_SESSIONS_DESCRIPTION;
import static com.google.cloud.spanner.MetricRegistryConstants.NUM_ACQUIRED_SESSIONS;
import static com.google.cloud.spanner.MetricRegistryConstants.NUM_ACQUIRED_SESSIONS_DESCRIPTION;
import static com.google.cloud.spanner.MetricRegistryConstants.NUM_RELEASED_SESSIONS;
import static com.google.cloud.spanner.MetricRegistryConstants.NUM_RELEASED_SESSIONS_DESCRIPTION;
import static com.google.cloud.spanner.MetricRegistryConstants.SESSIONS_TIMEOUTS_DESCRIPTION;
import static com.google.cloud.spanner.MetricRegistryConstants.SPANNER_DEFAULT_LABEL_VALUES;
import static com.google.cloud.spanner.MetricRegistryConstants.SPANNER_LABEL_KEYS;
import static com.google.cloud.spanner.SpannerExceptionFactory.newSpannerException;

import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutures;
import com.google.cloud.Timestamp;
import com.google.cloud.grpc.GrpcTransportOptions;
import com.google.cloud.grpc.GrpcTransportOptions.ExecutorFactory;
import com.google.cloud.spanner.Options.QueryOption;
import com.google.cloud.spanner.Options.ReadOption;
import com.google.cloud.spanner.SessionClient.SessionConsumer;
import com.google.cloud.spanner.SpannerException.ResourceNotFoundException;
import com.google.cloud.spanner.SpannerImpl.ClosedException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.protobuf.Empty;
import io.opencensus.common.Scope;
import io.opencensus.common.ToLongFunction;
import io.opencensus.metrics.DerivedLongCumulative;
import io.opencensus.metrics.DerivedLongGauge;
import io.opencensus.metrics.LabelValue;
import io.opencensus.metrics.MetricOptions;
import io.opencensus.metrics.MetricRegistry;
import io.opencensus.metrics.Metrics;
import io.opencensus.trace.Annotation;
import io.opencensus.trace.AttributeValue;
import io.opencensus.trace.Span;
import io.opencensus.trace.Status;
import io.opencensus.trace.Tracer;
import io.opencensus.trace.Tracing;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import org.threeten.bp.Duration;
import org.threeten.bp.Instant;

/**
 * Maintains a pool of sessions some of which might be prepared for write by invoking
 * BeginTransaction rpc. It maintains two queues of sessions(read and write prepared) and two queues
 * of waiters who are waiting for a session to become available. This class itself is thread safe
 * and is meant to be used concurrently across multiple threads.
 */
final class SessionPool {

  private static final Logger logger = Logger.getLogger(SessionPool.class.getName());
  private static final Tracer tracer = Tracing.getTracer();
  static final String WAIT_FOR_SESSION = "SessionPool.WaitForSession";
  static final ImmutableSet<ErrorCode> SHOULD_STOP_PREPARE_SESSIONS_ERROR_CODES =
      ImmutableSet.of(
          ErrorCode.UNKNOWN,
          ErrorCode.INVALID_ARGUMENT,
          ErrorCode.PERMISSION_DENIED,
          ErrorCode.UNAUTHENTICATED,
          ErrorCode.RESOURCE_EXHAUSTED,
          ErrorCode.FAILED_PRECONDITION,
          ErrorCode.OUT_OF_RANGE,
          ErrorCode.UNIMPLEMENTED,
          ErrorCode.INTERNAL);

  /**
   * Wrapper around current time so that we can fake it in tests. TODO(user): Replace with Java 8
   * Clock.
   */
  static class Clock {
    Instant instant() {
      return Instant.now();
    }
  }

  /**
   * Wrapper around {@code ReadContext} that releases the session to the pool once the call is
   * finished, if it is a single use context.
   */
  private static class AutoClosingReadContext<T extends ReadContext> implements ReadContext {
    private final Function<PooledSession, T> readContextDelegateSupplier;
    private T readContextDelegate;
    private final SessionPool sessionPool;
    private PooledSession session;
    private final boolean isSingleUse;
    private boolean closed;
    private boolean sessionUsedForQuery = false;

    private AutoClosingReadContext(
        Function<PooledSession, T> delegateSupplier,
        SessionPool sessionPool,
        PooledSession session,
        boolean isSingleUse) {
      this.readContextDelegateSupplier = delegateSupplier;
      this.sessionPool = sessionPool;
      this.session = session;
      this.isSingleUse = isSingleUse;
      while (true) {
        try {
          this.readContextDelegate = readContextDelegateSupplier.apply(this.session);
          break;
        } catch (SessionNotFoundException e) {
          replaceSessionIfPossible(e);
        }
      }
    }

    T getReadContextDelegate() {
      return readContextDelegate;
    }

    private ResultSet wrap(final Supplier<ResultSet> resultSetSupplier) {
      ResultSet res;
      while (true) {
        try {
          res = resultSetSupplier.get();
          break;
        } catch (SessionNotFoundException e) {
          replaceSessionIfPossible(e);
        }
      }
      return new ForwardingResultSet(res) {
        private boolean beforeFirst = true;

        @Override
        public boolean next() throws SpannerException {
          while (true) {
            try {
              return internalNext();
            } catch (SessionNotFoundException e) {
              replaceSessionIfPossible(e);
              replaceDelegate(resultSetSupplier.get());
            }
          }
        }

        private boolean internalNext() {
          try {
            boolean ret = super.next();
            if (beforeFirst) {
              session.markUsed();
              beforeFirst = false;
              sessionUsedForQuery = true;
            }
            if (!ret && isSingleUse) {
              close();
            }
            return ret;
          } catch (SessionNotFoundException e) {
            throw e;
          } catch (SpannerException e) {
            if (!closed && isSingleUse) {
              session.lastException = e;
              AutoClosingReadContext.this.close();
            }
            throw e;
          }
        }

        @Override
        public void close() {
          super.close();
          if (isSingleUse) {
            AutoClosingReadContext.this.close();
          }
        }
      };
    }

    private void replaceSessionIfPossible(SessionNotFoundException e) {
      if (isSingleUse || !sessionUsedForQuery) {
        // This class is only used by read-only transactions, so we know that we only need a
        // read-only session.
        session = sessionPool.replaceReadSession(e, session);
        readContextDelegate = readContextDelegateSupplier.apply(session);
      } else {
        throw e;
      }
    }

    @Override
    public ResultSet read(
        final String table,
        final KeySet keys,
        final Iterable<String> columns,
        final ReadOption... options) {
      return wrap(
          new Supplier<ResultSet>() {
            @Override
            public ResultSet get() {
              return readContextDelegate.read(table, keys, columns, options);
            }
          });
    }

    @Override
    public ResultSet readUsingIndex(
        final String table,
        final String index,
        final KeySet keys,
        final Iterable<String> columns,
        final ReadOption... options) {
      return wrap(
          new Supplier<ResultSet>() {
            @Override
            public ResultSet get() {
              return readContextDelegate.readUsingIndex(table, index, keys, columns, options);
            }
          });
    }

    @Override
    @Nullable
    public Struct readRow(String table, Key key, Iterable<String> columns) {
      try {
        while (true) {
          try {
            session.markUsed();
            return readContextDelegate.readRow(table, key, columns);
          } catch (SessionNotFoundException e) {
            replaceSessionIfPossible(e);
          }
        }
      } finally {
        sessionUsedForQuery = true;
        if (isSingleUse) {
          close();
        }
      }
    }

    @Override
    @Nullable
    public Struct readRowUsingIndex(String table, String index, Key key, Iterable<String> columns) {
      try {
        while (true) {
          try {
            session.markUsed();
            return readContextDelegate.readRowUsingIndex(table, index, key, columns);
          } catch (SessionNotFoundException e) {
            replaceSessionIfPossible(e);
          }
        }
      } finally {
        sessionUsedForQuery = true;
        if (isSingleUse) {
          close();
        }
      }
    }

    @Override
    public ResultSet executeQuery(final Statement statement, final QueryOption... options) {
      return wrap(
          new Supplier<ResultSet>() {
            @Override
            public ResultSet get() {
              return readContextDelegate.executeQuery(statement, options);
            }
          });
    }

    @Override
    public ResultSet analyzeQuery(final Statement statement, final QueryAnalyzeMode queryMode) {
      return wrap(
          new Supplier<ResultSet>() {
            @Override
            public ResultSet get() {
              return readContextDelegate.analyzeQuery(statement, queryMode);
            }
          });
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      readContextDelegate.close();
      session.close();
    }
  }

  private static class AutoClosingReadTransaction
      extends AutoClosingReadContext<ReadOnlyTransaction> implements ReadOnlyTransaction {

    AutoClosingReadTransaction(
        Function<PooledSession, ReadOnlyTransaction> txnSupplier,
        SessionPool sessionPool,
        PooledSession session,
        boolean isSingleUse) {
      super(txnSupplier, sessionPool, session, isSingleUse);
    }

    @Override
    public Timestamp getReadTimestamp() {
      return getReadContextDelegate().getReadTimestamp();
    }
  }

  private static class AutoClosingTransactionManager implements TransactionManager {
    private class SessionPoolResultSet extends ForwardingResultSet {
      private SessionPoolResultSet(ResultSet delegate) {
        super(delegate);
      }

      @Override
      public boolean next() {
        try {
          return super.next();
        } catch (SessionNotFoundException e) {
          throw handleSessionNotFound(e);
        }
      }
    }

    /**
     * {@link TransactionContext} that is used in combination with an {@link
     * AutoClosingTransactionManager}. This {@link TransactionContext} handles {@link
     * SessionNotFoundException}s by replacing the underlying session with a fresh one, and then
     * throws an {@link AbortedException} to trigger the retry-loop that has been created by the
     * caller.
     */
    private class SessionPoolTransactionContext implements TransactionContext {
      private final TransactionContext delegate;

      private SessionPoolTransactionContext(TransactionContext delegate) {
        this.delegate = delegate;
      }

      @Override
      public ResultSet read(
          String table, KeySet keys, Iterable<String> columns, ReadOption... options) {
        return new SessionPoolResultSet(delegate.read(table, keys, columns, options));
      }

      @Override
      public ResultSet readUsingIndex(
          String table,
          String index,
          KeySet keys,
          Iterable<String> columns,
          ReadOption... options) {
        return new SessionPoolResultSet(
            delegate.readUsingIndex(table, index, keys, columns, options));
      }

      @Override
      public Struct readRow(String table, Key key, Iterable<String> columns) {
        try {
          return delegate.readRow(table, key, columns);
        } catch (SessionNotFoundException e) {
          throw handleSessionNotFound(e);
        }
      }

      @Override
      public void buffer(Mutation mutation) {
        delegate.buffer(mutation);
      }

      @Override
      public Struct readRowUsingIndex(
          String table, String index, Key key, Iterable<String> columns) {
        try {
          return delegate.readRowUsingIndex(table, index, key, columns);
        } catch (SessionNotFoundException e) {
          throw handleSessionNotFound(e);
        }
      }

      @Override
      public void buffer(Iterable<Mutation> mutations) {
        delegate.buffer(mutations);
      }

      @Override
      public long executeUpdate(Statement statement) {
        try {
          return delegate.executeUpdate(statement);
        } catch (SessionNotFoundException e) {
          throw handleSessionNotFound(e);
        }
      }

      @Override
      public long[] batchUpdate(Iterable<Statement> statements) {
        try {
          return delegate.batchUpdate(statements);
        } catch (SessionNotFoundException e) {
          throw handleSessionNotFound(e);
        }
      }

      @Override
      public ResultSet executeQuery(Statement statement, QueryOption... options) {
        return new SessionPoolResultSet(delegate.executeQuery(statement, options));
      }

      @Override
      public ResultSet analyzeQuery(Statement statement, QueryAnalyzeMode queryMode) {
        return new SessionPoolResultSet(delegate.analyzeQuery(statement, queryMode));
      }

      @Override
      public void close() {
        delegate.close();
      }
    }

    private TransactionManager delegate;
    private final SessionPool sessionPool;
    private PooledSession session;
    private boolean closed;
    private boolean restartedAfterSessionNotFound;

    AutoClosingTransactionManager(SessionPool sessionPool, PooledSession session) {
      this.sessionPool = sessionPool;
      this.session = session;
      this.delegate = session.delegate.transactionManager();
    }

    @Override
    public TransactionContext begin() {
      while (true) {
        try {
          return internalBegin();
        } catch (SessionNotFoundException e) {
          session = sessionPool.replaceReadWriteSession(e, session);
          delegate = session.delegate.transactionManager();
        }
      }
    }

    private TransactionContext internalBegin() {
      TransactionContext res = new SessionPoolTransactionContext(delegate.begin());
      session.markUsed();
      return res;
    }

    private SpannerException handleSessionNotFound(SessionNotFoundException e) {
      session = sessionPool.replaceReadWriteSession(e, session);
      delegate = session.delegate.transactionManager();
      restartedAfterSessionNotFound = true;
      return SpannerExceptionFactory.newSpannerException(ErrorCode.ABORTED, e.getMessage(), e);
    }

    @Override
    public void commit() {
      try {
        delegate.commit();
      } catch (SessionNotFoundException e) {
        throw handleSessionNotFound(e);
      } finally {
        if (getState() != TransactionState.ABORTED) {
          close();
        }
      }
    }

    @Override
    public void rollback() {
      try {
        delegate.rollback();
      } finally {
        close();
      }
    }

    @Override
    public TransactionContext resetForRetry() {
      while (true) {
        try {
          if (restartedAfterSessionNotFound) {
            TransactionContext res = new SessionPoolTransactionContext(delegate.begin());
            restartedAfterSessionNotFound = false;
            return res;
          } else {
            return new SessionPoolTransactionContext(delegate.resetForRetry());
          }
        } catch (SessionNotFoundException e) {
          session = sessionPool.replaceReadWriteSession(e, session);
          delegate = session.delegate.transactionManager();
          restartedAfterSessionNotFound = true;
        }
      }
    }

    @Override
    public Timestamp getCommitTimestamp() {
      return delegate.getCommitTimestamp();
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      try {
        delegate.close();
      } finally {
        session.close();
      }
    }

    @Override
    public TransactionState getState() {
      if (restartedAfterSessionNotFound) {
        return TransactionState.ABORTED;
      } else {
        return delegate.getState();
      }
    }
  }

  /**
   * {@link TransactionRunner} that automatically handles {@link SessionNotFoundException}s by
   * replacing the underlying read/write session and then restarts the transaction.
   */
  private static final class SessionPoolTransactionRunner implements TransactionRunner {
    private final SessionPool sessionPool;
    private PooledSession session;
    private TransactionRunner runner;

    private SessionPoolTransactionRunner(SessionPool sessionPool, PooledSession session) {
      this.sessionPool = sessionPool;
      this.session = session;
      this.runner = session.delegate.readWriteTransaction();
    }

    @Override
    @Nullable
    public <T> T run(TransactionCallable<T> callable) {
      try {
        T result;
        while (true) {
          try {
            result = runner.run(callable);
            break;
          } catch (SessionNotFoundException e) {
            session = sessionPool.replaceReadWriteSession(e, session);
            runner = session.delegate.readWriteTransaction();
          }
        }
        session.markUsed();
        return result;
      } catch (SpannerException e) {
        throw session.lastException = e;
      } finally {
        session.close();
      }
    }

    @Override
    public Timestamp getCommitTimestamp() {
      return runner.getCommitTimestamp();
    }

    @Override
    public TransactionRunner allowNestedTransaction() {
      runner.allowNestedTransaction();
      return this;
    }
  }

  // Exception class used just to track the stack trace at the point when a session was handed out
  // from the pool.
  private final class LeakedSessionException extends RuntimeException {
    private static final long serialVersionUID = 1451131180314064914L;

    private LeakedSessionException() {
      super("Session was checked out from the pool at " + clock.instant());
    }
  }

  private enum SessionState {
    AVAILABLE,
    BUSY,
    CLOSING,
  }

  final class PooledSession implements Session {
    @VisibleForTesting SessionImpl delegate;
    private volatile Instant lastUseTime;
    private volatile SpannerException lastException;
    private volatile LeakedSessionException leakedException;
    private volatile boolean allowReplacing = true;

    @GuardedBy("lock")
    private SessionState state;

    private PooledSession(SessionImpl delegate) {
      this.delegate = delegate;
      this.state = SessionState.AVAILABLE;
      this.lastUseTime = clock.instant();
    }

    @VisibleForTesting
    void setAllowReplacing(boolean allowReplacing) {
      this.allowReplacing = allowReplacing;
    }

    @VisibleForTesting
    void clearLeakedException() {
      this.leakedException = null;
    }

    private void markBusy() {
      this.state = SessionState.BUSY;
      this.leakedException = new LeakedSessionException();
    }

    private void markClosing() {
      this.state = SessionState.CLOSING;
    }

    @Override
    public Timestamp write(Iterable<Mutation> mutations) throws SpannerException {
      try {
        markUsed();
        return delegate.write(mutations);
      } catch (SpannerException e) {
        throw lastException = e;
      } finally {
        close();
      }
    }

    @Override
    public long executePartitionedUpdate(Statement stmt) throws SpannerException {
      try {
        markUsed();
        return delegate.executePartitionedUpdate(stmt);
      } catch (SpannerException e) {
        throw lastException = e;
      } finally {
        close();
      }
    }

    @Override
    public Timestamp writeAtLeastOnce(Iterable<Mutation> mutations) throws SpannerException {
      try {
        markUsed();
        return delegate.writeAtLeastOnce(mutations);
      } catch (SpannerException e) {
        throw lastException = e;
      } finally {
        close();
      }
    }

    @Override
    public ReadContext singleUse() {
      try {
        return new AutoClosingReadContext<>(
            new Function<PooledSession, ReadContext>() {
              @Override
              public ReadContext apply(PooledSession session) {
                return session.delegate.singleUse();
              }
            },
            SessionPool.this,
            this,
            true);
      } catch (Exception e) {
        close();
        throw e;
      }
    }

    @Override
    public ReadContext singleUse(final TimestampBound bound) {
      try {
        return new AutoClosingReadContext<>(
            new Function<PooledSession, ReadContext>() {
              @Override
              public ReadContext apply(PooledSession session) {
                return session.delegate.singleUse(bound);
              }
            },
            SessionPool.this,
            this,
            true);
      } catch (Exception e) {
        close();
        throw e;
      }
    }

    @Override
    public ReadOnlyTransaction singleUseReadOnlyTransaction() {
      return internalReadOnlyTransaction(
          new Function<PooledSession, ReadOnlyTransaction>() {
            @Override
            public ReadOnlyTransaction apply(PooledSession session) {
              return session.delegate.singleUseReadOnlyTransaction();
            }
          },
          true);
    }

    @Override
    public ReadOnlyTransaction singleUseReadOnlyTransaction(final TimestampBound bound) {
      return internalReadOnlyTransaction(
          new Function<PooledSession, ReadOnlyTransaction>() {
            @Override
            public ReadOnlyTransaction apply(PooledSession session) {
              return session.delegate.singleUseReadOnlyTransaction(bound);
            }
          },
          true);
    }

    @Override
    public ReadOnlyTransaction readOnlyTransaction() {
      return internalReadOnlyTransaction(
          new Function<PooledSession, ReadOnlyTransaction>() {
            @Override
            public ReadOnlyTransaction apply(PooledSession session) {
              return session.delegate.readOnlyTransaction();
            }
          },
          false);
    }

    @Override
    public ReadOnlyTransaction readOnlyTransaction(final TimestampBound bound) {
      return internalReadOnlyTransaction(
          new Function<PooledSession, ReadOnlyTransaction>() {
            @Override
            public ReadOnlyTransaction apply(PooledSession session) {
              return session.delegate.readOnlyTransaction(bound);
            }
          },
          false);
    }

    private ReadOnlyTransaction internalReadOnlyTransaction(
        Function<PooledSession, ReadOnlyTransaction> transactionSupplier, boolean isSingleUse) {
      try {
        return new AutoClosingReadTransaction(
            transactionSupplier, SessionPool.this, this, isSingleUse);
      } catch (Exception e) {
        close();
        throw e;
      }
    }

    @Override
    public TransactionRunner readWriteTransaction() {
      return new SessionPoolTransactionRunner(SessionPool.this, this);
    }

    @Override
    public ApiFuture<Empty> asyncClose() {
      close();
      return ApiFutures.immediateFuture(Empty.getDefaultInstance());
    }

    @Override
    public void close() {
      synchronized (lock) {
        numSessionsInUse--;
        numSessionsReleased++;
      }
      leakedException = null;
      if (lastException != null && isSessionNotFound(lastException)) {
        invalidateSession(this);
      } else {
        if (lastException != null && isDatabaseOrInstanceNotFound(lastException)) {
          // Mark this session pool as no longer valid and then release the session into the pool as
          // there is nothing we can do with it anyways.
          synchronized (lock) {
            SessionPool.this.resourceNotFoundException =
                MoreObjects.firstNonNull(
                    SessionPool.this.resourceNotFoundException,
                    (ResourceNotFoundException) lastException);
          }
        }
        lastException = null;
        if (state != SessionState.CLOSING) {
          state = SessionState.AVAILABLE;
        }
        releaseSession(this, Position.FIRST);
      }
    }

    @Override
    public String getName() {
      return delegate.getName();
    }

    @Override
    public void prepareReadWriteTransaction() {
      markUsed();
      delegate.prepareReadWriteTransaction();
    }

    private void keepAlive() {
      markUsed();
      try (ResultSet resultSet =
          delegate
              .singleUse(TimestampBound.ofMaxStaleness(60, TimeUnit.SECONDS))
              .executeQuery(Statement.newBuilder("SELECT 1").build())) {
        resultSet.next();
      }
    }

    void markUsed() {
      lastUseTime = clock.instant();
    }

    @Override
    public TransactionManager transactionManager() {
      return new AutoClosingTransactionManager(SessionPool.this, this);
    }
  }

  private static final class SessionOrError {
    private final PooledSession session;
    private final SpannerException e;

    SessionOrError(PooledSession session) {
      this.session = session;
      this.e = null;
    }

    SessionOrError(SpannerException e) {
      this.session = null;
      this.e = e;
    }
  }

  private final class Waiter {
    private static final long MAX_SESSION_WAIT_TIMEOUT = 240_000L;
    private final SynchronousQueue<SessionOrError> waiter = new SynchronousQueue<>();

    private void put(PooledSession session) {
      Uninterruptibles.putUninterruptibly(waiter, new SessionOrError(session));
    }

    private void put(SpannerException e) {
      Uninterruptibles.putUninterruptibly(waiter, new SessionOrError(e));
    }

    private PooledSession take() throws SpannerException {
      long currentTimeout = options.getInitialWaitForSessionTimeoutMillis();
      while (true) {
        Span span = tracer.spanBuilder(WAIT_FOR_SESSION).startSpan();
        try (Scope waitScope = tracer.withSpan(span)) {
          SessionOrError s = pollUninterruptiblyWithTimeout(currentTimeout);
          if (s == null) {
            // Set the status to DEADLINE_EXCEEDED and retry.
            numWaiterTimeouts.incrementAndGet();
            tracer.getCurrentSpan().setStatus(Status.DEADLINE_EXCEEDED);
            currentTimeout = Math.min(currentTimeout * 2, MAX_SESSION_WAIT_TIMEOUT);
          } else {
            if (s.e != null) {
              throw newSpannerException(s.e);
            }
            return s.session;
          }
        } catch (Exception e) {
          TraceUtil.setWithFailure(span, e);
          throw e;
        } finally {
          span.end(TraceUtil.END_SPAN_OPTIONS);
        }
      }
    }

    private SessionOrError pollUninterruptiblyWithTimeout(long timeoutMillis) {
      boolean interrupted = false;
      try {
        while (true) {
          try {
            return waiter.poll(timeoutMillis, TimeUnit.MILLISECONDS);
          } catch (InterruptedException e) {
            interrupted = true;
          }
        }
      } finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  /**
   * Background task to maintain the pool. Tasks:
   *
   * <ul>
   *   <li>Removes idle sessions from the pool. Sessions that go above MinSessions that have not
   *       been used for the last 55 minutes will be removed from the pool. These will automatically
   *       be garbage collected by the backend.
   *   <li>Keeps alive sessions that have not been used for a user configured time in order to keep
   *       MinSessions sessions alive in the pool at any time. The keep-alive traffic is smeared out
   *       over a window of 10 minutes to avoid bursty traffic.
   * </ul>
   */
  final class PoolMaintainer {
    // Length of the window in millis over which we keep track of maximum number of concurrent
    // sessions in use.
    private final Duration windowLength = Duration.ofMillis(TimeUnit.MINUTES.toMillis(10));
    // Frequency of the timer loop.
    @VisibleForTesting final long loopFrequency = options.getLoopFrequency();
    // Number of loop iterations in which we need to to close all the sessions waiting for closure.
    @VisibleForTesting final long numClosureCycles = windowLength.toMillis() / loopFrequency;
    private final Duration keepAliveMilis =
        Duration.ofMillis(TimeUnit.MINUTES.toMillis(options.getKeepAliveIntervalMinutes()));
    // Number of loop iterations in which we need to keep alive all the sessions
    @VisibleForTesting final long numKeepAliveCycles = keepAliveMilis.toMillis() / loopFrequency;

    Instant lastResetTime = Instant.ofEpochMilli(0);
    int numSessionsToClose = 0;
    int sessionsToClosePerLoop = 0;

    @GuardedBy("lock")
    ScheduledFuture<?> scheduledFuture;

    @GuardedBy("lock")
    boolean running;

    void init() {
      // Scheduled pool maintenance worker.
      synchronized (lock) {
        scheduledFuture =
            executor.scheduleAtFixedRate(
                new Runnable() {
                  @Override
                  public void run() {
                    maintainPool();
                  }
                },
                loopFrequency,
                loopFrequency,
                TimeUnit.MILLISECONDS);
      }
    }

    void close() {
      synchronized (lock) {
        scheduledFuture.cancel(false);
        if (!running) {
          decrementPendingClosures(1);
        }
      }
    }

    // Does various pool maintenance activities.
    void maintainPool() {
      synchronized (lock) {
        if (isClosed()) {
          return;
        }
        running = true;
      }
      Instant currTime = clock.instant();
      removeIdleSessions(currTime);
      // Now go over all the remaining sessions and see if they need to be kept alive explicitly.
      keepAliveSessions(currTime);
      replenishPool();
      synchronized (lock) {
        running = false;
        if (isClosed()) {
          decrementPendingClosures(1);
        }
      }
    }

    private void removeIdleSessions(Instant currTime) {
      synchronized (lock) {
        // Determine the minimum last use time for a session to be deemed to still be alive. Remove
        // all sessions that have a lastUseTime before that time, unless it would cause us to go
        // below MinSessions. Prefer to remove read sessions above write-prepared sessions.
        Instant minLastUseTime = currTime.minus(options.getRemoveInactiveSessionAfter());
        for (Iterator<PooledSession> iterator :
            Arrays.asList(
                readSessions.descendingIterator(), writePreparedSessions.descendingIterator())) {
          while (iterator.hasNext()) {
            PooledSession session = iterator.next();
            if (session.lastUseTime.isBefore(minLastUseTime)) {
              if (session.state != SessionState.CLOSING) {
                removeFromPool(session);
                iterator.remove();
              }
            }
          }
        }
      }
    }

    private void keepAliveSessions(Instant currTime) {
      long numSessionsToKeepAlive = 0;
      synchronized (lock) {
        if (numSessionsInUse >= (options.getMinSessions() + options.getMaxIdleSessions())) {
          // At least MinSessions are in use, so we don't have to ping any sessions.
          return;
        }
        // In each cycle only keep alive a subset of sessions to prevent burst of traffic.
        numSessionsToKeepAlive =
            (long)
                Math.ceil(
                    (double)
                            ((options.getMinSessions() + options.getMaxIdleSessions())
                                - numSessionsInUse)
                        / numKeepAliveCycles);
      }
      // Now go over all the remaining sessions and see if they need to be kept alive explicitly.
      Instant keepAliveThreshold = currTime.minus(keepAliveMilis);

      // Keep chugging till there is no session that needs to be kept alive.
      while (numSessionsToKeepAlive > 0) {
        PooledSession sessionToKeepAlive = null;
        synchronized (lock) {
          sessionToKeepAlive = findSessionToKeepAlive(readSessions, keepAliveThreshold, 0);
          if (sessionToKeepAlive == null) {
            sessionToKeepAlive =
                findSessionToKeepAlive(
                    writePreparedSessions, keepAliveThreshold, readSessions.size());
          }
        }
        if (sessionToKeepAlive == null) {
          break;
        }
        try {
          logger.log(Level.FINE, "Keeping alive session " + sessionToKeepAlive.getName());
          numSessionsToKeepAlive--;
          sessionToKeepAlive.keepAlive();
          releaseSession(sessionToKeepAlive, Position.FIRST);
        } catch (SpannerException e) {
          handleException(e, sessionToKeepAlive);
        }
      }
    }

    private void replenishPool() {
      synchronized (lock) {
        // If we have gone below min pool size, create that many sessions.
        int sessionCount = options.getMinSessions() - (totalSessions() + numSessionsBeingCreated);
        if (sessionCount > 0) {
          createSessions(getAllowedCreateSessions(sessionCount), false);
        }
      }
    }
  }

  private static enum Position {
    FIRST,
    RANDOM;
  }

  private final SessionPoolOptions options;
  private final SessionClient sessionClient;
  private final ScheduledExecutorService executor;
  private final ExecutorFactory<ScheduledExecutorService> executorFactory;
  private final ScheduledExecutorService prepareExecutor;
  private final int prepareThreadPoolSize;
  final PoolMaintainer poolMaintainer;
  private final Clock clock;
  private final Object lock = new Object();
  private final Random random = new Random();

  @GuardedBy("lock")
  private int pendingClosure;

  @GuardedBy("lock")
  private SettableFuture<Void> closureFuture;

  @GuardedBy("lock")
  private ClosedException closedException;

  @GuardedBy("lock")
  private ResourceNotFoundException resourceNotFoundException;

  @GuardedBy("lock")
  private boolean stopAutomaticPrepare;

  @GuardedBy("lock")
  private final LinkedList<PooledSession> readSessions = new LinkedList<>();

  @GuardedBy("lock")
  private final LinkedList<PooledSession> writePreparedSessions = new LinkedList<>();

  @GuardedBy("lock")
  private final Queue<Waiter> readWaiters = new LinkedList<>();

  @GuardedBy("lock")
  private final Queue<Waiter> readWriteWaiters = new LinkedList<>();

  @GuardedBy("lock")
  private int numSessionsBeingPrepared = 0;

  @GuardedBy("lock")
  private int numSessionsBeingCreated = 0;

  @GuardedBy("lock")
  private int numSessionsInUse = 0;

  @GuardedBy("lock")
  private int maxSessionsInUse = 0;

  @GuardedBy("lock")
  private long numSessionsAcquired = 0;

  @GuardedBy("lock")
  private long numSessionsReleased = 0;

  @GuardedBy("lock")
  private long numSessionsInProcessPrepared = 0;

  @GuardedBy("lock")
  private long numSessionsAsyncPrepared = 0;

  @GuardedBy("lock")
  private long numIdleSessionsRemoved = 0;

  private AtomicLong numWaiterTimeouts = new AtomicLong();

  @GuardedBy("lock")
  private final Set<PooledSession> allSessions = new HashSet<>();

  private final SessionConsumer sessionConsumer = new SessionConsumerImpl();

  @VisibleForTesting Function<PooledSession, Void> idleSessionRemovedListener;

  /**
   * Create a session pool with the given options and for the given database. It will also start
   * eagerly creating sessions if {@link SessionPoolOptions#getMinSessions()} is greater than 0.
   * Return pool is immediately ready for use, though getting a session might block for sessions to
   * be created.
   */
  static SessionPool createPool(
      SpannerOptions spannerOptions, SessionClient sessionClient, List<LabelValue> labelValues) {
    return createPool(
        spannerOptions.getSessionPoolOptions(),
        ((GrpcTransportOptions) spannerOptions.getTransportOptions()).getExecutorFactory(),
        sessionClient,
        new Clock(),
        Metrics.getMetricRegistry(),
        labelValues);
  }

  static SessionPool createPool(
      SessionPoolOptions poolOptions,
      ExecutorFactory<ScheduledExecutorService> executorFactory,
      SessionClient sessionClient) {
    return createPool(poolOptions, executorFactory, sessionClient, new Clock());
  }

  static SessionPool createPool(
      SessionPoolOptions poolOptions,
      ExecutorFactory<ScheduledExecutorService> executorFactory,
      SessionClient sessionClient,
      Clock clock) {
    return createPool(
        poolOptions,
        executorFactory,
        sessionClient,
        clock,
        Metrics.getMetricRegistry(),
        SPANNER_DEFAULT_LABEL_VALUES);
  }

  static SessionPool createPool(
      SessionPoolOptions poolOptions,
      ExecutorFactory<ScheduledExecutorService> executorFactory,
      SessionClient sessionClient,
      Clock clock,
      MetricRegistry metricRegistry,
      List<LabelValue> labelValues) {
    SessionPool pool =
        new SessionPool(
            poolOptions,
            executorFactory,
            executorFactory.get(),
            sessionClient,
            clock,
            metricRegistry,
            labelValues);
    pool.initPool();
    return pool;
  }

  private SessionPool(
      SessionPoolOptions options,
      ExecutorFactory<ScheduledExecutorService> executorFactory,
      ScheduledExecutorService executor,
      SessionClient sessionClient,
      Clock clock,
      MetricRegistry metricRegistry,
      List<LabelValue> labelValues) {
    this.options = options;
    this.executorFactory = executorFactory;
    this.executor = executor;
    if (executor instanceof ThreadPoolExecutor) {
      prepareThreadPoolSize = Math.max(((ThreadPoolExecutor) executor).getCorePoolSize(), 1);
    } else {
      prepareThreadPoolSize = 8;
    }
    this.prepareExecutor =
        Executors.newScheduledThreadPool(
            prepareThreadPoolSize,
            new ThreadFactoryBuilder()
                .setDaemon(true)
                .setNameFormat("session-pool-prepare-%d")
                .build());
    this.sessionClient = sessionClient;
    this.clock = clock;
    this.poolMaintainer = new PoolMaintainer();
    this.initMetricsCollection(metricRegistry, labelValues);
  }

  @VisibleForTesting
  long getNumberOfSessionsInProcessPrepared() {
    synchronized (lock) {
      return numSessionsInProcessPrepared;
    }
  }

  @VisibleForTesting
  long getNumberOfSessionsAsyncPrepared() {
    synchronized (lock) {
      return numSessionsAsyncPrepared;
    }
  }

  void removeFromPool(PooledSession session) {
    synchronized (lock) {
      if (isClosed()) {
        decrementPendingClosures(1);
        return;
      }
      session.markClosing();
      allSessions.remove(session);
      numIdleSessionsRemoved++;
      if (idleSessionRemovedListener != null) {
        idleSessionRemovedListener.apply(session);
      }
    }
  }

  long numIdleSessionsRemoved() {
    synchronized (lock) {
      return numIdleSessionsRemoved;
    }
  }

  @VisibleForTesting
  int getNumberOfAvailableWritePreparedSessions() {
    synchronized (lock) {
      return writePreparedSessions.size();
    }
  }

  @VisibleForTesting
  int getNumberOfSessionsInPool() {
    synchronized (lock) {
      return readSessions.size() + writePreparedSessions.size() + numSessionsBeingPrepared;
    }
  }

  @VisibleForTesting
  int getNumberOfSessionsBeingCreated() {
    synchronized (lock) {
      return numSessionsBeingCreated;
    }
  }

  @VisibleForTesting
  int getNumberOfSessionsBeingPrepared() {
    synchronized (lock) {
      return numSessionsBeingPrepared;
    }
  }

  @VisibleForTesting
  long getNumWaiterTimeouts() {
    return numWaiterTimeouts.get();
  }

  private void initPool() {
    synchronized (lock) {
      poolMaintainer.init();
      if (options.getMinSessions() > 0) {
        createSessions(options.getMinSessions(), true);
      }
    }
  }

  private boolean isClosed() {
    synchronized (lock) {
      return closureFuture != null;
    }
  }

  private void handleException(SpannerException e, PooledSession session) {
    if (isSessionNotFound(e)) {
      invalidateSession(session);
    } else {
      releaseSession(session, Position.FIRST);
    }
  }

  private boolean isSessionNotFound(SpannerException e) {
    return e.getErrorCode() == ErrorCode.NOT_FOUND && e.getMessage().contains("Session not found");
  }

  private boolean isDatabaseOrInstanceNotFound(SpannerException e) {
    return e instanceof DatabaseNotFoundException || e instanceof InstanceNotFoundException;
  }

  private boolean shouldStopPrepareSessions(SpannerException e) {
    return isDatabaseOrInstanceNotFound(e)
        || SHOULD_STOP_PREPARE_SESSIONS_ERROR_CODES.contains(e.getErrorCode());
  }

  private void invalidateSession(PooledSession session) {
    synchronized (lock) {
      if (isClosed()) {
        decrementPendingClosures(1);
        return;
      }
      allSessions.remove(session);
      // replenish the pool.
      createSessions(getAllowedCreateSessions(1), false);
    }
  }

  private PooledSession findSessionToKeepAlive(
      Queue<PooledSession> queue, Instant keepAliveThreshold, int numAlreadyChecked) {
    int numChecked = 0;
    Iterator<PooledSession> iterator = queue.iterator();
    while (iterator.hasNext()
        && (numChecked + numAlreadyChecked)
            < (options.getMinSessions() + options.getMaxIdleSessions() - numSessionsInUse)) {
      PooledSession session = iterator.next();
      if (session.lastUseTime.isBefore(keepAliveThreshold)) {
        iterator.remove();
        return session;
      }
      numChecked++;
    }
    return null;
  }

  /** @return true if this {@link SessionPool} is still valid. */
  boolean isValid() {
    synchronized (lock) {
      return closureFuture == null && resourceNotFoundException == null;
    }
  }

  /**
   * Returns a session to be used for read requests to spanner. It will block if a session is not
   * currently available. In case the pool is exhausted and {@link
   * SessionPoolOptions#isFailIfPoolExhausted()} has been set, it will throw an exception. Returned
   * session must be closed by calling {@link Session#close()}.
   *
   * <p>Implementation strategy:
   *
   * <ol>
   *   <li>If a read session is available, return that.
   *   <li>Otherwise if a writePreparedSession is available, return that.
   *   <li>Otherwise if a session can be created, fire a creation request.
   *   <li>Wait for a session to become available. Note that this can be unblocked either by a
   *       session being returned to the pool or a new session being created.
   * </ol>
   */
  PooledSession getReadSession() throws SpannerException {
    Span span = Tracing.getTracer().getCurrentSpan();
    span.addAnnotation("Acquiring session");
    Waiter waiter = null;
    PooledSession sess = null;
    synchronized (lock) {
      if (closureFuture != null) {
        span.addAnnotation("Pool has been closed");
        throw new IllegalStateException("Pool has been closed", closedException);
      }
      if (resourceNotFoundException != null) {
        span.addAnnotation("Database has been deleted");
        throw SpannerExceptionFactory.newSpannerException(
            ErrorCode.NOT_FOUND,
            String.format(
                "The session pool has been invalidated because a previous RPC returned 'Database not found': %s",
                resourceNotFoundException.getMessage()),
            resourceNotFoundException);
      }
      sess = readSessions.poll();
      if (sess == null) {
        sess = writePreparedSessions.poll();
        if (sess == null) {
          span.addAnnotation("No session available");
          maybeCreateSession();
          waiter = new Waiter();
          readWaiters.add(waiter);
        } else {
          span.addAnnotation("Acquired read write session");
        }
      } else {
        span.addAnnotation("Acquired read only session");
      }
    }
    if (waiter != null) {
      logger.log(
          Level.FINE,
          "No session available in the pool. Blocking for one to become available/created");
      span.addAnnotation("Waiting for read only session to be available");
      sess = waiter.take();
    }
    sess.markBusy();
    incrementNumSessionsInUse();
    span.addAnnotation(sessionAnnotation(sess));
    return sess;
  }

  /**
   * Returns a session which has been prepared for writes by invoking BeginTransaction rpc. It will
   * block if such a session is not currently available.In case the pool is exhausted and {@link
   * SessionPoolOptions#isFailIfPoolExhausted()} has been set, it will throw an exception. Returned
   * session must closed by invoking {@link Session#close()}.
   *
   * <p>Implementation strategy:
   *
   * <ol>
   *   <li>If a writePreparedSession is available, return that.
   *   <li>Otherwise if we have an extra session being prepared for write, wait for that.
   *   <li>Otherwise, if there is a read session available, start preparing that for write and wait.
   *   <li>Otherwise start creating a new session and wait.
   *   <li>Wait for write prepared session to become available. This can be unblocked either by the
   *       session create/prepare request we fired in above request or by a session being released
   *       to the pool which is then write prepared.
   * </ol>
   */
  PooledSession getReadWriteSession() {
    Span span = Tracing.getTracer().getCurrentSpan();
    span.addAnnotation("Acquiring read write session");
    PooledSession sess = null;
    // Loop to retry SessionNotFoundExceptions that might occur during in-process prepare of a
    // session.
    while (true) {
      Waiter waiter = null;
      boolean inProcessPrepare = stopAutomaticPrepare;
      synchronized (lock) {
        if (closureFuture != null) {
          span.addAnnotation("Pool has been closed");
          throw new IllegalStateException("Pool has been closed", closedException);
        }
        if (resourceNotFoundException != null) {
          span.addAnnotation("Database has been deleted");
          throw SpannerExceptionFactory.newSpannerException(
              ErrorCode.NOT_FOUND,
              String.format(
                  "The session pool has been invalidated because a previous RPC returned 'Database not found': %s",
                  resourceNotFoundException.getMessage()),
              resourceNotFoundException);
        }
        sess = writePreparedSessions.poll();
        if (sess == null) {
          if (!inProcessPrepare && numSessionsBeingPrepared <= prepareThreadPoolSize) {
            if (numSessionsBeingPrepared <= readWriteWaiters.size()) {
              PooledSession readSession = readSessions.poll();
              if (readSession != null) {
                span.addAnnotation(
                    "Acquired read only session. Preparing for read write transaction");
                prepareSession(readSession);
              } else {
                span.addAnnotation("No session available");
                maybeCreateSession();
              }
            }
          } else {
            inProcessPrepare = true;
            numSessionsInProcessPrepared++;
            PooledSession readSession = readSessions.poll();
            if (readSession != null) {
              // Create a read/write transaction in-process if there is already a queue for prepared
              // sessions. This is more efficient than doing it asynchronously, as it scales with
              // the number of user threads. The thread pool for asynchronously preparing sessions
              // is fixed.
              span.addAnnotation(
                  "Acquired read only session. Preparing in-process for read write transaction");
              sess = readSession;
            } else {
              span.addAnnotation("No session available");
              maybeCreateSession();
            }
          }
          if (sess == null) {
            waiter = new Waiter();
            if (inProcessPrepare) {
              // inProcessPrepare=true means that we have already determined that the queue for
              // preparing read/write sessions is larger than the number of threads in the prepare
              // thread pool, and that it's more efficient to do the prepare in-process. We will
              // therefore create a waiter for a read-only session, even though a read/write session
              // has been requested.
              readWaiters.add(waiter);
            } else {
              readWriteWaiters.add(waiter);
            }
          }
        } else {
          span.addAnnotation("Acquired read write session");
        }
      }
      if (waiter != null) {
        logger.log(
            Level.FINE,
            "No session available in the pool. Blocking for one to become available/created");
        span.addAnnotation("Waiting for read write session to be available");
        sess = waiter.take();
      }
      if (inProcessPrepare) {
        try {
          sess.prepareReadWriteTransaction();
          // Session prepare succeeded, restart automatic prepare if it had been stopped.
          synchronized (lock) {
            stopAutomaticPrepare = false;
          }
        } catch (Throwable t) {
          SpannerException e = newSpannerException(t);
          if (!isClosed()) {
            handlePrepareSessionFailure(e, sess, false);
          }
          sess = null;
          if (!isSessionNotFound(e)) {
            throw e;
          }
        }
      }
      if (sess != null) {
        break;
      }
    }
    sess.markBusy();
    incrementNumSessionsInUse();
    span.addAnnotation(sessionAnnotation(sess));
    return sess;
  }

  PooledSession replaceReadSession(SessionNotFoundException e, PooledSession session) {
    return replaceSession(e, session, false);
  }

  PooledSession replaceReadWriteSession(SessionNotFoundException e, PooledSession session) {
    return replaceSession(e, session, true);
  }

  private PooledSession replaceSession(
      SessionNotFoundException e, PooledSession session, boolean write) {
    if (!options.isFailIfSessionNotFound() && session.allowReplacing) {
      synchronized (lock) {
        numSessionsInUse--;
        numSessionsReleased++;
      }
      session.leakedException = null;
      invalidateSession(session);
      return write ? getReadWriteSession() : getReadSession();
    } else {
      throw e;
    }
  }

  private Annotation sessionAnnotation(Session session) {
    AttributeValue sessionId = AttributeValue.stringAttributeValue(session.getName());
    return Annotation.fromDescriptionAndAttributes(
        "Using Session", ImmutableMap.of("sessionId", sessionId));
  }

  private void incrementNumSessionsInUse() {
    synchronized (lock) {
      if (maxSessionsInUse < ++numSessionsInUse) {
        maxSessionsInUse = numSessionsInUse;
      }
      numSessionsAcquired++;
    }
  }

  private void maybeCreateSession() {
    Span span = Tracing.getTracer().getCurrentSpan();
    synchronized (lock) {
      if (numWaiters() >= numSessionsBeingCreated) {
        if (canCreateSession()) {
          span.addAnnotation("Creating sessions");
          createSessions(getAllowedCreateSessions(options.getIncStep()), false);
        } else if (options.isFailIfPoolExhausted()) {
          span.addAnnotation("Pool exhausted. Failing");
          // throw specific exception
          throw newSpannerException(
              ErrorCode.RESOURCE_EXHAUSTED,
              "No session available in the pool. Maximum number of sessions in the pool can be"
                  + " overridden by invoking SessionPoolOptions#Builder#setMaxSessions. Client can be made to block"
                  + " rather than fail by setting SessionPoolOptions#Builder#setBlockIfPoolExhausted.");
        }
      }
    }
  }
  /**
   * Releases a session back to the pool. This might cause one of the waiters to be unblocked.
   *
   * <p>Implementation note:
   *
   * <ol>
   *   <li>If there are no pending waiters, either add to the read sessions queue or start preparing
   *       for write depending on what fraction of sessions are already prepared for writes.
   *   <li>Otherwise either unblock a waiting reader or start preparing for a write. Exact strategy
   *       on which option we chose, in case there are both waiting readers and writers, is
   *       implemented in {@link #shouldUnblockReader}
   * </ol>
   */
  private void releaseSession(PooledSession session, Position position) {
    Preconditions.checkNotNull(session);
    synchronized (lock) {
      if (closureFuture != null) {
        return;
      }
      if (readWaiters.size() == 0 && numSessionsBeingPrepared >= readWriteWaiters.size()) {
        // No pending waiters
        if (shouldPrepareSession()) {
          prepareSession(session);
        } else {
          switch (position) {
            case RANDOM:
              if (!readSessions.isEmpty()) {
                int pos = random.nextInt(readSessions.size() + 1);
                readSessions.add(pos, session);
                break;
              }
              // fallthrough
            case FIRST:
            default:
              readSessions.addFirst(session);
          }
        }
      } else if (shouldUnblockReader()) {
        readWaiters.poll().put(session);
      } else {
        prepareSession(session);
      }
    }
  }

  private void handleCreateSessionsFailure(SpannerException e, int count) {
    synchronized (lock) {
      for (int i = 0; i < count; i++) {
        if (readWaiters.size() > 0) {
          readWaiters.poll().put(e);
        } else if (readWriteWaiters.size() > 0) {
          readWriteWaiters.poll().put(e);
        } else {
          break;
        }
      }
      if (isDatabaseOrInstanceNotFound(e)) {
        setResourceNotFoundException((ResourceNotFoundException) e);
      }
    }
  }

  private void handlePrepareSessionFailure(
      SpannerException e, PooledSession session, boolean informFirstWaiter) {
    synchronized (lock) {
      if (isSessionNotFound(e)) {
        invalidateSession(session);
      } else if (shouldStopPrepareSessions(e)) {
        // Database has been deleted or the user has no permission to write to this database, or
        // there is some other semi-permanent error. We should stop trying to prepare any
        // transactions. Also propagate the error to all waiters if the database or instance has
        // been deleted, as any further waiting is pointless.
        stopAutomaticPrepare = true;
        while (readWriteWaiters.size() > 0) {
          readWriteWaiters.poll().put(e);
        }
        while (readWaiters.size() > 0) {
          readWaiters.poll().put(e);
        }
        if (isDatabaseOrInstanceNotFound(e)) {
          // Remove the session from the pool.
          if (isClosed()) {
            decrementPendingClosures(1);
          }
          allSessions.remove(session);
          setResourceNotFoundException((ResourceNotFoundException) e);
        } else {
          releaseSession(session, Position.FIRST);
        }
      } else if (informFirstWaiter && readWriteWaiters.size() > 0) {
        releaseSession(session, Position.FIRST);
        readWriteWaiters.poll().put(e);
      } else {
        releaseSession(session, Position.FIRST);
      }
    }
  }

  void setResourceNotFoundException(ResourceNotFoundException e) {
    this.resourceNotFoundException = MoreObjects.firstNonNull(this.resourceNotFoundException, e);
  }

  private void decrementPendingClosures(int count) {
    pendingClosure -= count;
    if (pendingClosure == 0) {
      closureFuture.set(null);
    }
  }

  /**
   * Close all the sessions. Once this method is invoked {@link #getReadSession()} and {@link
   * #getReadWriteSession()} will start throwing {@code IllegalStateException}. The returned future
   * blocks till all the sessions created in this pool have been closed.
   */
  ListenableFuture<Void> closeAsync(ClosedException closedException) {
    ListenableFuture<Void> retFuture = null;
    synchronized (lock) {
      if (closureFuture != null) {
        throw new IllegalStateException("Close has already been invoked", this.closedException);
      }
      this.closedException = closedException;
      // Fail all pending waiters.
      Waiter waiter = readWaiters.poll();
      while (waiter != null) {
        waiter.put(newSpannerException(ErrorCode.INTERNAL, "Client has been closed"));
        waiter = readWaiters.poll();
      }
      waiter = readWriteWaiters.poll();
      while (waiter != null) {
        waiter.put(newSpannerException(ErrorCode.INTERNAL, "Client has been closed"));
        waiter = readWriteWaiters.poll();
      }
      closureFuture = SettableFuture.create();
      retFuture = closureFuture;
      pendingClosure =
          totalSessions()
              + numSessionsBeingCreated
              + 2 /* For pool maintenance thread + prepareExecutor */;

      poolMaintainer.close();
      readSessions.clear();
      writePreparedSessions.clear();
      prepareExecutor.shutdown();
      executor.submit(
          new Runnable() {
            @Override
            public void run() {
              try {
                prepareExecutor.awaitTermination(5L, TimeUnit.SECONDS);
              } catch (Throwable t) {
              }
              synchronized (lock) {
                decrementPendingClosures(1);
              }
            }
          });
      for (final PooledSession session : ImmutableList.copyOf(allSessions)) {
        if (session.leakedException != null) {
          logger.log(Level.WARNING, "Leaked session", session.leakedException);
        }
        if (session.state != SessionState.CLOSING) {
          closeSessionAsync(session);
        }
      }
    }
    retFuture.addListener(
        new Runnable() {
          @Override
          public void run() {
            executorFactory.release(executor);
          }
        },
        MoreExecutors.directExecutor());
    return retFuture;
  }

  private boolean shouldUnblockReader() {
    // This might not be the best strategy since a continuous burst of read requests can starve
    // a write request. Maybe maintain a timestamp in the queue and unblock according to that
    // or just flip a weighted coin.
    synchronized (lock) {
      int numWriteWaiters = readWriteWaiters.size() - numSessionsBeingPrepared;
      return readWaiters.size() > numWriteWaiters;
    }
  }

  private boolean shouldPrepareSession() {
    synchronized (lock) {
      if (stopAutomaticPrepare) {
        return false;
      }
      int preparedSessions = writePreparedSessions.size() + numSessionsBeingPrepared;
      return preparedSessions < Math.floor(options.getWriteSessionsFraction() * totalSessions());
    }
  }

  private int numWaiters() {
    synchronized (lock) {
      return readWaiters.size() + readWriteWaiters.size();
    }
  }

  @VisibleForTesting
  int totalSessions() {
    synchronized (lock) {
      return allSessions.size();
    }
  }

  private ApiFuture<Empty> closeSessionAsync(final PooledSession sess) {
    ApiFuture<Empty> res = sess.delegate.asyncClose();
    res.addListener(
        new Runnable() {
          @Override
          public void run() {
            synchronized (lock) {
              allSessions.remove(sess);
              if (isClosed()) {
                decrementPendingClosures(1);
                return;
              }
              // Create a new session if needed to unblock some waiter.
              if (numWaiters() > numSessionsBeingCreated) {
                createSessions(
                    getAllowedCreateSessions(numWaiters() - numSessionsBeingCreated), false);
              }
            }
          }
        },
        executor);
    return res;
  }

  private void prepareSession(final PooledSession sess) {
    synchronized (lock) {
      numSessionsBeingPrepared++;
    }
    prepareExecutor.submit(
        new Runnable() {
          @Override
          public void run() {
            try {
              logger.log(Level.FINE, "Preparing session");
              sess.prepareReadWriteTransaction();
              logger.log(Level.FINE, "Session prepared");
              synchronized (lock) {
                numSessionsAsyncPrepared++;
                numSessionsBeingPrepared--;
                if (!isClosed()) {
                  if (readWriteWaiters.size() > 0) {
                    readWriteWaiters.poll().put(sess);
                  } else if (readWaiters.size() > 0) {
                    readWaiters.poll().put(sess);
                  } else {
                    writePreparedSessions.add(sess);
                  }
                }
              }
            } catch (Throwable t) {
              synchronized (lock) {
                numSessionsBeingPrepared--;
                if (!isClosed()) {
                  handlePrepareSessionFailure(newSpannerException(t), sess, true);
                }
              }
            }
          }
        });
  }

  /**
   * Returns the minimum of the wanted number of sessions that the caller wants to create and the
   * actual max number that may be created at this moment.
   */
  private int getAllowedCreateSessions(int wantedSessions) {
    synchronized (lock) {
      return Math.min(
          wantedSessions, options.getMaxSessions() - (totalSessions() + numSessionsBeingCreated));
    }
  }

  private boolean canCreateSession() {
    synchronized (lock) {
      return totalSessions() + numSessionsBeingCreated < options.getMaxSessions();
    }
  }

  private void createSessions(final int sessionCount, boolean distributeOverChannels) {
    logger.log(Level.FINE, String.format("Creating %d sessions", sessionCount));
    synchronized (lock) {
      numSessionsBeingCreated += sessionCount;
      try {
        // Create a batch of sessions. The actual session creation can be split into multiple gRPC
        // calls and the session consumer consumes the returned sessions as they become available.
        // The batchCreateSessions method automatically spreads the sessions evenly over all
        // available channels.
        sessionClient.asyncBatchCreateSessions(
            sessionCount, distributeOverChannels, sessionConsumer);
      } catch (Throwable t) {
        // Expose this to customer via a metric.
        numSessionsBeingCreated -= sessionCount;
        if (isClosed()) {
          decrementPendingClosures(sessionCount);
        }
        handleCreateSessionsFailure(newSpannerException(t), sessionCount);
      }
    }
  }

  /**
   * {@link SessionConsumer} that receives the created sessions from a {@link SessionClient} and
   * releases these into the pool. The session pool only needs one instance of this, as all sessions
   * should be returned to the same pool regardless of what triggered the creation of the sessions.
   */
  class SessionConsumerImpl implements SessionConsumer {
    /** Release a new session to the pool. */
    @Override
    public void onSessionReady(SessionImpl session) {
      PooledSession pooledSession = null;
      boolean closeSession = false;
      synchronized (lock) {
        pooledSession = new PooledSession(session);
        numSessionsBeingCreated--;
        if (closureFuture != null) {
          closeSession = true;
        } else {
          Preconditions.checkState(totalSessions() <= options.getMaxSessions() - 1);
          allSessions.add(pooledSession);
          // Release the session to a random position in the pool to prevent the case that a batch
          // of sessions that are affiliated with the same channel are all placed sequentially in
          // the pool.
          releaseSession(pooledSession, Position.RANDOM);
        }
      }
      if (closeSession) {
        closeSessionAsync(pooledSession);
      }
    }

    /**
     * Informs waiters for a session that session creation failed. The exception will propagate to
     * the waiters as a {@link SpannerException}.
     */
    @Override
    public void onSessionCreateFailure(Throwable t, int createFailureForSessionCount) {
      synchronized (lock) {
        numSessionsBeingCreated -= createFailureForSessionCount;
        if (isClosed()) {
          decrementPendingClosures(createFailureForSessionCount);
        }
        handleCreateSessionsFailure(newSpannerException(t), createFailureForSessionCount);
      }
    }
  }

  /**
   * Initializes and creates Spanner session relevant metrics. When coupled with an exporter, it
   * allows users to monitor client behavior.
   */
  private void initMetricsCollection(MetricRegistry metricRegistry, List<LabelValue> labelValues) {
    DerivedLongGauge maxInUseSessionsMetric =
        metricRegistry.addDerivedLongGauge(
            MAX_IN_USE_SESSIONS,
            MetricOptions.builder()
                .setDescription(MAX_IN_USE_SESSIONS_DESCRIPTION)
                .setUnit(COUNT)
                .setLabelKeys(SPANNER_LABEL_KEYS)
                .build());

    DerivedLongGauge maxAllowedSessionsMetric =
        metricRegistry.addDerivedLongGauge(
            MAX_ALLOWED_SESSIONS,
            MetricOptions.builder()
                .setDescription(MAX_ALLOWED_SESSIONS_DESCRIPTION)
                .setUnit(COUNT)
                .setLabelKeys(SPANNER_LABEL_KEYS)
                .build());

    DerivedLongGauge numInUseSessionsMetric =
        metricRegistry.addDerivedLongGauge(
            IN_USE_SESSIONS,
            MetricOptions.builder()
                .setDescription(IN_USE_SESSIONS_DESCRIPTION)
                .setUnit(COUNT)
                .setLabelKeys(SPANNER_LABEL_KEYS)
                .build());

    DerivedLongCumulative sessionsTimeouts =
        metricRegistry.addDerivedLongCumulative(
            GET_SESSION_TIMEOUTS,
            MetricOptions.builder()
                .setDescription(SESSIONS_TIMEOUTS_DESCRIPTION)
                .setUnit(COUNT)
                .setLabelKeys(SPANNER_LABEL_KEYS)
                .build());

    DerivedLongCumulative numAcquiredSessionsMetric =
        metricRegistry.addDerivedLongCumulative(
            NUM_ACQUIRED_SESSIONS,
            MetricOptions.builder()
                .setDescription(NUM_ACQUIRED_SESSIONS_DESCRIPTION)
                .setUnit(COUNT)
                .setLabelKeys(SPANNER_LABEL_KEYS)
                .build());

    DerivedLongCumulative numReleasedSessionsMetric =
        metricRegistry.addDerivedLongCumulative(
            NUM_RELEASED_SESSIONS,
            MetricOptions.builder()
                .setDescription(NUM_RELEASED_SESSIONS_DESCRIPTION)
                .setUnit(COUNT)
                .setLabelKeys(SPANNER_LABEL_KEYS)
                .build());

    // The value of a maxSessionsInUse is observed from a callback function. This function is
    // invoked whenever metrics are collected.
    maxInUseSessionsMetric.createTimeSeries(
        labelValues,
        this,
        new ToLongFunction<SessionPool>() {
          @Override
          public long applyAsLong(SessionPool sessionPool) {
            return sessionPool.maxSessionsInUse;
          }
        });

    // The value of a maxSessions is observed from a callback function. This function is invoked
    // whenever metrics are collected.
    maxAllowedSessionsMetric.createTimeSeries(
        labelValues,
        options,
        new ToLongFunction<SessionPoolOptions>() {
          @Override
          public long applyAsLong(SessionPoolOptions options) {
            return options.getMaxSessions();
          }
        });

    // The value of a numSessionsInUse is observed from a callback function. This function is
    // invoked whenever metrics are collected.
    numInUseSessionsMetric.createTimeSeries(
        labelValues,
        this,
        new ToLongFunction<SessionPool>() {
          @Override
          public long applyAsLong(SessionPool sessionPool) {
            return sessionPool.numSessionsInUse;
          }
        });

    // The value of a numWaiterTimeouts is observed from a callback function. This function is
    // invoked whenever metrics are collected.
    sessionsTimeouts.createTimeSeries(
        labelValues,
        this,
        new ToLongFunction<SessionPool>() {
          @Override
          public long applyAsLong(SessionPool sessionPool) {
            return sessionPool.getNumWaiterTimeouts();
          }
        });

    numAcquiredSessionsMetric.createTimeSeries(
        labelValues,
        this,
        new ToLongFunction<SessionPool>() {
          @Override
          public long applyAsLong(SessionPool sessionPool) {
            return sessionPool.numSessionsAcquired;
          }
        });

    numReleasedSessionsMetric.createTimeSeries(
        labelValues,
        this,
        new ToLongFunction<SessionPool>() {
          @Override
          public long applyAsLong(SessionPool sessionPool) {
            return sessionPool.numSessionsReleased;
          }
        });
  }
}
