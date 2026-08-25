package ru.petstore.benchmarks;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import ru.petstore.common.reference.ReferenceItem;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(2)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Threads(4)
@State(Scope.Benchmark)
public class ReferenceCacheReadBenchmark {

    @Param({"PETSTORE", "SYNCHRONIZED_TREE_MAP", "SKIP_LIST"})
    public ReferenceStore.Kind implementation;

    @Param({"32", "512"})
    public int size;

    private ReferenceStore store;
    private String[] codes;

    @Setup(Level.Trial)
    public void setUp() {
        Map<String, ReferenceItem> data = ReferenceData.sample(size);
        store = ReferenceStore.of(implementation, data);
        codes = data.keySet().toArray(String[]::new);
    }

    @Benchmark
    public ReferenceItem lookup(Cursor cursor) {
        return store.get(cursor.next(codes));
    }

    @Benchmark
    public List<ReferenceItem> listAll() {
        return store.all();
    }
}
