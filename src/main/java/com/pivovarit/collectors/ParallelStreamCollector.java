package com.pivovarit.collectors;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Stream;

import static com.pivovarit.collectors.AsyncParallelCollector.requireValidParallelism;
import static com.pivovarit.collectors.BatchingStream.batching;
import static com.pivovarit.collectors.BatchingStream.partitioned;
import static com.pivovarit.collectors.CompletionStrategy.ordered;
import static com.pivovarit.collectors.CompletionStrategy.unordered;
import static com.pivovarit.collectors.Dispatcher.getDefaultParallelism;
import static com.pivovarit.collectors.Dispatcher.of;
import static java.util.Collections.emptySet;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.toList;

/**
 * @author Grzegorz Piwowarek
 */
class ParallelStreamCollector<T, R> implements Collector<T, Stream.Builder<CompletableFuture<R>>, Stream<R>> {

    private static final EnumSet<Characteristics> UNORDERED = EnumSet.of(Characteristics.UNORDERED);

    private final Dispatcher<R> dispatcher;
    private final Function<T, R> function;
    private final CompletionStrategy<R> completionStrategy;
    private final Set<Characteristics> characteristics;

    private ParallelStreamCollector(
      Function<T, R> function,
      CompletionStrategy<R> completionStrategy,
      Set<Characteristics> characteristics,
      Dispatcher<R> dispatcher) {
        this.completionStrategy = completionStrategy;
        this.characteristics = characteristics;
        this.dispatcher = dispatcher;
        this.function = function;
    }

    private void startConsuming() {
        if (!dispatcher.isRunning()) {
            dispatcher.start();
        }
    }

    @Override
    public Supplier<Stream.Builder<CompletableFuture<R>>> supplier() {
        return Stream::builder;
    }

    @Override
    public BiConsumer<Stream.Builder<CompletableFuture<R>>, T> accumulator() {
        return (acc, e) -> {
            startConsuming();
            acc.add(dispatcher.enqueue(() -> function.apply(e)));
        };
    }

    @Override
    public BinaryOperator<Stream.Builder<CompletableFuture<R>>> combiner() {
        return (left, right) -> {
            throw new UnsupportedOperationException("Using parallel stream with parallel collectors is a bad idea");
        };
    }

    @Override
    public Function<Stream.Builder<CompletableFuture<R>>, Stream<R>> finisher() {
        return acc -> {
            dispatcher.stop();
            return completionStrategy.apply(acc.build());
        };
    }

    @Override
    public Set<Characteristics> characteristics() {
        return characteristics;
    }

    static <T, R> Collector<T, ?, Stream<R>> streaming(Function<T, R> mapper, Executor executor) {
        return streaming(mapper, executor, getDefaultParallelism());
    }

    static <T, R> Collector<T, ?, Stream<R>> streaming(Function<T, R> mapper, Executor executor, int parallelism) {
        requireNonNull(executor, "executor can't be null");
        requireNonNull(mapper, "mapper can't be null");
        requireValidParallelism(parallelism);

        return parallelism == 1
          ? BatchingCollectors.syncCollector(mapper)
          : new ParallelStreamCollector<>(mapper, unordered(), UNORDERED, of(executor, parallelism));
    }

    static <T, R> Collector<T, ?, Stream<R>> streamingOrdered(Function<T, R> mapper, Executor executor) {
        return streamingOrdered(mapper, executor, getDefaultParallelism());
    }

    static <T, R> Collector<T, ?, Stream<R>> streamingOrdered(Function<T, R> mapper, Executor executor, int parallelism) {
        requireNonNull(executor, "executor can't be null");
        requireNonNull(mapper, "mapper can't be null");
        requireValidParallelism(parallelism);

        return parallelism == 1
          ? BatchingCollectors.syncCollector(mapper)
          : new ParallelStreamCollector<>(mapper, ordered(), emptySet(), of(executor, parallelism));
    }

    static final class BatchingCollectors {
        private BatchingCollectors() {
        }

        static <T, R> Collector<T, ?, Stream<R>> streaming(Function<T, R> mapper, Executor executor, int parallelism) {
            requireNonNull(executor, "executor can't be null");
            requireNonNull(mapper, "mapper can't be null");
            requireValidParallelism(parallelism);

            return parallelism == 1
              ? syncCollector(mapper)
              : batched(new ParallelStreamCollector<>(batching(mapper), unordered(), UNORDERED, Dispatcher.of(executor, parallelism)), parallelism);
        }

        static <T, R> Collector<T, ?, Stream<R>> streamingOrdered(Function<T, R> mapper, Executor executor, int parallelism) {
            requireNonNull(executor, "executor can't be null");
            requireNonNull(mapper, "mapper can't be null");
            requireValidParallelism(parallelism);

            return parallelism == 1
              ? syncCollector(mapper)
              : batched(new ParallelStreamCollector<>(batching(mapper), ordered(), emptySet(), Dispatcher.of(executor, parallelism)), parallelism);
        }

        private static <T, R> Collector<T, ?, Stream<R>> batched(ParallelStreamCollector<List<T>, List<R>> downstream, int parallelism) {
            return collectingAndThen(
              toList(),
              list -> partitioned(list, parallelism)
                .collect(collectingAndThen(downstream, s -> s.flatMap(Collection::stream))));
        }

        private static <T, R> Collector<T, Stream.Builder<R>, Stream<R>> syncCollector(Function<T, R> mapper) {
            return Collector.of(Stream::builder, (rs, t) -> rs.add(mapper.apply(t)), (rs, rs2) -> {
                throw new UnsupportedOperationException("Using parallel stream with parallel collectors is a bad idea");
            }, Stream.Builder::build);
        }
    }
}
