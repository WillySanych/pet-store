package ru.petstore.benchmarks;

import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

@State(Scope.Thread)
public class Cursor {

    private int index;

    String next(String[] codes) {
        int i = index + 1;
        index = i == codes.length ? 0 : i;
        return codes[index];
    }
}
